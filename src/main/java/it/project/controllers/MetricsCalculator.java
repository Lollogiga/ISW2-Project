package it.project.controllers;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import it.project.entities.JavaClass;
import it.project.entities.JavaMethod;
import it.project.entities.Release;
import it.project.entities.Smell;
import it.project.utils.PmdParser;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;


public class MetricsCalculator {

    private final Git git;


    public MetricsCalculator(Git git) {
        this.git = git;

    }

    public void calculateHistoricalMetrics(List<Release> releases) throws IOException {
        Map<String, Set<String>> previousAuthors = new HashMap<>();

        for (Release release : releases) {
            Logger.getAnonymousLogger().log(Level.INFO, "Calcolo metriche per release {0}...", release.getName());

            File reportFile = new File(git.getRepository().getWorkTree(), "pmd-reports/pmd-" + release.getName() + ".xml");
            Path repoRoot = git.getRepository().getWorkTree().toPath().toAbsolutePath();
            Map<String, Set<String>> currentAuthors = new HashMap<>();
            Map<String, Integer> fanInMap = computeFanInMapForRelease(repoRoot, release.getJavaClassList());

            for (JavaClass javaClass : release.getJavaClassList()) {
                String relativePath = javaClass.getPath();

                for (JavaMethod javaMethod : javaClass.getMethods()) {
                    calculateMetricsForMethod(javaMethod, release.getCommitList(), previousAuthors, currentAuthors);

                    // parseReport viene richiamato come nel codice originale
                    Map<String, List<Smell>> smellsMap = new PmdParser().parseReport(reportFile, repoRoot.toFile());
                    int nSmells = countSmellsForMethod(smellsMap.getOrDefault(relativePath, Collections.emptyList()), javaMethod);
                    javaMethod.setnSmells(nSmells);
                    // Set Fan-In sul metodo (chiave: path::methodName)
                    javaMethod.setFanIn(fanInMap.getOrDefault(makeKey(javaClass.getPath(), javaMethod.getName(), javaMethod.getSignature()), 0));

                }
            }

            previousAuthors = currentAuthors;
        }
    }

    private int countSmellsForMethod(List<Smell> smells, JavaMethod method) {
        int count = 0;
        int start = method.getStartLine();
        int end = method.getEndLine();

        for (Smell smell : smells) {
            if (smell.getBeginLine() >= start && smell.getBeginLine() <= end) {
                count++;
            }
        }

        return count;
    }



    private void calculateMetricsForMethod(JavaMethod method, List<RevCommit> commits,
                                           Map<String, Set<String>> previousAuthors,
                                           Map<String, Set<String>> currentAuthors) throws IOException {

        int totalChurn = 0;
        int locAdded = 0;
        Set<String> authors = new HashSet<>();
        int weekendCommits = 0;
        int totalCommitsForMethod = 0;

        String filePath = method.getRelease().getJavaClassList().stream()
                .filter(c -> c.getMethods().contains(method))
                .findFirst().get().getPath();

        for (RevCommit commit : commits) {
            if (commit.getParentCount() == 0) continue; // Salta il primo commit del repo

            RevCommit parent = commit.getParent(0);
            boolean methodTouched = wasMethodTouched(commit, parent, filePath, method.getStartLine(), method.getEndLine());

            if (methodTouched) {
                totalCommitsForMethod++;
                authors.add(commit.getAuthorIdent().getEmailAddress());

                // Calcolo Churn e LOC Added
                int[] churnAndAdded = calculateChurnAndLocAdded(commit, parent, filePath, method.getStartLine(), method.getEndLine());
                totalChurn += churnAndAdded[0];
                locAdded += churnAndAdded[1];

                // Calcolo Weekend Commit Ratio
                LocalDateTime commitDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(commit.getCommitTime()), ZoneId.systemDefault());
                if (commitDate.getDayOfWeek() == DayOfWeek.SATURDAY || commitDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    weekendCommits++;
                }
            }
        }

        method.setChurn(totalChurn);
        method.setLocAdded(locAdded);
        method.setnAuth(authors.size());
        method.setWeekendCommit(totalCommitsForMethod > 0 ? (double) weekendCommits / totalCommitsForMethod : 0.0);

