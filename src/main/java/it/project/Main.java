package it.project;
import it.project.controllers.DatasetCreation;
import it.project.controllers.PredictionPipeline;
import it.project.controllers.WekaClassifier;
import it.project.utils.SpearmanCorrelation;

import java.io.*;
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
        String outputDir = prop.getProperty("OUTPUT_DIR");
        String pmdPath = prop.getProperty("PMD_PATH");

        boolean skipExtraction = skipExtractionProp != null && skipExtractionProp.equalsIgnoreCase("true");

        if (!skipExtraction) {
            try {
                DatasetCreation.dataExtraction(projectName, pmdPath);
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.INFO, String.format("Error during program execution flow %s", e));
            }
        }
        //new SpearmanCorrelation(outputDir, projectName).run();
        new WekaClassifier(projectName).fetchWekaAnalysis();

    }
}
