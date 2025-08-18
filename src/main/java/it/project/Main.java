package it.project;
import it.project.controllers.DatasetCreation;
import it.project.controllers.PredictionPipeline;
import it.project.controllers.WekaClassifier;
import it.project.utils.SpearmanCorrelation;

import java.io.*;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    public static void main(String[] args) throws Exception {
        Properties prop = new Properties();

        try (InputStream input = new FileInputStream("src/main/resources/configuration.properties")) {
            prop.load(input);
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.INFO, String.format("File not found, %s", e));
        }

        String projectName = prop.getProperty("PROJECT_NAME");
        String skipExtractionProp = prop.getProperty("SKIP_EXTRACTION");
        String pmdPath = prop.getProperty("PMD_PATH");
        String outputPath = prop.getProperty("OUTPUT_PATH");

        boolean skipExtraction = skipExtractionProp != null && skipExtractionProp.equalsIgnoreCase("true");

        if (!skipExtraction) {
            try {
                DatasetCreation.dataExtraction(projectName, pmdPath);
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.INFO, String.format("Error during program execution flow %s", e));
            }
        }
        new SpearmanCorrelation(projectName).run();
        WekaClassifier wekaClassifier = new WekaClassifier(projectName);
        wekaClassifier.fetchWekaAnalysis();
        String path = outputPath + projectName.toLowerCase() + "/";
        if(Objects.equals(projectName, "BOOKKEEPER")) {
            path += "training/ARFF/BOOKKEEPER_training_iter_4.arff";
        }else{
            path += outputPath + "training/ARFF/OPENJPA_training_iter_4.arff";
        }




    }
}
