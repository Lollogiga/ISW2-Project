package it.project.entities;

public class Smell {
    private final int beginLine;
    private final int endLine;

    public Smell(int beginLine, int endLine) {
        this.beginLine = beginLine;
        this.endLine = endLine;
    }

    public int getBeginLine() {
        return beginLine;
    }

    public int getEndLine() {
        return endLine;
    }
}