        // Calcolo Newcomer Risk
        String methodId = it.project.utils.MethodSig.key(filePath, method.getName(), method.getSignature());
        Set<String> prevAuthorsSet = previousAuthors.getOrDefault(methodId, Collections.emptySet());
        long newAuthorsCount = authors.stream().filter(author -> !prevAuthorsSet.contains(author)).count();
        method.setNewcomerRisk(newAuthorsCount > 0 ? 1.0 : 0.0);

        // Memorizza gli autori di questo metodo per la prossima release
        currentAuthors.put(methodId, authors);
    }

    // Metodo helper per controllare se un metodo è stato toccato in un commit
    private boolean wasMethodTouched(RevCommit commit, RevCommit parent, String filePath, int startLine, int endLine) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(git.getRepository());
            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());

            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(filePath)) {
                    for (Edit edit : diffFormatter.toFileHeader(diff).toEditList()) {
                        int changeStart = edit.getBeginB();
                        int changeEnd = edit.getEndB();
                        // Controlla se il range di modifica si sovrappone al range del metodo
                        if (Math.max(startLine, changeStart) <= Math.min(endLine, changeEnd)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // Metodo helper per calcolare Churn e LOC Added
    private int[] calculateChurnAndLocAdded(RevCommit commit, RevCommit parent, String filePath, int startLine, int endLine) throws IOException {
        int churn = 0;
        int locAdded = 0;
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(git.getRepository());
            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());
            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(filePath)) {
                    for (Edit edit : diffFormatter.toFileHeader(diff).toEditList()) {
                        // Considera solo le modifiche che toccano il metodo
                        if (Math.max(startLine, edit.getBeginB()) <= Math.min(endLine, edit.getEndB())) {
                            churn += edit.getLengthA(); // Linee cancellate
                            churn += edit.getLengthB(); // Linee aggiunte
                            locAdded += edit.getLengthB();
                        }
                    }
                }
            }
        }
        return new int[]{churn, locAdded};
    }


    // ------------------------------------------------------------
// PUBLIC/MAIN ORCHESTRATOR
// ------------------------------------------------------------
    /**
     * Calcola il Fan-In per tutti i metodi della release: numero di metodi distinti che li invocano.
     * Ritorna una mappa keyMetodo -> fanIn, dove la chiave è "pathRelativo::methodName".
     */
    private Map<String, Integer> computeFanInMapForRelease(Path repoRoot, List<JavaClass> classesInRelease) {
        // 1) Indici di base e set file da analizzare
        Map<String, JavaMethod> keyToMethod = buildKeyToMethod(classesInRelease);
        Set<Path> filesToParse = collectFilesToParse(repoRoot, classesInRelease);

        // 2) Configura Symbol Solver
        configureSymbolSolver(repoRoot);

        // 3) Prima passata: indicizza dichiarazioni (fallback)
        Map<String, String> declIndex = buildDeclarationIndex(repoRoot, filesToParse);

        // 4) Seconda passata: costruisci grafo incoming (callee -> callers)
        Map<String, Set<String>> incoming = buildIncomingGraph(repoRoot, filesToParse, keyToMethod, declIndex);

        // 5) Aggrega in <key, fanIn>
        return toFanInMap(keyToMethod, incoming);
    }

    // ------------------------------------------------------------
// STEP 1: raccolta indici/metadati
// ------------------------------------------------------------
    private Map<String, JavaMethod> buildKeyToMethod(List<JavaClass> classesInRelease) {
        Map<String, JavaMethod> keyToMethod = new HashMap<>();
        for (JavaClass jc : classesInRelease) {
            String relPath = jc.getPath();
            for (JavaMethod jm : jc.getMethods()) {
                keyToMethod.put(makeKey(relPath, jm.getName(), jm.getSignature()), jm);
            }
        }
        return keyToMethod;
    }


    private Set<Path> collectFilesToParse(Path repoRoot, List<JavaClass> classesInRelease) {
        Set<Path> filesToParse = new LinkedHashSet<>();
        for (JavaClass jc : classesInRelease) {
            filesToParse.add(repoRoot.resolve(jc.getPath()));
        }
        return filesToParse;
    }

    // ------------------------------------------------------------
