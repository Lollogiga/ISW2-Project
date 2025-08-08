package it.project;
import it.project.controllers.Executor;

import java.io.*;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    public static void main(String[] args) {
        Properties prop = new Properties();

        try (InputStream input = new FileInputStream("src/main/resources/configuration.properties")) {
            prop.load(input);
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.INFO, String.format("File not found, %s", e));
        }

        String projectName = prop.getProperty("PROJECT_NAME");
        String skipExtractionProp = prop.getProperty("SKIP_EXTRACTION");
        String pmdPath = prop.getProperty("PMD_PATH");

        boolean skipExtraction = skipExtractionProp != null && skipExtractionProp.equalsIgnoreCase("true");

        if (!skipExtraction) {
            try {
                Executor.dataExtraction(projectName, pmdPath);
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.INFO, String.format("Error during program execution flow %s", e));
            }
        } else {
            Logger.getAnonymousLogger().log(Level.INFO, "Skipping feature extraction: using existing data.");
        }
    }
}
