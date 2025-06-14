package it.project.entities;

import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.List;

public class JavaClass {
    private String name;
    private String content;
    private List<RevCommit> commitList;
    private boolean buggy;
    private Release release;
    private double prediction;

    /* metrics */
    private int LOC;
    private int NumAuth;
    private int ParametersCount;
    private int FanOut;
    private int CyclomaticComplexity;
    private int Churn;
    private int LOCAdded;
    private double LCOM;
    private double NewcomerRisk;
    private double WeekendCommitRatio;

    public JavaClass(String name, String content, Release release) {
        this.name = name;
        this.content = content;
        this.commitList = new ArrayList<>();
        this.release = release;
        this.buggy = false;

        this.LOC = 0;
        this.NumAuth = 0;
        this.ParametersCount = 0;
        this.FanOut = 0;
        this.CyclomaticComplexity = 0;
        this.Churn = 0;
        this.LOCAdded = 0;
        this.LCOM = 0;
        this.NewcomerRisk = 0;
        this.WeekendCommitRatio = 0;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<RevCommit> getCommitList() {
        return commitList;
    }

    public void setCommitList(List<RevCommit> commitList) {
        this.commitList = commitList;
    }

    public String getBuggy() {
        if (buggy)
            return "yes";
        else
            return "no";
    }


    public Release getRelease() {
        return release;
    }

    public void setRelease(Release release) {
        this.release = release;
    }

    public double getPrediction() {
        return prediction;
    }

    public void setPrediction(double prediction) {
        this.prediction = prediction;
    }

    public int getLOC() {
        return LOC;
    }

    public void setLOC(int LOC) {
        this.LOC = LOC;
    }

    public int getNumAuth() {
        return NumAuth;
    }

    public void setNumAuth(int numAuth) {
        NumAuth = numAuth;
    }

    public int getParametersCount() {
        return ParametersCount;
    }

    public void setParametersCount(int parametersCount) {
        ParametersCount = parametersCount;
    }

    public int getFanOut() {
        return FanOut;
    }

    public void setFanOut(int fanOut) {
        FanOut = fanOut;
    }

    public int getCyclomaticComplexity() {
        return CyclomaticComplexity;
    }

    public void setCyclomaticComplexity(int cyclomaticComplexity) {
        CyclomaticComplexity = cyclomaticComplexity;
    }

    public int getChurn() {
        return Churn;
    }

    public void setChurn(int churn) {
        Churn = churn;
    }

    public int getLOCAdded() {
        return LOCAdded;
    }

    public void setLOCAdded(int LOCAdded) {
        this.LOCAdded = LOCAdded;
    }

    public double getLCOM() {
        return LCOM;
    }

    public void setLCOM(double LCOM) {
        this.LCOM = LCOM;
    }

    public double getNewcomerRisk() {
        return NewcomerRisk;
    }

    public void setNewcomerRisk(double newcomerRisk) {
        NewcomerRisk = newcomerRisk;
    }

    public double getWeekendCommitRatio() {
        return WeekendCommitRatio;
    }

    public void setWeekendCommitRatio(double weekendCommitRatio) {
        WeekendCommitRatio = weekendCommitRatio;
    }
}
