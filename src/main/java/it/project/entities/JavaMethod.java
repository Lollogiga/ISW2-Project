package it.project.entities;

public class JavaMethod {
    private final String name;
    private final String className;
    private final int releaseIndex;

    // Metriche calcolate
    private int loc;
    private int cyclomaticComplexity;
    private int lcom; // Lack of Cohesion in Methods (spesso calcolata a livello di classe)
    private int churn;
    private int locAdded;
    private double newcomerRisk;
    private int auth; // Numero di autori
    private double weekendCommitRatio;
    private boolean isBuggy;

    public JavaMethod(String name, String className, int releaseIndex) {
        this.name = name;
        this.className = className;
        this.releaseIndex = releaseIndex;
        this.isBuggy = false; // Default a non buggy
    }

    public String getName() {
        return name;
    }

    public String getClassName() {
        return className;
    }

    public int getReleaseIndex() {
        return releaseIndex;
    }

    public int getLoc() {
        return loc;
    }

    public void setLoc(int loc) {
        this.loc = loc;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public void setCyclomaticComplexity(int cyclomaticComplexity) {
        this.cyclomaticComplexity = cyclomaticComplexity;
    }

    public int getLcom() {
        return lcom;
    }

    public void setLcom(int lcom) {
        this.lcom = lcom;
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

    public int getAuth() {
        return auth;
    }

    public void setAuth(int auth) {
        this.auth = auth;
    }

    public double getWeekendCommitRatio() {
        return weekendCommitRatio;
    }

    public void setWeekendCommitRatio(double weekendCommitRatio) {
        this.weekendCommitRatio = weekendCommitRatio;
    }

    public String isBuggy() {
        if(isBuggy)
            return "Yes";
        else
            return "No";
    }


}