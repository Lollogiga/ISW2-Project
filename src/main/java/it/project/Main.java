package it.project;
import it.project.controllers.DatasetCreation;
import it.project.controllers.SmellImpactAnalyzer;
import it.project.controllers.WekaClassifier;
import it.project.utils.FileCSVGenerator;
import it.project.utils.SpearmanCorrelation;
import weka.classifiers.Classifier;

import java.io.*;
import java.nio.file.Paths;
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
        String skipEvaluationProp = prop.getProperty("SKIP_EVALUATION");
        String pmdPath = prop.getProperty("PMD_PATH");
        String outputPath = prop.getProperty("OUTPUT_PATH");

        boolean skipExtraction = skipExtractionProp != null && skipExtractionProp.equalsIgnoreCase("true");

        if (!skipExtraction) {
            try {
                DatasetCreation.dataExtraction(projectName, pmdPath);
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.INFO, String.format("Error during program execution flow %s", e));
            }
        } else if (!skipEvaluationProp.trim().equalsIgnoreCase("true")) {
            new SpearmanCorrelation(projectName).run();
            FileCSVGenerator csvGenerator = new FileCSVGenerator(outputPath, projectName);
            WekaClassifier wekaClassifier = new WekaClassifier(projectName, csvGenerator);
            wekaClassifier.fetchWekaAnalysis();
            String path = Paths.get(outputPath)
                    .resolve(projectName.toLowerCase())
                    + File.separator;

            if(Objects.equals(projectName, "BOOKKEEPER")) {
                path += "training/ARFF/BOOKKEEPER_training_iter_4.arff";
            }else{
                path += outputPath + "training/ARFF/OPENJPA_training_iter_4.arff";
            }
            wekaClassifier.runAndSaveFeatureCorrelationRanking(path, 20);
        }
        //AFeatures = LOC/Cyclomatic Complexity
        //BClassifier = random Forest
        //AFMethod = bookkeeper-server/src/main/java/org/apache/bookkeeper/bookie/LedgerCacheImpl.java::flushLedger

        Classifier clf = new weka.classifiers.trees.RandomForest(); // oppure il tuo BClassifier
        SmellImpactAnalyzer analyzer = new SmellImpactAnalyzer();
        analyzer.run(
                "src/main/resources/bookkeeper/otherFiles/BOOKKEEPER_fullDataset.arff",
                clf,
                "nSmell"
        );



    }


}
