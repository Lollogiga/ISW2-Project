package it.project.entities;

import it.project.entities.JavaMethod;

import java.util.List;

public class JavaClass {
    private final String name;
    private final String path; // Percorso del file
    private String content; // Contenuto del file in una specifica release
    private List<JavaMethod> methods;

    public JavaClass(String name, String path) {
        this.name = name;
        this.path = path;
    }


    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    // Getters e Setters
    public List<JavaMethod> getMethods() {
        return methods;
    }

    public void setMethods(List<JavaMethod> methods) {
        this.methods = methods;
    }

}