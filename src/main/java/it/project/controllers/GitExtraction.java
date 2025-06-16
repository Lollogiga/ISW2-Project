package it.project.controllers;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import java.util.stream.Collectors;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.expr.ThisExpr;
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

public class GitExtraction {

    private final Git git;

    public GitExtraction() throws IOException {
        this.git = RepoFactory.getGit();
    }

    /**
     * Associa i commit a ciascuna release basandosi sugli intervalli di date.
     * La lista di release DEVE essere già ordinata per data.
     * @param releases Lista di release ordinate cronologicamente.
     */
    public void associateCommitsToReleases(List<Release> releases) throws GitAPIException, IOException {
        Logger.getAnonymousLogger().log(Level.INFO, "Data Extraction: Github extraction started");

        // Recupera tutti i commit dal repository una sola volta per efficienza
        List<RevCommit> allCommits = new ArrayList<>();
        git.log().all().call().forEach(allCommits::add);
        // Ordina i commit per data, dal più vecchio al più recente
        allCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));

        LocalDateTime previousReleaseDate = null; // Per la prima release, non c'è una data precedente

        for (Release release : releases) {
            final LocalDateTime currentReleaseDate = release.getDate();

            for (RevCommit commit : allCommits) {
                // La data del commit in JGit è in secondi (epoch), la convertiamo in LocalDateTime
                LocalDateTime commitDate = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        ZoneId.systemDefault()
                );

                // Caso della prima release: prendi tutti i commit fino alla data della release
                if (previousReleaseDate == null) {
                    if (!commitDate.isAfter(currentReleaseDate)) {
                        release.getCommitList().add(commit);
                    }
                } else {
                    // Release successive: prendi i commit nell'intervallo (previousReleaseDate, currentReleaseDate]
                    if (commitDate.isAfter(previousReleaseDate) && !commitDate.isAfter(currentReleaseDate)) {
                        release.getCommitList().add(commit);
                    }
                }
            }
            // Aggiorna la data precedente per la prossima iterazione
            previousReleaseDate = currentReleaseDate;
            Logger.getAnonymousLogger().log(Level.INFO, "Release {0}: find {1} commit.", new Object[]{release.getName(), release.getCommitList().size()});
        }
    }

    public void analyzeReleaseCode(Release release) throws IOException {
        if (release.getCommitList().isEmpty()) {
            Logger.getAnonymousLogger().log(Level.WARNING, " Release {0} hasn't commit, code not be analyze.", release.getName());
            return;
        }

        // Analizziamo lo stato del codice all'ultimo commit della release
        RevCommit lastCommit = release.getCommitList().get(release.getCommitList().size() - 1);
        RevTree tree = lastCommit.getTree();

        Logger.getAnonymousLogger().log(Level.INFO, "Analisi codice per release {0} (commit: {1})", new Object[]{release.getName(), lastCommit.getId().getName()});

        // Usiamo un TreeWalk per navigare nell'albero dei file del commit
        try (TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);

            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                // Considera solo i file .java, ignorando i test
                if (path.endsWith(".java") && !path.toLowerCase().contains("/test/")) {
                    parseJavaFile(treeWalk, path, release);
                }
            }
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

    /**
     * Controlla se un costruttore è "semplice", cioè assegna solo parametri a campi.
     */
    private boolean isSimpleConstructor(ConstructorDeclaration constructor) {
        if (constructor.getBody().getStatements().isEmpty()) {
            return true; // Costruttore vuoto, es. default
        }
        for (Statement stmt : constructor.getBody().getStatements()) {
            // Un costruttore semplice contiene solo assegnazioni del tipo "this.field = param;"
            if (!(stmt instanceof ExpressionStmt)) return false;
            ExpressionStmt exprStmt = (ExpressionStmt) stmt;
            if (!(exprStmt.getExpression() instanceof AssignExpr)) return false;

            AssignExpr assignExpr = (AssignExpr) exprStmt.getExpression();
            if (!(assignExpr.getTarget() instanceof FieldAccessExpr)) return false;

            FieldAccessExpr fieldAccess = (FieldAccessExpr) assignExpr.getTarget();
            if (!(fieldAccess.getScope() instanceof ThisExpr)) return false;
        }
        return true;
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

        // Mappa per tenere traccia di quali campi sono usati da quale metodo
        Map<MethodDeclaration, Set<String>> methodFieldUsage = new HashMap<>();
        List<String> fieldNames = fields.stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .collect(Collectors.toList());

        for (MethodDeclaration method : methods) {
            Set<String> usedFields = new HashSet<>();
            // Trova tutti gli accessi ai campi all'interno del metodo
            method.findAll(FieldAccessExpr.class).forEach(fa -> {
                if (fieldNames.contains(fa.getNameAsString())) {
                    usedFields.add(fa.getNameAsString());
                }
            });
            methodFieldUsage.put(method, usedFields);
        }

        // Costruisci il grafo di adiacenza
        Map<MethodDeclaration, List<MethodDeclaration>> adjList = new HashMap<>();
        for (MethodDeclaration m : methods) adjList.put(m, new ArrayList<>());

        for (int i = 0; i < methods.size(); i++) {
            for (int j = i + 1; j < methods.size(); j++) {
                MethodDeclaration m1 = methods.get(i);
                MethodDeclaration m2 = methods.get(j);

                Set<String> fields1 = methodFieldUsage.get(m1);
                Set<String> fields2 = methodFieldUsage.get(m2);

                // Se i metodi condividono almeno un campo, aggiungi un arco
                if (!Collections.disjoint(fields1, fields2)) {
                    adjList.get(m1).add(m2);
                    adjList.get(m2).add(m1);
                }
            }
        }

        // Calcola le componenti connesse usando DFS (Depth-First Search)
        Set<MethodDeclaration> visited = new HashSet<>();
        int components = 0;
        for (MethodDeclaration method : methods) {
            if (!visited.contains(method)) {
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
            // Incrementa per ogni operatore logico && o ||
            if (n.getOperator() == BinaryExpr.Operator.AND || n.getOperator() == BinaryExpr.Operator.OR) {
                complexity.incrementAndGet();
            }
        }
    }
}