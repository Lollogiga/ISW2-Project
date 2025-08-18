package it.project.controllers;

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
                    javaMethod.setFanIn(fanInMap.getOrDefault(makeKey(javaClass.getPath(), javaMethod.getName()), 0));

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
        String methodId = filePath + "::" + method.getName();
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

    /**
     * Calcola il Fan-In per tutti i metodi della release: numero di metodi distinti che li invocano.
     * Ritorna una mappa keyMetodo -> fanIn, dove la chiave è "pathRelativo::methodName"
     * coerente con quella che usi nel dataset.
     */
    private Map<String, Integer> computeFanInMapForRelease(Path repoRoot, List<JavaClass> classesInRelease) {
        // 1) Indici utili
        Map<String, JavaMethod> keyToMethod = new HashMap<>();
        Map<String, String> declIndex = new HashMap<>(); // "FQN#name" o "path::name" -> canonicalKey (path::name)
        Set<Path> filesToParse = new LinkedHashSet<>();

        for (JavaClass jc : classesInRelease) {
            Path p = repoRoot.resolve(jc.getPath());
            filesToParse.add(p);
            for (JavaMethod jm : jc.getMethods()) {
                keyToMethod.put(makeKey(jc.getPath(), jm.getName()), jm);
            }
        }

        // 2) Configura il symbol solver (reflection + sorgenti del repo)
        CombinedTypeSolver typeSolver = new CombinedTypeSolver(
                new ReflectionTypeSolver(false),
                new JavaParserTypeSolver(repoRoot.toFile())
        );
        StaticJavaParser.getConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver));

        // 3) Prima passata: indicizza tutte le dichiarazioni (per fallback risoluzione)
        for (Path p : filesToParse) {
            if (!safeIsJavaFile(p)) continue;
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(p);
            } catch (Exception parseEx) {
                continue;
            }
            String relPath = toUnix(repoRoot.relativize(p).toString());
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String mName = md.getNameAsString();
                String canonicalKey = makeKey(relPath, mName);

                // owner FQN (se disponibile) -> canonical key
                String ownerFqn = md.findAncestor(ClassOrInterfaceDeclaration.class)
                        .flatMap(coid -> coid.getFullyQualifiedName()).orElse(null);
                if (ownerFqn != null) {
                    declIndex.put(ownerFqn + "#" + mName, canonicalKey);
                }
                // path::name come ulteriore chiave
                declIndex.put(relPath + "::" + mName, canonicalKey);
            }
        }

        // 4) Seconda passata: costruisci incoming callers (callee -> set di caller)
        Map<String, Set<String>> incoming = new HashMap<>();

        for (Path p : filesToParse) {
            if (!safeIsJavaFile(p)) continue;
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(p);
            } catch (Exception parseEx) {
                continue;
            }
            String relPath = toUnix(repoRoot.relativize(p).toString());

            cu.findAll(MethodCallExpr.class).forEach(call -> {
                // Caller = metodo che contiene la call
                String callerKey = call.findAncestor(MethodDeclaration.class)
                        .map(md -> makeKey(relPath, md.getNameAsString()))
                        .orElse(relPath + "::<init>");

                String calleeKey = null;

                // 4a) Prova risoluzione con SymbolSolver (versioni recenti: ResolvedMethodDeclaration)
                try {
                    ResolvedMethodDeclaration rd = call.resolve();
                    String calleeName = rd.getName();

                    String ownerFqn = null;
                    try {
                        ownerFqn = rd.declaringType().getQualifiedName(); // es: com.example.Foo
                    } catch (UnsupportedOperationException | IllegalStateException ex) {
                        // alcune volte declaringType() può fallire; come fallback puoi usare la firma qualificata
                        // String qs = rd.getQualifiedSignature(); // es: com.example.Foo.bar(int)
                        // Se vuoi, puoi parsare qs per ottenere ownerFqn e nome, ma qui continuiamo coi fallback sotto.
                    }

                    if (ownerFqn != null) {
                        String mapped = declIndex.get(ownerFqn + "#" + calleeName);
                        if (mapped != null) calleeKey = mapped;
                    }
                } catch (Throwable ignore) {
                    // fallisce? useremo i fallback 4b/4c
                }

                // 4b) Fallback 1: se lo scope è risolvibile → usa il suo FQN (es. com.foo.Bar)
                if (calleeKey == null) {
                    try {
                        if (call.getScope().isPresent()) {
                            var scopeExpr = call.getScope().get();
                            var rt = scopeExpr.calculateResolvedType();

                            String fqnScope = null;
                            if (rt.isReferenceType()) {

                                com.github.javaparser.resolution.types.ResolvedReferenceType rrt = rt.asReferenceType();
                                fqnScope = rrt.getQualifiedName();
                            } else if (rt.isArray() && rt.asArrayType().getComponentType().isReferenceType()) {
                                // caso raro: array di tipo riferimento
                                fqnScope = rt.asArrayType()
                                        .getComponentType()
                                        .asReferenceType()
                                        .getQualifiedName();
                            }

                            if (fqnScope != null) {
                                String mapped = declIndex.get(fqnScope + "#" + call.getNameAsString());
                                if (mapped != null) calleeKey = mapped;
                            }
                        }
                    } catch (Throwable ignore) {
                        // Ignora: non siamo riusciti a risolvere lo scope
                    }
                }


                // 4c) Fallback 2: stesso file (path::name)
                if (calleeKey == null) {
                    String mapped = declIndex.get(relPath + "::" + call.getNameAsString());
                    if (mapped != null) calleeKey = mapped;
                }

                if (calleeKey != null && keyToMethod.containsKey(calleeKey) && keyToMethod.containsKey(callerKey)) {
                    incoming.computeIfAbsent(calleeKey, k -> new HashSet<>()).add(callerKey);
                }
            });
        }

        // 5) Converte in mappa <key, fanIn>
        Map<String, Integer> fanIn = new HashMap<>();
        for (String k : keyToMethod.keySet()) {
            fanIn.put(k, incoming.getOrDefault(k, Collections.emptySet()).size());
        }
        return fanIn;
    }

    private static boolean safeIsJavaFile(Path p) {
        try { return java.nio.file.Files.isRegularFile(p) && p.toString().endsWith(".java"); }
        catch (Exception e) { return false; }
    }

    private static String makeKey(String classPath, String methodName) {
        return toUnix(classPath) + "::" + methodName;
    }

    private static String toUnix(String rel) {
        return rel.replace(File.separatorChar, '/');
    }

}