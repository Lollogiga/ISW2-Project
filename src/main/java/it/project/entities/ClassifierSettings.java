package it.project.entities;

public class ClassifierSettings {

    private String featureSelection;

    public ClassifierSettings() {

        this.featureSelection = "";
    }


    public void setFeatureSelection(String featureSelection) {
        this.featureSelection = featureSelection;
    }


    public String getFeatureSelection() {
        return featureSelection;
    }

    public void reset() {
        featureSelection = "";
    }
}
