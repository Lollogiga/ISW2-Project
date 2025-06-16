package it.project.entities;


public class JavaMethod {
    private final String name;
    private final String content;
    private final Release release; // Link alla release di appartenenza

    // Features
    private int loc;
    private int cyclomaticComplexity;
    private int churn;
    private int locAdded;
    private double newcomerRisk;
    private int n_auth;
    private double weekendCommitRatio;
    private boolean isBuggy;

    public JavaMethod(String name, String content, Release release) {
        this.name = name;
        this.content = content;
        this.release = release;

        // Inizializziamo i valori di default
        this.isBuggy = false; // Un metodo è "non buggy" finché non si prova il contrario
        this.loc = 0;
        this.cyclomaticComplexity = 0;
        this.churn = 0;
        this.locAdded = 0;
        this.newcomerRisk = 0.0;
        this.n_auth = 0;
        this.weekendCommitRatio = 0.0;
    }

    // --- GETTERS ---
    public String getName() { return name; }
    public String getContent() { return content; }
    public Release getRelease() { return release; }
    public int getLoc() { return loc; }
    public int getCyclomaticComplexity() { return cyclomaticComplexity; }
    public int getChurn() { return churn; }
    public int getLocAdded() { return locAdded; }
    public double getNewcomerRisk() { return newcomerRisk; }
    public int getN_auth() { return n_auth; }
    public double getWeekendCommitRatio() { return weekendCommitRatio; }
    public boolean isBuggy() { return isBuggy; }

    // --- SETTERS ---
    public void setLoc(int loc) { this.loc = loc; }
    public void setCyclomaticComplexity(int cyclomaticComplexity) { this.cyclomaticComplexity = cyclomaticComplexity; }
    public void setChurn(int churn) { this.churn = churn; }
    public void setLocAdded(int locAdded) { this.locAdded = locAdded; }
    public void setNewcomerRisk(double newcomerRisk) { this.newcomerRisk = newcomerRisk; }
    public void setN_auth(int n_auth) { this.n_auth = n_auth; }
    public void setWeekendCommitRatio(double weekendCommitRatio) { this.weekendCommitRatio = weekendCommitRatio; }
    public void setBuggy(boolean buggy) { isBuggy = buggy; }
}