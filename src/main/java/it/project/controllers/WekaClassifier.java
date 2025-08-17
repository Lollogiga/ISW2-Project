package it.project.controllers;

import it.project.entities.ClassifierResults;
import it.project.entities.ClassifierSettings;
import it.project.utils.DetectWalkPass;
import it.project.utils.FileCSVGenerator;

import weka.attributeSelection.ASEvaluation;
import weka.attributeSelection.ASSearch;
import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.BestFirst;
import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.attributeSelection.WrapperSubsetEval;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;

import weka.core.Instances;
import weka.core.SelectedTag;
import weka.core.converters.ConverterUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WekaClassifier {

    private static final Logger LOG = Logger.getLogger(WekaClassifier.class.getName());

    private final String projName;
    private final int walkPass;

    /* Create List of classifiers to use */
    private final List<Classifier> classifiers = new ArrayList<>(List.of(new NaiveBayes(), new RandomForest(), new IBk()));

    private static final String RESOURCES = "src/main/resources/";

    private static final String NAIVE_BAYES = "naive bayes";
    private static final String RANDOM_FOREST = "random forest";
    private static final String IBK = "IBK";

    private static final String[] CLASSIFIER_NAME = {NAIVE_BAYES, RANDOM_FOREST, IBK};

    public WekaClassifier(String projName) throws IOException {
        this.projName = projName;
        this.walkPass = new DetectWalkPass(projName).detectWalkPass();
    }

    /** Piccolo contenitore per i dataset ridotti + selector (se vuoi ispezionarlo) */
    private static final class FSResult {
        final Instances train;
        final Instances test;
        final AttributeSelection selector;
        FSResult(Instances train, Instances test, AttributeSelection selector) {
            this.train = train;
            this.test = test;
            this.selector = selector;
        }
    }

    /**
     * Esegue la feature selection SOLO sul train, logga le feature scelte e
     * applica lo stesso subset a train e test. Ritorna le due Instances ridotte.
     */
    private FSResult runFeatureSelection(
            Instances trainDataset,
            Instances testDataset,
            String labelForLog,
            ASEvaluation evaluator,
            ASSearch search
    ) throws Exception {

        // Imposta/garantisce la classe come ultima colonna
        trainDataset.setClassIndex(trainDataset.numAttributes() - 1);
        testDataset.setClassIndex(testDataset.numAttributes() - 1);

        AttributeSelection selector = new AttributeSelection();
        selector.setEvaluator(evaluator);
        selector.setSearch(search);

        // Selezione SOLO sul train
        selector.SelectAttributes(trainDataset);

        // Log delle feature scelte
        logSelectedFeatures(selector, trainDataset, labelForLog);

        // Riduci entrambi usando LO STESSO subset
        Instances newTrain = selector.reduceDimensionality(new Instances(trainDataset));
        Instances newTest  = selector.reduceDimensionality(new Instances(testDataset));

        // Reimposta class index dopo la riduzione
        newTrain.setClassIndex(newTrain.numAttributes() - 1);
        newTest.setClassIndex(newTest.numAttributes() - 1);

        return new FSResult(newTrain, newTest, selector);
    }

    public List<ClassifierResults> fetchWekaAnalysis() throws Exception {
        List<ClassifierResults> classifierResults = new ArrayList<>();

        /* Walk forward analysis */
        for (int i = 1; i <= walkPass; i++) {
            String arffTrainingPath = RESOURCES + projName.toLowerCase() + "/training/ARFF/" + this.projName + "_training_iter_" + i + ".arff";
            ConverterUtils.DataSource trainingSource = new ConverterUtils.DataSource(arffTrainingPath);
            Instances trainDataset = trainingSource.getDataSet();

            String arffTestingPath = RESOURCES + projName.toLowerCase() + "/testing/ARFF/" + this.projName + "_testing_iter_" + i + ".arff";
            ConverterUtils.DataSource testingSource = new ConverterUtils.DataSource(arffTestingPath);
            Instances testDataset = testingSource.getDataSet();

            /* Setting class to predict to last column (buggyness) */
            trainDataset.setClassIndex(trainDataset.numAttributes() - 1);
            testDataset.setClassIndex(testDataset.numAttributes() - 1);

            // ===== 0) Base: nessuna selezione =====
            Logger.getAnonymousLogger().log(Level.INFO, "Default Classifier");
            {
                ClassifierSettings settings = new ClassifierSettings();
                evaluateClassifier(classifierResults, i, trainDataset, testDataset, settings);
            }

            // Preparazione evaluator comune (CFS)
            CfsSubsetEval cfs = new CfsSubsetEval();

            // ===== 1) GreedyStepwise FORWARD + CFS =====
            {
                GreedyStepwise gsForward = new GreedyStepwise();
                gsForward.setSearchBackwards(false);
                String label = "GreedyStepwise FORWARD + CFS at iteration " + i;

                FSResult fs = runFeatureSelection(
                        new Instances(trainDataset), new Instances(testDataset),
                        label, cfs, gsForward
                );

                ClassifierSettings settings = new ClassifierSettings();
                settings.setFeatureSelection("GreedyStepwise Forward + CFS");
                evaluateClassifier(classifierResults, i, fs.train, fs.test, settings);
            }

            // ===== 2) GreedyStepwise BACKWARD + CFS =====
            {
                GreedyStepwise gsBackward = new GreedyStepwise();
                gsBackward.setSearchBackwards(true);
                String label = "GreedyStepwise BACKWARD + CFS at iteration " + i;

                FSResult fs = runFeatureSelection(
                        new Instances(trainDataset), new Instances(testDataset),
                        label, cfs, gsBackward
                );

                ClassifierSettings settings = new ClassifierSettings();
                settings.setFeatureSelection("GreedyStepwise Backward + CFS");
                evaluateClassifier(classifierResults, i, fs.train, fs.test, settings);
            }

            // ===== 2) GreedyStepwise BACKWARD + CFS =====
            {
                BestFirst bestFirst = new BestFirst();
                String label = "BestFirst + CFS at iteration " + i;

                FSResult fs = runFeatureSelection(
                        new Instances(trainDataset), new Instances(testDataset),
                        label, cfs, bestFirst
                );

                ClassifierSettings settings = new ClassifierSettings();
                settings.setFeatureSelection("BestFirst + CFS");
                evaluateClassifier(classifierResults, i, fs.train, fs.test, settings);
            }

        }

        FileCSVGenerator csvGenerator = new FileCSVGenerator(RESOURCES, projName);
        csvGenerator.generateWekaResultFile(classifierResults, 0);
        return classifierResults;
    }

    private void evaluateClassifier(
            List<ClassifierResults> classifierResults,
            int iteration,
            Instances trainDataset,
            Instances testDataset,
            ClassifierSettings settings
    ) throws Exception {
        int index = 0;
        String combinationClassifier = settings.getFeatureSelection();

        for (Classifier classifier : classifiers) {
            // Addestra sul TRAIN
            classifier.buildClassifier(trainDataset);

            // Valuta sul TEST (inizializzando con il TRAIN è la prassi corretta)
            Evaluation evaluation = new Evaluation(trainDataset);
            evaluation.evaluateModel(classifier, testDataset);

            // Individua dinamicamente l'indice della classe positiva "yes"
            int pos = testDataset.classAttribute().indexOfValue("yes");
            if (pos < 0) pos = 0; // fallback, se il nome fosse diverso

            // Crea il contenitore dei risultati per questo (iter, classifier)
            ClassifierResults res = new ClassifierResults(
                    this.projName, iteration, CLASSIFIER_NAME[index], settings,
                    trainDataset.numInstances(), testDataset.numInstances()
            );

            // === Metriche riferite alla classe positiva (pos) ===
            res.setRec(evaluation.recall(pos));
            res.setPreci(evaluation.precision(pos));
            res.setKappa(evaluation.kappa());
            res.setAuc(evaluation.areaUnderROC(pos));
            res.setFMeasure(evaluation.fMeasure(pos));

            // === Ricostruzione coerente di TP/TN/FP/FN dalla confusion matrix ===
            double[][] cm = evaluation.confusionMatrix();

            // Supponiamo binario: {no, yes} oppure {yes, no}. Mappiamo sempre rispetto a "yes".
            int yesIndex = pos;
            int noIndex = (testDataset.classAttribute().numValues() == 2)
                    ? (1 - yesIndex)
                    : (yesIndex == 0 ? 1 : 0); // fallback semplice per binario

            long tp = Math.round(cm[yesIndex][yesIndex]); // true positives: yes predetto yes
            long fn = Math.round(cm[yesIndex][noIndex]);  // false negatives: yes predetto no
            long tn = Math.round(cm[noIndex][noIndex]);   // true negatives: no predetto no
            long fp = Math.round(cm[noIndex][yesIndex]);  // false positives: no predetto yes

            res.setTruePositives(tp);
            res.setFalseNegatives(fn);
            res.setTrueNegatives(tn);
            res.setFalsePositives(fp);

            // Colleziona i risultati
            classifierResults.add(res);

            // Salva anche le predizioni per-istanza su CSV
            makePrediction(classifier, testDataset, iteration, index, combinationClassifier);

            index++;
        }

        // CSV cumulativo per l'iterazione corrente
        FileCSVGenerator csvGenerator = new FileCSVGenerator(RESOURCES, projName);
        csvGenerator.generateWekaResultFile(classifierResults, iteration);
    }

    public static void logSelectedFeatures(AttributeSelection selector, Instances dataset, String context) throws Exception {
        int[] indices = selector.selectedAttributes();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Features selezionate [").append(context).append("] ===\n");

        for (int idx : indices) {
            if (idx == dataset.classIndex()) continue; // skip la classe
            sb.append("- ").append(dataset.attribute(idx).name()).append("\n");
        }

        LOG.info(sb.toString());
    }

    private void makePrediction(Classifier classifier,
                                Instances testDataset,
                                int iteration,
                                int indexClassifier,
                                String combination) throws Exception {

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {
                "iteration","classifier","feature_selection",
                "instance_id","actual","predicted","p_yes","p_no"
        });

        int pos = testDataset.classAttribute().indexOfValue("yes");
        if (pos < 0) pos = 0;
        int neg = (testDataset.classAttribute().numValues() == 2) ? (1 - pos)
                : (pos == 0 ? 1 : 0);

        for (int i = 0; i < testDataset.numInstances(); i++) {
            double[] dist = classifier.distributionForInstance(testDataset.instance(i));
            int predicted = (int) classifier.classifyInstance(testDataset.instance(i));
            int actual = (int) testDataset.instance(i).classValue();
            String instanceId = extractInstanceId(testDataset, i);

            rows.add(new String[] {
                    String.valueOf(iteration),
                    CLASSIFIER_NAME[indexClassifier],
                    (combination == null || combination.isBlank()) ? "base" : combination,
                    instanceId,
                    String.valueOf(actual),
                    String.valueOf(predicted),
                    (pos < dist.length) ? String.valueOf(dist[pos]) : "NaN",
                    (neg < dist.length) ? String.valueOf(dist[neg]) : "NaN"
            });
        }

        FileCSVGenerator csv = new FileCSVGenerator(RESOURCES, projName);
        csv.generatePredictionsFile(rows, CLASSIFIER_NAME[indexClassifier], combination, iteration);
    }

    private String extractInstanceId(Instances ds, int i) {
        String[] candidateNames = { "key", "id", "method", "method_id", "signature", "name", "file", "path" };
        for (String attrName : candidateNames) {
            if (ds.attribute(attrName) != null) {
                int idx = ds.attribute(attrName).index();
                try {
                    if (ds.attribute(idx).isString() || ds.attribute(idx).isNominal()) {
                        return ds.instance(i).stringValue(idx);
                    } else {
                        return String.valueOf(ds.instance(i).value(idx));
                    }
                } catch (Exception ignore) {
                    // ignora eventuali valori mancanti
                }
            }
        }
        return "row_" + i;
    }

    private boolean selectionHasPredictors(AttributeSelection sel, Instances ds) throws Exception {
        int[] selIdx = sel.selectedAttributes(); // include la class
        if (selIdx == null || selIdx.length == 0) return false;
        int cls = ds.classIndex();
        int predictors = 0;
        for (int a : selIdx) if (a != cls) predictors++;
        return predictors > 0;
    }
}
