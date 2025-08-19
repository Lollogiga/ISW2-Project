package it.project.controllers;

import it.project.entities.ClassifierResults;
import it.project.entities.ClassifierSettings;
import it.project.utils.DetectWalkPass;
import it.project.utils.FileCSVGenerator;

import weka.attributeSelection.*;

import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;

import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.core.converters.ConverterUtils;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoublePredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WekaClassifier {

    private static final Logger LOG = Logger.getLogger(WekaClassifier.class.getName());

    private final String projName;
    private final int walkPass;
    private final FileCSVGenerator csvGenerator;
    /* Create List of classifiers to use */
    private final List<Classifier> classifiers = new ArrayList<>(List.of(new NaiveBayes(), new RandomForest(), new IBk()));

    private static final String RESOURCES = "src/main/resources/";
    private static final String NAIVE_BAYES = "naive bayes";
    private static final String RANDOM_FOREST = "random forest";
    private static final String IBK = "IBK";

    private static final String[] CLASSIFIER_NAME = {NAIVE_BAYES, RANDOM_FOREST, IBK};

    public WekaClassifier(String projName, FileCSVGenerator csvGenerator) throws IOException {
        this.projName = projName;
        this.csvGenerator = csvGenerator;
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

    public void fetchWekaAnalysis() throws Exception {
        List<ClassifierResults> classifierResults = new ArrayList<>();

        for (int i = 1; i <= walkPass; i++) {
            String arffTrainingPath = Paths.get(RESOURCES, projName.toLowerCase(),
                    "training", "ARFF", this.projName + "_training_iter_" + i + ".arff").toString();
            ConverterUtils.DataSource trainingSource = new ConverterUtils.DataSource(arffTrainingPath);
            Instances trainDataset = trainingSource.getDataSet();

            String arffTestingPath = Paths.get(RESOURCES, projName.toLowerCase(),
                    "testing", "ARFF", this.projName + "_testing_iter_" + i + ".arff").toString();
            ConverterUtils.DataSource testingSource = new ConverterUtils.DataSource(arffTestingPath);
            Instances testDataset = testingSource.getDataSet();

            trainDataset.setClassIndex(trainDataset.numAttributes() - 1);
            testDataset.setClassIndex(testDataset.numAttributes() - 1);

            // ===== 0) Base: nessuna selezione =====
            Logger.getAnonymousLogger().log(Level.INFO, "Default Classifier");
            ClassifierSettings defaultSettings = new ClassifierSettings();
            evaluateClassifier(classifierResults, i, trainDataset, testDataset, defaultSettings);

            // Preparazione evaluator comune (CFS)
            CfsSubsetEval cfs = new CfsSubsetEval();

            // ===== 1) Forward =====
            GreedyStepwise gsForward = new GreedyStepwise();
            gsForward.setSearchBackwards(false);
            runAndEvaluateFeatureSelection(
                    classifierResults, i, trainDataset, testDataset,
                    "GreedyStepwise Forward + CFS",
                    cfs, gsForward
            );

            // ===== 2) Backward =====
            GreedyStepwise gsBackward = new GreedyStepwise();
            gsBackward.setSearchBackwards(true);
            runAndEvaluateFeatureSelection(
                    classifierResults, i, trainDataset, testDataset,
                    "GreedyStepwise Backward + CFS",
                    cfs, gsBackward
            );

            // ===== 3) BestFirst =====
            BestFirst bestFirst = new BestFirst();
            runAndEvaluateFeatureSelection(
                    classifierResults, i, trainDataset, testDataset,
                    "BestFirst + CFS",
                    cfs, bestFirst
            );
        }

        csvGenerator.generateWekaResultFile(classifierResults, 0);
    }

    private void runAndEvaluateFeatureSelection(
            List<ClassifierResults> classifierResults,
            int iteration,
            Instances trainDataset,
            Instances testDataset,
            String fsLabel,       // es. "GreedyStepwise Forward + CFS"
            ASEvaluation evaluator,
            ASSearch search
    ) throws Exception {
        String logLabel = fsLabel + " at iteration" + iteration;
        FSResult fs = runFeatureSelection(
                new Instances(trainDataset),
                new Instances(testDataset),
                iteration,
                logLabel,
                evaluator,
                search
        );

        ClassifierSettings settings = new ClassifierSettings();
        settings.setFeatureSelection(fsLabel);
        evaluateClassifier(classifierResults, iteration, fs.train, fs.test, settings);
    }

    // Piccolo holder per restituire sia il modello sia i risultati
    private static final class EvalOutcome {
        final Classifier model;
        final ClassifierResults results;
        EvalOutcome(Classifier model, ClassifierResults results) {
            this.model = model;
            this.results = results;
        }
    }

    private void evaluateClassifier(
            List<ClassifierResults> classifierResults,
            int iteration,
            Instances trainDataset,
            Instances testDataset,
            ClassifierSettings settings
    ) throws Exception {

        String combinationClassifier = settings.getFeatureSelection();

        for (int index = 0; index < classifiers.size(); index++) {
            Classifier prototype = classifiers.get(index);

            EvalOutcome outcome = evaluateSingleClassifier(
                    prototype, index, iteration,
                    trainDataset, testDataset,
                    settings, combinationClassifier
            );

            classifierResults.add(outcome.results);
            // Salvataggio predizioni per-istanza sul TEST (come prima)
            makePrediction(outcome.model, testDataset, iteration, index, combinationClassifier);
        }

        // CSV cumulativo per l'iterazione
        csvGenerator.generateWekaResultFile(classifierResults, iteration);
    }

// ========================= Helpers =========================

    private int positiveClassIndex(Instances data) {
        int idx = data.classAttribute().indexOfValue("yes");
        return Math.max(idx, 0);
    }

    private EvalOutcome evaluateSingleClassifier(
            Classifier prototype,
            int index,
            int iteration,
            Instances trainDataset,
            Instances testDataset,
            ClassifierSettings settings,
            String combinationClassifier
    ) throws Exception {

        int pos = positiveClassIndex(testDataset); // <--- calcolato qui

        long seed = 12345L + iteration * 100L + index;
        Evaluation cvEval = crossValidate(prototype, trainDataset, seed);
        logCvMetrics(iteration, index, combinationClassifier, cvEval, pos);

        Classifier model = AbstractClassifier.makeCopy(prototype);
        model.buildClassifier(trainDataset);

        Evaluation testEval = new Evaluation(trainDataset);
        testEval.evaluateModel(model, testDataset);

        ClassifierResults res = baseResults(settings, trainDataset, testDataset, iteration, index);
        fillStandardMetrics(res, testEval, pos);
        fillConfusionMatrix(res, testEval.confusionMatrix(), testDataset, pos);
        fillEffortAwareMetricsIfPossible(res, model, testDataset, pos);
        res.setAccuracy(testEval.pctCorrect() / 100.0);

        return new EvalOutcome(model, res);
    }


    private Evaluation crossValidate(Classifier prototype, Instances trainDataset, long seed) throws Exception {
        Evaluation cvEval = new Evaluation(trainDataset);
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        rnd.setSeed(seed); // garantisce risultati replicabili
        cvEval.crossValidateModel(prototype, trainDataset, 10, rnd);
        return cvEval;
    }



    private void logCvMetrics(int iteration, int index, String combinationClassifier, Evaluation cvEval, int pos) {
        if (!LOG.isLoggable(Level.INFO)) return;
        String combo = (combinationClassifier == null || combinationClassifier.isBlank()) ? "base" : combinationClassifier;
        LOG.info(String.format(
                "[Iter %d | %s | %s] 10-fold CV -> Prec=%.4f Rec=%.4f F1=%.4f AUC=%.4f Kappa=%.4f",
                iteration,
                CLASSIFIER_NAME[index],
                combo,
                cvEval.precision(pos),
                cvEval.recall(pos),
                cvEval.fMeasure(pos),
                cvEval.areaUnderROC(pos),
                cvEval.kappa()
        ));
    }

    private ClassifierResults baseResults(
            ClassifierSettings settings,
            Instances trainDataset,
            Instances testDataset,
            int iteration,
            int index
    ) {
        return new ClassifierResults(
                this.projName, iteration, CLASSIFIER_NAME[index], settings,
                trainDataset.numInstances(), testDataset.numInstances()
        );
    }

    private void fillStandardMetrics(ClassifierResults res, Evaluation eval, int pos) {
        res.setRec(eval.recall(pos));
        res.setPreci(eval.precision(pos));
        res.setKappa(eval.kappa());
        res.setAuc(eval.areaUnderROC(pos));
        res.setFMeasure(eval.fMeasure(pos));
    }

    private void fillConfusionMatrix(ClassifierResults res, double[][] cm, Instances testDataset, int pos) {
        int numValues = testDataset.classAttribute().numValues();

        int noIndex;
        if (numValues == 2) {
            noIndex = 1 - pos;
        } else {
            noIndex = (pos == 0 ? 1 : 0);
        }

        long tp = Math.round(cm[pos][pos]);         // yes → yes
        long fn = Math.round(cm[pos][noIndex]);     // yes → no
        long tn = Math.round(cm[noIndex][noIndex]); // no  → no
        long fp = Math.round(cm[noIndex][pos]);     // no  → yes

        res.setTruePositives(tp);
        res.setFalseNegatives(fn);
        res.setTrueNegatives(tn);
        res.setFalsePositives(fp);
    }


    private void fillEffortAwareMetricsIfPossible(ClassifierResults res, Classifier model, Instances testDataset, int pos) throws Exception {
        int locIdx = findLocIndex(testDataset);
        if (locIdx < 0) {
            res.setPofB20(Double.NaN);
            res.setNpofB20(Double.NaN);
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.warning("Attributo LOC non trovato nel TEST: PofB20/NPofB20 non calcolabili.");
            }
            return;
        }

        int n = testDataset.numInstances();
        int[] locArr = new int[n];
        double[] probYes = new double[n];
        boolean[] buggy = new boolean[n];

        for (int i = 0; i < n; i++) {
            var inst = testDataset.instance(i);

            // LOC (0 se mancante)
            double v = inst.isMissing(locIdx) ? 0.0 : inst.value(locIdx);
            locArr[i] = (int) Math.max(0, Math.round(v));

            // probabilità "yes"
            double[] dist = model.distributionForInstance(inst);
            probYes[i] = (pos < dist.length && !Double.isNaN(dist[pos])) ? dist[pos] : 0.0;

            // label reale
            int y = (int) inst.classValue();
            buggy[i] = (y == pos);
        }

        double pofB20 = computePofB20(locArr, probYes, buggy);
        res.setPofB20(pofB20);
        res.setNpofB20(computeNPofB20(pofB20));
    }



    public static void logSelectedFeatures(AttributeSelection selector, Instances dataset, String context) throws Exception {
        if (!LOG.isLoggable(Level.INFO)) {
            return; // evita costruzione stringa se non serve
        }

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

        int neg;
        if (testDataset.classAttribute().numValues() == 2) {
            neg = 1 - pos;
        } else if (pos == 0) {
            neg = 1;
        } else {
            neg = 0;
        }

        for (int i = 0; i < testDataset.numInstances(); i++) {
            var inst = testDataset.instance(i);
            double[] dist = classifier.distributionForInstance(inst);
            int predicted = (int) classifier.classifyInstance(inst);
            int actual = (int) inst.classValue();
            String instanceId = extractInstanceId(testDataset, i);

            String fsLabel = (combination == null || combination.isBlank()) ? "base" : combination;
            String pYes = (pos < dist.length) ? String.valueOf(dist[pos]) : "NaN";
            String pNo  = (neg < dist.length) ? String.valueOf(dist[neg]) : "NaN";

            rows.add(new String[] {
                    String.valueOf(iteration),
                    CLASSIFIER_NAME[indexClassifier],
                    fsLabel,
                    instanceId,
                    String.valueOf(actual),
                    String.valueOf(predicted),
                    pYes,
                    pNo
            });
        }

        csvGenerator.generatePredictionsFile(rows, CLASSIFIER_NAME[indexClassifier], combination, iteration);
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
                } catch (Exception _) {
                    // Ignorato: se l'attributo non è leggibile, si passa al prossimo candidato
                }
            }
        }
        return "row_" + i;
    }

    /**
     * Esegue la feature selection SOLO sul train, salva/logga le feature scelte e
     * applica lo stesso subset a train e test. Ritorna le due Instances ridotte.
     */
    private FSResult runFeatureSelection(
            Instances trainDataset,
            Instances testDataset,
            int iteration,
            String fsLabel,                 // es. "GreedyStepwise Forward + CFS"
            ASEvaluation evaluator,
            ASSearch search
    ) throws Exception {

        // garantisci class index
        trainDataset.setClassIndex(trainDataset.numAttributes() - 1);
        testDataset.setClassIndex(testDataset.numAttributes() - 1);

        AttributeSelection selector = new AttributeSelection();
        selector.setEvaluator(evaluator);
        selector.setSearch(search);

        // Selezione SOLO sul train
        selector.SelectAttributes(trainDataset);

        // Indici selezionati
        int[] indices = selector.selectedAttributes();

        // Log & salvataggio
        logSelectedFeatures(selector, trainDataset, fsLabel + " (iter " + iteration + ")");
        csvGenerator.saveSelectedFeatures(iteration, fsLabel, trainDataset, indices);

        // Riduci entrambi usando LO STESSO subset
        Instances newTrain = selector.reduceDimensionality(new Instances(trainDataset));
        Instances newTest  = selector.reduceDimensionality(new Instances(testDataset));

        // Reimposta class index dopo la riduzione
        newTrain.setClassIndex(newTrain.numAttributes() - 1);
        newTest.setClassIndex(newTest.numAttributes() - 1);

        return new FSResult(newTrain, newTest, selector);
    }


    // Piccolo DTO per i punteggi
    public static final class FeatureScore {
        public final String name;
        public final int index;
        public final double score;

        public FeatureScore(String name, int index, double score) {
            this.name = name;
            this.index = index;
            this.score = score;
        }

        @Override
        public String toString() {
            return name + " (idx=" + index + ", score=" + score + ")";
        }
    }

    public void runAndSaveFeatureCorrelationRanking(String arffPath, int topN) throws IOException {
        Instances data = loadArffOrThrow(arffPath);

        int clsIdx = pickClassIndexPreferIsBuggy(data);
        data.setClassIndex(clsIdx);

        double[] y = buildBinaryTarget(data, clsIdx);

        List<WekaClassifier.FeatureScore> scores =
                computePearsonScores(data, y, clsIdx);

        scores = sortAndTruncateByAbs(scores, topN);

        csvGenerator.saveFeaturesCorrelationRanking(scores);
    }

