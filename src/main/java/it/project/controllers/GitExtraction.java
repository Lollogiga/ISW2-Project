package it.project.controllers;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import it.project.entities.JavaClass;
import it.project.entities.JavaMethod;
import it.project.entities.Release;
import it.project.utils.RepoFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class GitExtraction {

    private static final String JAVA_EXTENSION = ".java";
    private final Git git;
    private final String pmdPath;

    public GitExtraction(String pmdPath) throws IOException {
        this.git = RepoFactory.getGit();
        this.pmdPath = pmdPath;
    }


    public void analyzeReleaseCode(Release release) throws IOException {
        if (release.getCommitList().isEmpty()) {
            Logger.getAnonymousLogger().log(Level.WARNING, "Release {0} has 0 commits, code will not be analyzed.", release.getName());
            return;
        }

        RevCommit lastCommit = release.getCommitList().get(release.getCommitList().size() - 1);

        Logger.getAnonymousLogger().log(Level.INFO,
                "Analisi codice per release {0} (commit: {1})",
                new Object[]{release.getName(), lastCommit.getId().getName()});

        // Checkout reale nel working tree
        try {
            git.checkout().setName(lastCommit.getName()).call();
            Logger.getAnonymousLogger().log(Level.INFO, "Checkout effettuato per il commit della release {0}", release.getName());
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante il checkout della release {0}:" , release.getName() + e.getMessage());
            return;
        }

        // Ora il codice è disponibile fisicamente nel file system → PMD potrà analizzarlo
        // Avvia PMD
        String realPath = git.getRepository().getWorkTree().getAbsolutePath();
        runPMDAnalysis(realPath, release.getName());

        // Analisi con JavaParser (come già facevi)
        RevTree tree = lastCommit.getTree();
        try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                if (path.endsWith(JAVA_EXTENSION) && !path.toLowerCase().contains("/test/")) {
                    parseJavaFile(treeWalk, path, release);
                }
            }
        }
    }




    private List<File> findAllJavaFiles(File root) {
        List<File> javaFiles = new ArrayList<>();
        Queue<File> dirs = new LinkedList<>();
        dirs.add(root);

        while (!dirs.isEmpty()) {
            File dir = dirs.poll();
            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.isDirectory()) {
                    dirs.add(file);
                } else if (file.getName().endsWith(JAVA_EXTENSION)
                        && !file.getAbsolutePath().toLowerCase().contains(File.separator + "test" + File.separator)) {
                    javaFiles.add(file);
                }
            }
        }

        return javaFiles;
    }

    private void runPMDAnalysis(String projectRootPath, String releaseName) {
        try {
            Logger.getAnonymousLogger().log(Level.INFO, "Eseguo PMD su release: {0}", releaseName);

            File root = new File(projectRootPath);
            List<File> javaFiles = findAllJavaFiles(root);

            if (javaFiles.isEmpty()) {
                Logger.getAnonymousLogger().log(Level.WARNING, "Nessun file .java trovato in: {0}", projectRootPath);
                return;
            }

            // PMD accetta un path separato da virgole
            String fileList = javaFiles.stream()
                    .map(File::getAbsolutePath)
                    .collect(Collectors.joining(","));

            File reportDir = new File(projectRootPath + "/pmd-reports");
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            File reportFile = new File(reportDir, "pmd-" + releaseName + ".xml");

            Logger.getAnonymousLogger().log(Level.INFO, "PMD analizzerà {0} file.", javaFiles.size());

            ProcessBuilder pb = new ProcessBuilder(
                    pmdPath,
                    "check",
                    "-d", fileList,
                    "-R", "category/java/bestpractices.xml",
                    "-f", "xml",
                    "-r", reportFile.getAbsolutePath()
            );

            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                Logger.getAnonymousLogger().log(Level.INFO, "PMD completato per release {0}.", releaseName);
            } else {
                Logger.getAnonymousLogger().log(Level.WARNING, "PMD ha terminato con codice {0} per release {1}.", new Object[]{exitCode, releaseName});
            }

        } catch (IOException | InterruptedException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante PMD per release {0}", releaseName + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Associa i commit a ciascuna release basandosi sugli intervalli di date.
     * La lista di release DEVE essere già ordinata per data.
     * @param releases Lista di release ordinate cronologicamente.
     */
    public void associateCommitsToReleases(List<Release> releases) throws GitAPIException, IOException {
        Logger.getAnonymousLogger().log(Level.INFO, "Data Extraction: Github extraction started");

        List<RevCommit> allCommits = new ArrayList<>();
        git.log().all().call().forEach(allCommits::add);
        allCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));

        LocalDateTime previousReleaseDate = null;

        for (Release release : releases) {
            LocalDateTime currentReleaseDate = release.getDate();

            for (RevCommit commit : allCommits) {
                LocalDateTime commitDate = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(commit.getCommitTime()), ZoneId.systemDefault());

                boolean isInFirstRelease = previousReleaseDate == null && !commitDate.isAfter(currentReleaseDate);
                boolean isInSubsequentRelease = previousReleaseDate != null
                        && commitDate.isAfter(previousReleaseDate)
                        && !commitDate.isAfter(currentReleaseDate);

                if (isInFirstRelease || isInSubsequentRelease) {
                    release.getCommitList().add(commit);
                }
            }

            previousReleaseDate = currentReleaseDate;
            Logger.getAnonymousLogger().log(Level.INFO,
                    "Release {0}: find {1} commit.",
                    new Object[]{release.getName(), release.getCommitList().size()});
        }
    }


    private boolean isSimpleGetterOrSetter(MethodDeclaration method) {
        // Un metodo senza corpo non è un getter/setter semplice (es. astratto)
        Optional<BlockStmt> body = method.getBody();
        if (body.isEmpty()) {
            return false;
        }

        // Un getter/setter semplice ha esattamente una istruzione nel corpo
        if (body.get().getStatements().size() != 1) {
            return false;
        }

        String methodName = method.getNameAsString();

        // Controllo per i GETTER (get... o is... per i booleani)
        if ((methodName.startsWith("get") || methodName.startsWith("is")) && method.getParameters().isEmpty()) {
            // L'unica istruzione deve essere un "return"
            return body.get().getStatements().get(0) instanceof ReturnStmt;
        }

        // Controllo per i SETTER (set...)
        if (methodName.startsWith("set") && method.getParameters().size() == 1) {
            // L'unica istruzione deve essere un'espressione
            if (!(body.get().getStatements().get(0) instanceof ExpressionStmt)) {
                return false;
            }
            // E quell'espressione deve essere un'assegnazione
            ExpressionStmt exprStmt = (ExpressionStmt) body.get().getStatements().get(0);
            return exprStmt.getExpression() instanceof AssignExpr;
        }

        return false;
    }

    /**
     * Controlla se un metodo è un boilerplate comune (toString, equals, hashCode).
     */
    private boolean isBoilerplateMethod(MethodDeclaration method) {
        String name = method.getNameAsString();
        // Controllo per toString()
        if ("toString".equals(name) && method.getParameters().isEmpty()) return true;
        // Controllo per hashCode()
        if ("hashCode".equals(name) && method.getParameters().isEmpty()) return true;
        // Controllo per equals(Object obj)
        return "equals".equals(name) && method.getParameters().size() == 1 &&
                method.getParameter(0).getType().asString().equals("Object");
    }

    /**
     * Controlla se un metodo è un main eseguibile.
     */
    private boolean isMainMethod(MethodDeclaration method) {
        if (!"main".equals(method.getNameAsString())) return false;
        if (!method.isPublic() || !method.isStatic()) return false;
        if (method.getParameters().size() != 1) return false;
        return method.getParameter(0).getType().asString().equals("String[]");
    }


    private void parseJavaFile(TreeWalk treeWalk, String path, Release release) throws IOException {
        ObjectId objectId = treeWalk.getObjectId(0);
        ObjectLoader loader = git.getRepository().open(objectId);

        JavaParser javaParser = new JavaParser();
        ParseResult<CompilationUnit> parseResult = javaParser.parse(loader.openStream());

        if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
            Logger.getAnonymousLogger().log(Level.WARNING, "Impossibile parsare il file: {0}", path);
            return;
        }

        CompilationUnit cu = parseResult.getResult().get();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
            String className = c.getFullyQualifiedName().orElse(c.getNameAsString());
            JavaClass javaClass = new JavaClass(className, path);

            javaClass.setLcom(calculateLCOM4(c));

            c.findAll(MethodDeclaration.class).forEach(m -> {
                if(m.getBody().isPresent() &&
                        !isSimpleGetterOrSetter(m) &&
                        !isBoilerplateMethod(m) &&
                        !isMainMethod(m))
                {

                    String methodName = m.getNameAsString();
                    String methodContent = m.toString();

                    int startLine = m.getBegin().map(p -> p.line).orElse(-1);
                    int endLine = m.getEnd().map(p -> p.line).orElse(-1);
                    // Creiamo il JavaMethod passando la release corrente
                    JavaMethod javaMethod = new JavaMethod(methodName, methodContent, release, startLine, endLine);

                    //LOC:
                    if(startLine != -1){
                        javaMethod.setLoc(endLine-startLine+1);
                    }

                    //Parameters count:
                    javaMethod.setParametersCount(m.getParameters().size());

                    //FanOut:
                    Set<String> calledMethods = new HashSet<>();
                    m.findAll(MethodCallExpr.class).forEach(call->calledMethods.add(call.getNameAsString()));
                    javaMethod.setFanOut(calledMethods.size());

                    //Cyclomatic Complexity:
                    javaMethod.setCyclomaticComplexity(calculateCyclomaticComplexity(m));

                    javaClass.addMethod(javaMethod);
                }

            });

            if (!javaClass.getMethods().isEmpty()) {
                release.getJavaClassList().add(javaClass);
            }
        });
    }

    private int calculateLCOM4(ClassOrInterfaceDeclaration c) {
        List<MethodDeclaration> methods = c.getMethods();
        List<FieldDeclaration> fields = c.getFields();

        // Se non ci sono metodi o campi, la coesione non è applicabile o è massima.
        if (methods.size() <= 1 || fields.isEmpty()) {
            return 1;
        }

        List<String> fieldNames = fields.stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .toList();

        Map<MethodDeclaration, Set<String>> methodFieldUsage = buildFieldUsageMap(methods, fieldNames);
        Map<MethodDeclaration, List<MethodDeclaration>> adjList = buildAdjacencyList(methods, methodFieldUsage);
        return countConnectedComponents(methods, adjList);
    }

    private Map<MethodDeclaration, Set<String>> buildFieldUsageMap(List<MethodDeclaration> methods, List<String> fieldNames) {
        Map<MethodDeclaration, Set<String>> usageMap = new HashMap<>();
        for (MethodDeclaration method : methods) {
            Set<String> usedFields = new HashSet<>();
            for (FieldAccessExpr fa : method.findAll(FieldAccessExpr.class)) {
                if (fieldNames.contains(fa.getNameAsString())) {
                    usedFields.add(fa.getNameAsString());
                }
            }
            usageMap.put(method, usedFields);
        }
        return usageMap;
    }

    private Map<MethodDeclaration, List<MethodDeclaration>> buildAdjacencyList(
            List<MethodDeclaration> methods,
            Map<MethodDeclaration, Set<String>> methodFieldUsage) {

        Map<MethodDeclaration, List<MethodDeclaration>> adjList = new HashMap<>();
        for (MethodDeclaration m : methods) {
            adjList.put(m, new ArrayList<>());
        }

        for (int i = 0; i < methods.size(); i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                MethodDeclaration m1 = methods.get(i);
                MethodDeclaration m2 = methods.get(j);
                if (!Collections.disjoint(methodFieldUsage.get(m1), methodFieldUsage.get(m2))) {
                    adjList.get(m1).add(m2);
                    adjList.get(m2).add(m1);
                }
            }
        }

        return adjList;
    }

    private int countConnectedComponents(List<MethodDeclaration> methods, Map<MethodDeclaration, List<MethodDeclaration>> adjList) {
        Set<MethodDeclaration> visited = new HashSet<>();
        int components = 0;

        for (MethodDeclaration method : methods) {
            if (visited.add(method)) { // add() returns false if already present
                dfs(method, adjList, visited);
                components++;
            }
        }

        return components;
    }

    // Helper DFS per il traversal del grafo
    private void dfs(MethodDeclaration node, Map<MethodDeclaration, List<MethodDeclaration>> adjList, Set<MethodDeclaration> visited) {
        visited.add(node);
        for (MethodDeclaration neighbor : adjList.get(node)) {
            if (!visited.contains(neighbor)) {
                dfs(neighbor, adjList, visited);
            }
        }
    }
    private int calculateCyclomaticComplexity(MethodDeclaration md) {
        // La complessità parte sempre da 1
        AtomicInteger complexity = new AtomicInteger(1);

        // Crea un'istanza del visitor
        CyclomaticComplexityVisitor visitor = new CyclomaticComplexityVisitor();

        // Fai partire la visita dall'inizio del metodo, passando il contatore
        visitor.visit(md, complexity);

        return complexity.get();
    }

    private static class CyclomaticComplexityVisitor extends VoidVisitorAdapter<AtomicInteger> {

        @Override
        public void visit(IfStmt n, AtomicInteger complexity) {
            super.visit(n, complexity);
            complexity.incrementAndGet();
        }

        @Override
        public void visit(ForStmt n, AtomicInteger complexity) {
            super.visit(n, complexity);
            complexity.incrementAndGet();
        }

        @Override
        public void visit(WhileStmt n, AtomicInteger complexity) {
            super.visit(n, complexity);
            complexity.incrementAndGet();
        }

        @Override
        public void visit(DoStmt n, AtomicInteger complexity) {
            super.visit(n, complexity);
            complexity.incrementAndGet();
        }

        @Override
        public void visit(SwitchEntry n, AtomicInteger complexity) {
            super.visit(n, complexity);
            // Incrementa solo per i case con codice, non per 'default' vuoto
            if (n.getStatements().isNonEmpty()) {
                complexity.incrementAndGet();
            }
        }

        @Override
        public void visit(ConditionalExpr n, AtomicInteger complexity) {
            super.visit(n, complexity);
            complexity.incrementAndGet(); // Per l'operatore ternario (cond ? a : b)
        }

        @Override
        public void visit(BinaryExpr n, AtomicInteger complexity) {
            super.visit(n, complexity);
            if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) {
                complexity.incrementAndGet();
            }
        }
    }
}