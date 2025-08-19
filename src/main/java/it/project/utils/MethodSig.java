// utils/MethodSig.java
package it.project.utils;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;


public final class MethodSig {
    private MethodSig(){}

    /** Firma testuale tipo "(int, java.lang.String)" usando i tipi come compaiono nel sorgente. */
    public static String fromAst(MethodDeclaration md) {
        String params = md.getParameters().stream()
                .map(p -> p.getType().asString().trim())   // niente spazi sporchi
                .collect(java.util.stream.Collectors.joining(", "));
        return "(" + params + ")";
    }


    /** Firma risolta tipo "(int, java.lang.String)" usando i FQN se disponibili via SymbolSolver. */
    public static String fromResolved(ResolvedMethodDeclaration rd) {
        // rd.getSignature() restituisce "name(int,java.lang.String)" → estraggo solo la parte tra parentesi
        String sig = rd.getSignature();
        int i = sig.indexOf('(');
        return (i >= 0) ? sig.substring(i) : "()";
    }

    /** Costruisce la chiave canonica per tutta la pipeline. */
    public static String key(String classRelPath, String methodName, String signature) {
        return toUnix(classRelPath) + "::" + methodName + signature; // es: "src/a/Foo.java::bar(int, String)"
    }

    private static String toUnix(String rel) {
        return rel.replace('\\', '/');
    }

    public static String fromAst(ConstructorDeclaration cd) {
        String params = cd.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(java.util.stream.Collectors.joining(", "));
        return "(" + params + ")";
    }
}
