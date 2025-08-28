package it.project;

import it.project.controllers.DatasetCreation;
import it.project.controllers.SmellImpactAnalyzer;
import it.project.controllers.WekaClassifier;
import it.project.utils.FileCSVGenerator;
import it.project.utils.SpearmanCorrelation;

import weka.attributeSelection.BestFirst;
import weka.classifiers.Classifier;
import weka.classifiers.meta.AttributeSelectedClassifier;
import weka.attributeSelection.CfsSubsetEval;
import weka.classifiers.trees.RandomForest;
import weka.core.Instances;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    // --------------- CONFIG KEYS ---------------
    private static final String KEY_PROJECT_NAME   = "PROJECT_NAME";
    private static final String KEY_SKIP_EXTRACTION = "SKIP_EXTRACTION";
    private static final String KEY_SKIP_EVALUATION = "SKIP_EVALUATION";
    private static final String KEY_SKIP_SMELL_IMPACT = "SKIP_SMELL_IMPACT";
    private static final String KEY_PMD_PATH       = "PMD_PATH";
    private static final String KEY_OUTPUT_PATH    = "OUTPUT_PATH";
    private static final String N_SMELL    = "nSmell";

    // --------------- ENTRY POINT ---------------
    public static void main(String[] args) {
        Properties cfg = loadProperties("src/main/resources/configuration.properties");

        String projectName      = cfg.getProperty(KEY_PROJECT_NAME, "").trim();
        String pmdPath          = cfg.getProperty(KEY_PMD_PATH, "").trim();
        String outputPath       = cfg.getProperty(KEY_OUTPUT_PATH, "src/main/resources").trim();
        boolean skipExtraction  = parseBool(cfg.getProperty(KEY_SKIP_EXTRACTION));
        boolean skipEvaluation  = parseBool(cfg.getProperty(KEY_SKIP_EVALUATION));
        boolean skipSmellImpact = parseBool(cfg.getProperty(KEY_SKIP_SMELL_IMPACT));

        if (projectName.isEmpty()) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "PROJECT_NAME mancante in configuration.properties");
            return;
        }

        // 1) Estrazione dataset (Jira/Git/PMD → CSV/ARFF)
        if (!skipExtraction) {
            runExtraction(projectName, pmdPath);
        }

        // 2) Valutazione (correlazioni + classificatori Weka + ranking feature)
        if (!skipEvaluation) {
            runEvaluation(projectName, outputPath);
        }

        // 3) Analisi impatto smell (dataset full + classifier per progetto)
        if(!skipSmellImpact) {
            runSmellImpact(projectName, outputPath);
        }
    }

    // --------------- PHASE 1: EXTRACTION ---------------
    private static void runExtraction(String projectName, String pmdPath) {
        try {
            DatasetCreation.dataExtraction(projectName, pmdPath);
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante l'estrazione dei dati", e);
        }
    }

    // --------------- PHASE 2: EVALUATION ---------------
    private static void runEvaluation(String projectName, String outputPath) {
        try {
            new SpearmanCorrelation(projectName).run();

            FileCSVGenerator csvGen = new FileCSVGenerator(outputPath, projectName);
            WekaClassifier weka = new WekaClassifier(projectName, csvGen);
            weka.fetchWekaAnalysis();

            // path ARFF per ranking feature (come nel codice originale)
            String base = Paths.get(outputPath).resolve(projectName.toLowerCase())+ File.separator;
            String pathForRanking;
            if (Objects.equals(projectName, "BOOKKEEPER")) {
                pathForRanking = base + "otherFiles/BOOKKEEPER_fullDataset.arff";
            } else {
                pathForRanking = base + "otherFiles/OPENJPA_fullDataset.arff";
            }
            weka.runAndSaveFeatureCorrelationRanking(pathForRanking, 20);
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante la fase di valutazione", e);
        }
    }

    // --------------- PHASE 3: SMELL IMPACT ---------------
    private static void runSmellImpact(String projectName, String outputPath) {
        SmellImpactAnalyzer analyzer = new SmellImpactAnalyzer();

        try {
            if ("BOOKKEEPER".equals(projectName)) {
                // AFeatures = LOC/Cyclomatic Complexity
                // BClassifier = RandomForest (come in origine)
                String arffPath = "src/main/resources/bookkeeper/otherFiles/BOOKKEEPER_fullDataset.arff";
                Classifier clf = new weka.classifiers.trees.RandomForest();

                analyzer.run(
                        arffPath,
                        clf,
                        N_SMELL,
                        new FileCSVGenerator(outputPath, projectName),
                        10
                );

            } else if ("OPENJPA".equals(projectName)) {
                // AFeatures = fan-out
                // BClassifier = Naive Bayes con GreedyStepwise (forward)
                // AFMethod = openjpa-kernel/.../DetachedStateManager.java::attach
                String arffPath = "src/main/resources/openjpa/otherFiles/OPENJPA_fullDataset.arff";
                Classifier clf = buildRandomForestWithBestFirst(N_SMELL);
                    analyzer.run(
                            arffPath,
                            clf,
                            N_SMELL,
                            new FileCSVGenerator(outputPath, projectName),
                            1.55
                    );


            } else {
                Logger.getAnonymousLogger().log(
                        Level.WARNING,
                        "Progetto non riconosciuto: {0} (attesi: {1})",
                        new Object[]{projectName, "BOOKKEEPER o OPENJPA"}
                );
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante SmellImpactAnalyzer.run()", e);
        }
    }

    // --------------- MODEL BUILDERS ---------------
    /**
     * Costruisce un NaiveBayes incapsulato in AttributeSelectedClassifier
     * con CfsSubsetEval + GreedyStepwise (forward).
     * Nota: così il “greedy stepwise” viene applicato “prima” del classifier
     * in modo integrato al training (CV/fold-safe), senza dover salvare un ARFF filtrato.
     */
    public static Classifier buildRandomForestWithBestFirst(String mustKeepAttrName) {
        BestFirst bf = new BestFirst();

        CfsSubsetEval eval = new CfsSubsetEval();

        AttributeSelectedClassifier asc = new AttributeSelectedClassifier() {
            @Override
            public void buildClassifier(Instances data) throws Exception {
                int keepIdx0 = data.attribute(mustKeepAttrName).index();
                int keepIdx1 = keepIdx0 + 1; // 1-based per Weka
                ((BestFirst) getSearch()).setStartSet(Integer.toString(keepIdx1));
                super.buildClassifier(data);
            }
        };

        asc.setEvaluator(eval);
        asc.setSearch(bf);
        asc.setClassifier(new RandomForest());

        return asc;
    }


    // --------------- UTILS ---------------
    private static Properties loadProperties(String path) {
        Properties prop = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            prop.load(in);
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(
                    Level.WARNING,
                    "Impossibile leggere {0}: {1}",
                    new Object[]{path, e.getMessage()}
            );
        }
        return prop;
    }

    private static boolean parseBool(String s) {
        return s != null && s.trim().equalsIgnoreCase("true");
    }
}
