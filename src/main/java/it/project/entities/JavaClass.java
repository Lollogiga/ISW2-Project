package it.project.entities;

import java.util.ArrayList;
import java.util.List;

public class JavaClass {
    private final String name;
    private final String path;
    private final List<JavaMethod> methods;
    private int lcom; // <-- LCOM va qui!

    public JavaClass(String name, String path) {
        this.name = name;
        this.path = path;
        this.methods = new ArrayList<>();
        this.lcom = 0; // Valore di default
    }

    public void addMethod(JavaMethod method) {
        this.methods.add(method);
    }

    // --- Getters e Setters ---
    public String getName() { return name; }
    public String getPath() { return path; }
    public List<JavaMethod> getMethods() { return methods; }
    public int getLcom() { return lcom; }
    public void setLcom(int lcom) { this.lcom = lcom; }
}