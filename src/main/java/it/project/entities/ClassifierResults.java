package it.project.entities;

public class ClassifierResults {
    private final String projName;
    private final int index;
    private final String classifierName;

    private final double percTrainingInstances;
    private double precision;
    private double recall;
    private double kappa;
    private double truePositives;
    private double falsePositives;
    private double falseNegatives;
    private double trueNegatives;
    private double auc;
    private double fMeasure;

    private Double pofB20;   // può essere null/NaN se non calcolabile
    private Double npofB20;  // idem

    private final String selection;

    public ClassifierResults(String projName, int index, String classifierName, ClassifierSettings settings, int trainInstances, int testInstances) {
        this.projName = projName;
        this.index = index;
        this.classifierName = classifierName;
        this.selection = settings.getFeatureSelection();

        this.percTrainingInstances = 100.0 * trainInstances /(trainInstances + testInstances);
    }

    public void setAuc(double auc) {
        this.auc = auc;
    }


    public void setFMeasure(double fMeasure) {
        this.fMeasure = fMeasure;
    }

    public void setKappa(double kappa) {
        this.kappa = kappa;
    }

    public void setPreci(double precision) {
        this.precision = precision;
    }

    public void setRec(double recall) {
        this.recall = recall;
    }

    public void setTruePositives(double truePositives) {
        this.truePositives = truePositives;
    }

    public void setFalsePositives(double falsePositives) {
        this.falsePositives = falsePositives;
    }

    public void setFalseNegatives(double falseNegatives) {
        this.falseNegatives = falseNegatives;
    }

    public void setTrueNegatives(double trueNegatives) {
        this.trueNegatives = trueNegatives;
    }



    public String isSelection() {
        if (selection.isEmpty())
            return "no";
        return selection;
    }

    public double getPreci() {
        return precision;
    }

    public double getRec() {
        return recall;
    }

    public double getTruePositives() {
        return truePositives;
    }

    public double getFalsePositives() {
        return falsePositives;
    }

    public double getFalseNegatives() {
        return falseNegatives;
    }

    public double getTrueNegatives() {
        return trueNegatives;
    }

    public String getProjName() {
        return projName;
    }

    public int getIndex() {
        return index;
    }

    public String getClassifierName() {
        return classifierName;
    }

    public double getAuc() {
        return auc;
    }

    public double getFMeasure() {
        return fMeasure;
    }

    public double getKappa() {
        return kappa;
    }

    public Double getPofB20() { return pofB20; }
    public void setPofB20(Double pofB20) { this.pofB20 = pofB20; }

    public Double getNpofB20() { return npofB20; }
    public void setNpofB20(Double npofB20) { this.npofB20 = npofB20; }

    public double getPercTrainingInstances() {
        return percTrainingInstances;
    }
}