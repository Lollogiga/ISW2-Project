package it.project.entities;


public class JavaMethod {
    private final String name;
    private final String content;
    private final Release release; // Link alla release di appartenenza
    private final int startLine;
    private final int endLine;

    // Features
    private int loc;
    private int parametersCount;
    private int fanOut;
    private int cyclomaticComplexity;
    private int churn;
    private int locAdded;
    private double newcomerRisk;
    private int nAuth;
    private double weekendCommit;
    private int nSmells;
    private int fanIn;


    private boolean isBuggy;

    public JavaMethod(String name, String content, Release release, int startLine, int endLine) {
        this.name = name;
        this.content = content;
        this.release = release;
        this.startLine = startLine;
        this.endLine = endLine;

        // Inizializziamo i valori di default
        this.isBuggy = false; // Un metodo è "non buggy" finché non si prova il contrario
        this.loc = 0;
        this.parametersCount = 0;
        this.fanOut = 0;
        this.cyclomaticComplexity = 0;
        this.churn = 0;
        this.locAdded = 0;
        this.newcomerRisk = 0.0;
        this.nAuth = 0;
        this.weekendCommit = 0.0;
        this.nSmells = 0;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public Release getRelease() {
        return release;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public int getLoc() {
        return loc;
    }

    public void setLoc(int loc) {
        this.loc = loc;
    }

    public int getParametersCount() {
        return parametersCount;
    }

    public void setParametersCount(int parametersCount) {
        this.parametersCount = parametersCount;
    }

    public int getFanOut() {
        return fanOut;
    }

    public void setFanOut(int fanOut) {
        this.fanOut = fanOut;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public void setCyclomaticComplexity(int cyclomaticComplexity) {
        this.cyclomaticComplexity = cyclomaticComplexity;
    }

    public int getChurn() {
        return churn;
    }

    public void setChurn(int churn) {
        this.churn = churn;
    }

    public int getLocAdded() {
        return locAdded;
    }

    public void setLocAdded(int locAdded) {
        this.locAdded = locAdded;
    }

    public double getNewcomerRisk() {
        return newcomerRisk;
    }

    public void setNewcomerRisk(double newcomerRisk) {
        this.newcomerRisk = newcomerRisk;
    }

    public int getnAuth() {
        return nAuth;
    }

    public void setnAuth(int nAuth) {
        this.nAuth = nAuth;
    }

    public int getWeekendCommit() {
        return weekendCommit>0 ? 1 : 0;
    }

    public void setWeekendCommit(double weekendCommit) {
        this.weekendCommit = weekendCommit;
    }

    public String isBuggy() {
        if (isBuggy) {
            return "Yes";
        }else{
            return "No";
        }
    }

    public int getnSmells() {
        return nSmells;
    }

    public void setnSmells(int nSmells) {
        this.nSmells = nSmells;
    }

    public int getFanIn() { return fanIn; }
    public void setFanIn(int fanIn) { this.fanIn = fanIn; }

    public void setBuggy(boolean b) {
        this.isBuggy = b;
    }

}