// STEP 2: configurazione SymbolSolver
// ------------------------------------------------------------
    private void configureSymbolSolver(Path repoRoot) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(false),
                new JavaParserTypeSolver(repoRoot.toFile())
        );

        ParserConfiguration parserConfiguration = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));

        StaticJavaParser.setConfiguration(parserConfiguration);
    }


    // ------------------------------------------------------------
// STEP 3: prima passata - indice dichiarazioni
// ------------------------------------------------------------
    private Map<String, String> buildDeclarationIndex(Path repoRoot, Set<Path> filesToParse) {
        Map<String, String> declIndex = new HashMap<>();
        for (Path p : filesToParse) {
            if (safeIsJavaFile(p)) {
                CompilationUnit cu = parseQuietly(p);
                if (cu != null) {
                    String relPath = toUnix(repoRoot.relativize(p).toString());
                    indexDeclarationsFromCU(cu, relPath, declIndex);
                }
            }
        }
        return declIndex;
    }


    private void indexDeclarationsFromCU(CompilationUnit cu, String relPath, Map<String, String> declIndex) {
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            String mName = md.getNameAsString();
            String sig   = it.project.utils.MethodSig.fromAst(md);
            String canonicalKey = makeKey(relPath, mName, sig);

            String ownerFqn = md.findAncestor(ClassOrInterfaceDeclaration.class)
                    .flatMap(ClassOrInterfaceDeclaration::getFullyQualifiedName)
                    .orElse(null);

            if (ownerFqn != null) {
                // mappa FQN#name(signature) -> key canonica
                declIndex.put(ownerFqn + "#" + mName + sig, canonicalKey);
            }
            // path::name(signature)
            declIndex.put(relPath + "::" + mName + sig, canonicalKey);
        }
    }



    // ------------------------------------------------------------
// STEP 4: seconda passata - grafo incoming
// ------------------------------------------------------------
    private Map<String, Set<String>> buildIncomingGraph(
            Path repoRoot,
            Set<Path> filesToParse,
            Map<String, JavaMethod> keyToMethod,
            Map<String, String> declIndex
    ) {
        Map<String, Set<String>> incoming = new HashMap<>();

        for (Path p : filesToParse) {
            if (safeIsJavaFile(p)) {
                CompilationUnit cu = parseQuietly(p);
                if (cu != null) {
                    String relPath = toUnix(repoRoot.relativize(p).toString());
                    addIncomingEdgesFromCU(cu, relPath, keyToMethod, declIndex, incoming);
                }
            }
        }
        return incoming;
    }


    private void addIncomingEdgesFromCU(
            CompilationUnit cu,
            String relPath,
            Map<String, JavaMethod> keyToMethod,
            Map<String, String> declIndex,
            Map<String, Set<String>> incoming
    ) {
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            String callerKey = findCallerKey(call, relPath);
            String calleeKey  = resolveCalleeKey(call, relPath, declIndex);

            if (calleeKey == null) return;
            if (!keyToMethod.containsKey(calleeKey)) return;
            if (!keyToMethod.containsKey(callerKey)) return;

            incoming.computeIfAbsent(calleeKey, k -> new HashSet<>()).add(callerKey);
        });
    }

    private String findCallerKey(MethodCallExpr call, String relPath) {
        // Caso 1: siamo dentro a un metodo normale
        var mdOpt = call.findAncestor(MethodDeclaration.class);
        if (mdOpt.isPresent()) {
            MethodDeclaration md = mdOpt.get();
            String sig = it.project.utils.MethodSig.fromAst(md);
            return makeKey(relPath, md.getNameAsString(), sig);
        }

        // Caso 2: siamo dentro a un costruttore
        var cdOpt = call.findAncestor(com.github.javaparser.ast.body.ConstructorDeclaration.class);
        if (cdOpt.isPresent()) {
            var cd = cdOpt.get();
            String sig = it.project.utils.MethodSig.fromAst(cd);
            // per i costruttori usa un nome sentinel, es. "<init>"
            return it.project.utils.MethodSig.key(relPath, "<init>", sig);
        }

        // Fallback estremo: chiave anonima (evita collisioni senza firma)
        return it.project.utils.MethodSig.key(relPath, "<unknown>", "()");
    }


    // ------------------------------------------------------------
