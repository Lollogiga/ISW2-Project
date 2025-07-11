package it.project.controllers;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.SwitchStmt;
import it.project.entities.JavaMethod;

import java.io.StringReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CodeSmellAnalyzer {

    private final JavaParser javaParser;

    public CodeSmellAnalyzer() {
        // Crea un'istanza di JavaParser una sola volta per efficienza
        this.javaParser = new JavaParser();
    }

    /**
     * Metodo principale che orchestra l'analisi e conta il numero totale di smells.
     * @param javaMethod L'oggetto metodo da analizzare.
     * @return Il numero totale di tipi di smell trovati (ogni tipo conta come 1).
     */
    public int countSmells(JavaMethod javaMethod) {
        // Parsa il codice del metodo in un AST (Abstract Syntax Tree)
        MethodDeclaration ast = parseMethodContent(javaMethod.getContent());
        if (ast == null) {
            return 0; // Se il parsing fallisce, non ci sono smell da trovare
        }

        int smellCount = 0;
        smellCount += detectMagicNumbers(ast);
        smellCount += detectSwitchWithoutDefault(ast);
        smellCount += detectGenericExceptionCatch(ast);
        smellCount += detectEmptyCatchBlock(ast);
        smellCount += detectSystemPrint(ast);

        return smellCount;
    }

    /**
     * Helper per parsare una stringa contenente un metodo in un oggetto MethodDeclaration.
     */
    private MethodDeclaration parseMethodContent(String methodContent) {
        String classWrapper = "class Tmp { \n" + methodContent + "\n }";
        ParseResult<CompilationUnit> result = javaParser.parse(new StringReader(classWrapper));

        if (result.isSuccessful() && result.getResult().isPresent()) {
            return result.getResult().get().findFirst(MethodDeclaration.class).orElse(null);
        } else {
            // Logga l'errore di parsing se necessario
            Logger.getAnonymousLogger().log(Level.FINE, "Could not parse method content.", result.getProblems());
            return null;
        }
    }

    // --- RILEVATORI DI SMELL SPECIFICI ---

    private int detectMagicNumbers(MethodDeclaration ast) {
        Set<String> allowedNumbers = new HashSet<>(Arrays.asList("0", "1", "-1"));
        for (IntegerLiteralExpr number : ast.findAll(IntegerLiteralExpr.class)) {
            if (!allowedNumbers.contains(number.getValue())) {
                return 1; // Trovato un Magic Number, conta come 1 smell e basta
            }
        }
        return 0;
    }

    private int detectSwitchWithoutDefault(MethodDeclaration ast) {
        for (SwitchStmt switchStmt : ast.findAll(SwitchStmt.class)) {
            boolean hasDefault = switchStmt.getEntries().stream().anyMatch(e -> e.getLabels().isEmpty()); // isDefault non c'è, si usa questa euristica
            if (!hasDefault) {
                return 1; // Trovato uno switch senza default
            }
        }
        return 0;
    }

    private int detectGenericExceptionCatch(MethodDeclaration ast) {
        Set<String> genericExceptions = new HashSet<>(Arrays.asList("Exception", "Throwable", "RuntimeException"));
        for (CatchClause catchClause : ast.findAll(CatchClause.class)) {
            String exceptionType = catchClause.getParameter().getType().asString();
            if (genericExceptions.contains(exceptionType)) {
                return 1; // Trovato un catch generico
            }
        }
        return 0;
    }

    private int detectEmptyCatchBlock(MethodDeclaration ast) {
        for (CatchClause catchClause : ast.findAll(CatchClause.class)) {
            // Un blocco è vuoto se non ha istruzioni o ha solo commenti.
            // .isEmpty() controlla se ci sono statements.
            if (catchClause.getBody().getStatements().isEmpty()) {
                return 1; // Trovato un blocco catch vuoto
            }
        }
        return 0;
    }

    private int detectSystemPrint(MethodDeclaration ast) {
        for (MethodCallExpr call : ast.findAll(MethodCallExpr.class)) {
            // Controlla se la chiamata è del tipo "System.out.println" o "System.err.println"
            if (call.getScope().isPresent() && call.getScope().get().toString().equals("System.out") ||
                    call.getScope().isPresent() && call.getScope().get().toString().equals("System.err")) {
                if (call.getNameAsString().startsWith("print")) {
                    return 1; // Trovata una chiamata a System.out/err.print...
                }
            }
        }
        return 0;
    }
}