// ====================== Helpers (piccoli e coesi) ======================

    private static Instances loadArffOrThrow(String arffPath) throws IOException {
        if (arffPath == null || arffPath.isBlank())
            throw new IllegalArgumentException("ARFF path nullo o vuoto.");

        File f = new File(arffPath);
        if (!f.exists() || !f.isFile() || !f.canRead())
            throw new IOException("ARFF non trovato/illeggibile: " + f.getAbsolutePath());

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            ArffLoader.ArffReader reader = new ArffLoader.ArffReader(br);
            Instances data = reader.getData();
            if (data == null) throw new IOException("Caricamento ARFF fallito: " + f.getAbsolutePath());
            return data;
        }
    }

    private static int pickClassIndexPreferIsBuggy(Instances data) {
        var attr = data.attribute("isBuggy");
        return (attr != null) ? attr.index() : (data.numAttributes() - 1);
    }

    /**
     * Restituisce y binario in {0,1}, con Double.NaN per istanze con classe missing.
     * - Nominale con "yes": yes->1, altri->0
     * - Nominale binaria senza "yes": assume il SECONDO valore come positivo
     * - Numerica: soglia 0.5
     */
    private static double[] buildBinaryTarget(Instances data, int clsIdx) {
        double[] y = new double[data.numInstances()];
        var cls = data.classAttribute();

        if (cls.isNominal()) {
            int yesIdx = cls.indexOfValue("yes");
            if (yesIdx >= 0) {
                final int posIdx = yesIdx; // effectively final per il lambda
                fillBinaryByPredicate(data, clsIdx, v -> ((int) v) == posIdx, y);
            } else if (cls.numValues() == 2) {
                final int posIdx = 1; // convenzione: secondo valore positivo
                fillBinaryByPredicate(data, clsIdx, v -> ((int) v) == posIdx, y);
            } else {
                throw new IllegalStateException("Classe nominale non binaria e senza valore 'yes': " + cls);
            }
        } else if (cls.isNumeric()) {
            final double thr = 0.5;
            fillBinaryByPredicate(data, clsIdx, v -> v >= thr, y);
        } else {
            throw new IllegalStateException("Tipo attributo classe non gestito: " + cls);
        }

        return y;
    }

    private static void fillBinaryByPredicate(Instances data, int clsIdx, DoublePredicate isPositive, double[] y) {
        int n = data.numInstances();
        for (int i = 0; i < n; i++) {
            if (data.instance(i).isMissing(clsIdx)) {
                y[i] = Double.NaN;
            } else {
                double clsVal = data.instance(i).classValue();
                y[i] = isPositive.test(clsVal) ? 1.0 : 0.0;
            }
        }
    }

    /** Calcola Pearson solo per feature numeriche diverse dalla classe, ignorando missing. */
    private static List<WekaClassifier.FeatureScore> computePearsonScores(Instances data, double[] y, int clsIdx) {
        List<WekaClassifier.FeatureScore> scores = new ArrayList<>();

        int attrs = data.numAttributes();
        for (int a = 0; a < attrs; a++) {
            // salta non-classe e non-numeriche senza usare continue
            if (a == clsIdx || !data.attribute(a).isNumeric()) {
                continue; // <-- se vuoi ZERO continue, rimuovi questa riga e inverti la condizione sotto
            }

            ValidPairs pairs = extractValidPairs(data, y, a);
            if (pairs.n >= 3) {
                double r = pearson(pairs.xs, pairs.ys, pairs.n);
                if (!Double.isNaN(r) && !Double.isInfinite(r)) {
                    scores.add(new WekaClassifier.FeatureScore(data.attribute(a).name(), a, r));
                }
            }
        }
        return scores;
    }


    /** Punti validi (senza missing): array primitivi per ridurre overhead. */
    private static final class ValidPairs {
        final double[] xs;
        final double[] ys;
        final int n;
        ValidPairs(double[] xs, double[] ys, int n) { this.xs = xs; this.ys = ys; this.n = n; }
    }

    private static ValidPairs extractValidPairs(Instances data, double[] y, int attrIdx) {
        int n = data.numInstances();
        double[] xsTmp = new double[n];
        double[] ysTmp = new double[n];
        int k = 0;

        for (int i = 0; i < n; i++) {
            if (!data.instance(i).isMissing(attrIdx) && !Double.isNaN(y[i])) {
                xsTmp[k] = data.instance(i).value(attrIdx);
                ysTmp[k] = y[i];
                k++;
            }
        }
        return new ValidPairs(Arrays.copyOf(xsTmp, k), Arrays.copyOf(ysTmp, k), k);
    }

    /** Pearson r in [-1,1]. Restituisce NaN se varianza nulla. */
    private static double pearson(double[] x, double[] y, int n) {
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) { meanX += x[i]; meanY += y[i]; }
        meanX /= n; meanY /= n;

        double num = 0.0;
        double denX = 0.0;
        double denY = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        double denom = Math.sqrt(denX) * Math.sqrt(denY);
        if (denom == 0.0) return Double.NaN;
        return num / denom;
    }

    private static List<WekaClassifier.FeatureScore> sortAndTruncateByAbs(List<WekaClassifier.FeatureScore> scores, int topN) {
        scores.sort((p, q) -> Double.compare(Math.abs(q.score), Math.abs(p.score)));
        if (topN > 0 && topN < scores.size()) {
            return new ArrayList<>(scores.subList(0, topN));
        }
        return scores;
    }



    /** Ritorna l'indice dell'attributo LOC (case-insensitive), -1 se non trovato. */
    private static int findLocIndex(Instances ds) {
        String name = "LOC";
        if (ds.attribute(name) != null) {
            return ds.attribute(name).index();
        }

        // fallback: prova a cercare "loc" nella stringa del nome
        for (int i = 0; i < ds.numAttributes(); i++) {
            String nm = ds.attribute(i).name();
            if (nm != null && nm.toLowerCase().contains("loc")) return i;
        }
        return -1;
    }

    /** PofB20: % di bug trovati ispezionando il 20% del LOC totale, ordinando per score desc. */
    private static double computePofB20(int[] loc, double[] probYes, boolean[] isBuggy) {
        final int n = loc.length;

        long totalLOC = 0;
        int totalBugs = 0;
        for (int i = 0; i < n; i++) {
            totalLOC += Math.max(0, loc[i]);
            if (isBuggy[i]) totalBugs++;
        }
        if (totalBugs == 0 || totalLOC == 0) return Double.NaN;

        // ordina per score (probabilità di "yes") decrescente
        Integer[] idx = java.util.stream.IntStream.range(0, n).boxed().toArray(Integer[]::new);
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(probYes[b], probYes[a]));

        long limitLOC = Math.round(0.2 * totalLOC);
        long accLOC = 0;
        int bugsFound = 0;

        for (int id : idx) {
            if (accLOC >= limitLOC) break;
            accLOC += Math.max(0, loc[id]);
            if (isBuggy[id]) bugsFound++;
        }
        return bugsFound / (double) totalBugs;
    }

    /**
     * NPofB20 (random-normalized): normalizza PofB20 contro random (=0.2) e best (=1.0).
     * Formula: (PofB20 - 0.2) / (1 - 0.2) con clamp [0,1].
     * Nota: è una normalizzazione pratica e molto usata; se vuoi la versione "best/worst"
     * esatta basata sul profilo LOC dei bug, la scriviamo in un secondo momento.
     */
    private static double computeNPofB20(double pofB20) {
        if (Double.isNaN(pofB20)) return Double.NaN;
        double np = (pofB20 - 0.2) / 0.8;
        if (np < 0) np = 0;
        if (np > 1) np = 1;
        return np;
    }



}