// Risoluzione callee: proverà (A) SymbolSolver, poi (B) scope, poi (C) path::name
// ------------------------------------------------------------
    private String resolveCalleeKey(MethodCallExpr call, String relPath, Map<String, String> declIndex) {
        String key = tryResolveWithSymbolSolver(call, declIndex);
        if (key != null) return key;

        key = tryResolveFromScope(call, declIndex);
        if (key != null) return key;

        return tryResolveSameFile(call, relPath, declIndex);
    }

    private String tryResolveWithSymbolSolver(MethodCallExpr call, Map<String, String> declIndex) {
        try {
            ResolvedMethodDeclaration rd = call.resolve();
            String ownerFqn = safeDeclaringTypeFqn(rd);
            if (ownerFqn == null) return null;

            String sig = it.project.utils.MethodSig.fromResolved(rd); // "(...)" già risolta
            String name = rd.getName();

            return declIndex.get(ownerFqn + "#" + name + sig);
        } catch (Exception _) {
            return null;
        }
    }


    private String safeDeclaringTypeFqn(ResolvedMethodDeclaration rd) {
        try {
            return rd.declaringType().getQualifiedName();
        } catch (UnsupportedOperationException | IllegalStateException _) {
            return null;
        }
    }

    // Helper: prova a risolvere la chiamata con SymbolSolver e fare lookup nel declIndex
    private String lookupByResolved(MethodCallExpr call, String fqnScope, Map<String, String> declIndex) {
        try {
            var rd  = call.resolve();
            String sig  = it.project.utils.MethodSig.fromResolved(rd);
            String name = rd.getName();
            return declIndex.get(fqnScope + "#" + name + sig);
        } catch (Exception _) {
            return null;
        }
    }

    // Helper: estrae l'FQN del tipo dallo scope (obj.method() / arr[i].method())
    private String resolveScopeFqn(com.github.javaparser.ast.expr.Expression scopeExpr) {
        try {
            var rt = scopeExpr.calculateResolvedType();

            if (rt.isReferenceType()) {
                return rt.asReferenceType().getQualifiedName();
            }
            if (rt.isArray() && rt.asArrayType().getComponentType().isReferenceType()) {
                return rt.asArrayType().getComponentType().asReferenceType().getQualifiedName();
            }
            return null;
        } catch (Exception _) {
            return null;
        }
    }

    private String tryResolveFromScope(MethodCallExpr call, Map<String, String> declIndex) {
        var scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return null;

        String fqnScope = resolveScopeFqn(scopeOpt.get());
        if (fqnScope == null) return null;

        // Unico punto "esterno" che fa la risoluzione dettagliata
        return lookupByResolved(call, fqnScope, declIndex);
    }



    private String tryResolveSameFile(MethodCallExpr call, String relPath, Map<String, String> declIndex) {
        try {
            var rd = call.resolve();
            String sig = it.project.utils.MethodSig.fromResolved(rd);
            return declIndex.get(relPath + "::" + call.getNameAsString() + sig);
        } catch (Exception _) {
            return null;
        }
    }


    // ------------------------------------------------------------
// STEP 5: riduzione a fan-in
// ------------------------------------------------------------
    private Map<String, Integer> toFanInMap(Map<String, JavaMethod> keyToMethod, Map<String, Set<String>> incoming) {
        Map<String, Integer> fanIn = new HashMap<>();
        for (String k : keyToMethod.keySet()) {
            fanIn.put(k, incoming.getOrDefault(k, Collections.emptySet()).size());
        }
        return fanIn;
    }


    // ------------------------------------------------------------
// Utility parsing silenziosa (riduce branching)
// ------------------------------------------------------------
    private CompilationUnit parseQuietly(Path p) {
        try {
            return StaticJavaParser.parse(p);
        } catch (Exception _) {
            return null;
        }
    }


    private static boolean safeIsJavaFile(Path p) {
        try { return java.nio.file.Files.isRegularFile(p) && p.toString().endsWith(".java"); }
        catch (Exception _) { return false; }
    }

    private static String makeKey(String classPath, String methodName, String signature) {
        return it.project.utils.MethodSig.key(classPath, methodName, signature);
    }


    private static String toUnix(String rel) {
        return rel.replace(File.separatorChar, '/');
    }

}