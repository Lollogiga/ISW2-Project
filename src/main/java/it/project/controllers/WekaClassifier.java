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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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

    public void fetchWekaAnalysis() throws Exception {
        List<ClassifierResults> classifierResults = new ArrayList<>();
        FileCSVGenerator csvGenerator = new FileCSVGenerator(RESOURCES, projName);

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
                evaluateClassifier(classifierResults, i, trainDataset, testDataset, settings, csvGenerator);
            }

            // Preparazione evaluator comune (CFS)
            CfsSubsetEval cfs = new CfsSubsetEval();

            // ===== 1) GreedyStepwise FORWARD + CFS =====
            {
                GreedyStepwise gsForward = new GreedyStepwise();
                gsForward.setSearchBackwards(false);
                String label = "GreedyStepwise FORWARD + CFS at iteration " + i;

                FSResult fs = runFeatureSelection(
                        new Instances(trainDataset), new Instances(testDataset), i,
                        label, cfs, gsForward, csvGenerator
                );

                ClassifierSettings settings = new ClassifierSettings();
                settings.setFeatureSelection("GreedyStepwise Forward + CFS");
                evaluateClassifier(classifierResults, i, fs.train, fs.test, settings, csvGenerator);
            }

            // ===== 2) GreedyStepwise BACKWARD + CFS =====
            {
                GreedyStepwise gsBackward = new GreedyStepwise();
                gsBackward.setSearchBackwards(true);
                String label = "GreedyStepwise BACKWARD + CFS at iteration " + i;

                FSResult fs = runFeatureSelection(
                        new Instances(trainDataset), new Instances(testDataset), i,
                        label, cfs, gsBackward, csvGenerator
                );

                ClassifierSettings settings = new ClassifierSettings();
                settings.setFeatureSelection("GreedyStepwise Backward + CFS");
                evaluateClassifier(classifierResults, i, fs.train, fs.test, settings, csvGenerator);
            }

            // ===== 2) GreedyStepwise BACKWARD + CFS =====
            {
                BestFirst bestFirst = new BestFirst();
                String label = "BestFirst + CFS at iteration " + i;

                FSResult fs = runFeatureSelection(
                        new Instances(trainDataset), new Instances(testDataset), i,
                        label, cfs, bestFirst,  csvGenerator
                );

                ClassifierSettings settings = new ClassifierSettings();
                settings.setFeatureSelection("BestFirst + CFS");
                evaluateClassifier(classifierResults, i, fs.train, fs.test, settings, csvGenerator);
            }

        }

        csvGenerator.generateWekaResultFile(classifierResults, 0);
    }

    private void evaluateClassifier(
            List<ClassifierResults> classifierResults,
            int iteration,
            Instances trainDataset,
            Instances testDataset,
            ClassifierSettings settings,
            FileCSVGenerator csvGenerator
    ) throws Exception {

        int index = 0;
        String combinationClassifier = settings.getFeatureSelection();

        // indice della classe positiva "yes" (fallback 0 se non esiste)
        int pos = testDataset.classAttribute().indexOfValue("yes");
        if (pos < 0) pos = 0;

        for (Classifier prototype : classifiers) {
            long seed = 12345L + iteration * 100 + index; // riproducibilità

            // ===== A) 10-fold CV sul TRAIN (solo stima/tuning) =====
            Evaluation cvEval = new Evaluation(trainDataset);
            cvEval.crossValidateModel(prototype, trainDataset, /*folds*/10, new java.util.Random(seed));

            if (LOG.isLoggable(Level.INFO)) {
                LOG.info(String.format(
                        "[Iter %d | %s | %s] 10-fold CV -> Prec=%.4f Rec=%.4f F1=%.4f AUC=%.4f Kappa=%.4f",
                        iteration,
                        CLASSIFIER_NAME[index],
                        (combinationClassifier == null || combinationClassifier.isBlank()) ? "base" : combinationClassifier,
                        cvEval.precision(pos),
                        cvEval.recall(pos),
                        cvEval.fMeasure(pos),
                        cvEval.areaUnderROC(pos),
                        cvEval.kappa()
                ));
            }

            // (opzionale) tuning basato su cvEval qui...

            // ===== B) Addestramento sul TRAIN completo =====
            Classifier model = AbstractClassifier.makeCopy(prototype);
            model.buildClassifier(trainDataset);

            // ===== C) Valutazione sul TEST =====
            Evaluation testEval = new Evaluation(trainDataset);
            testEval.evaluateModel(model, testDataset);

            // Crea record risultati riferito alla valutazione sul TEST
            ClassifierResults res = new ClassifierResults(
                    this.projName, iteration, CLASSIFIER_NAME[index], settings,
                    trainDataset.numInstances(), testDataset.numInstances()
            );

            // Metriche standard (sulla classe positiva "yes")
            res.setRec(testEval.recall(pos));
            res.setPreci(testEval.precision(pos));
            res.setKappa(testEval.kappa());
            res.setAuc(testEval.areaUnderROC(pos));
            res.setFMeasure(testEval.fMeasure(pos));

            // Confusion matrix (binario): mappa rispetto a "yes"
            double[][] cm = testEval.confusionMatrix();
            int noIndex = (testDataset.classAttribute().numValues() == 2) ? (1 - pos) : (pos == 0 ? 1 : 0);

            long tp = Math.round(cm[pos][pos]);       // yes predetto yes
            long fn = Math.round(cm[pos][noIndex]);   // yes predetto no
            long tn = Math.round(cm[noIndex][noIndex]); // no predetto no
            long fp = Math.round(cm[noIndex][pos]);   // no predetto yes

            res.setTruePositives(tp);
            res.setFalseNegatives(fn);
            res.setTrueNegatives(tn);
            res.setFalsePositives(fp);

            // ===== D) PofB20 / NPofB20 sul TEST (effort-aware) =====
            int locIdx = findLocIndex(testDataset);
            if (locIdx >= 0) {
                int n = testDataset.numInstances();
                int[] locArr = new int[n];
                double[] probYes = new double[n];
                boolean[] buggy = new boolean[n];

                for (int i = 0; i < n; i++) {
                    var inst = testDataset.instance(i);

                    // LOC (0 se mancante)
                    double v = inst.isMissing(locIdx) ? 0.0 : inst.value(locIdx);
                    locArr[i] = (int) Math.max(0, Math.round(v));

                    // probabilità di "yes" (0 se non disponibile)
                    double[] dist = model.distributionForInstance(inst);
                    probYes[i] = (pos < dist.length && !Double.isNaN(dist[pos])) ? dist[pos] : 0.0;

                    // label reale
                    int y = (int) inst.classValue();
                    buggy[i] = (y == pos);
                }

                double pofB20 = computePofB20(locArr, probYes, buggy);
                double npofB20 = computeNPofB20(pofB20);
                res.setPofB20(pofB20);
                res.setNpofB20(npofB20);
            } else {
                // Se non troviamo LOC, lasciamo NaN nelle colonne effort-aware
                res.setPofB20(Double.NaN);
                res.setNpofB20(Double.NaN);
                if (LOG.isLoggable(Level.WARNING)) {
                    LOG.warning("Attributo LOC non trovato nel TEST: PofB20/NPofB20 non calcolabili.");
                }
            }

            // Colleziona i risultati
            classifierResults.add(res);

            // ===== E) Salva predizioni per-istanza sul TEST (CSV) =====
            // (resta invariato; volendo hai aggiunto la colonna LOC in makePrediction)
            makePrediction(model, testDataset, iteration, index, combinationClassifier);

            index++;
        }

        // ===== F) CSV cumulativo per l'iterazione corrente (come prima) =====
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
            ASSearch search,
            FileCSVGenerator csvGenerator   // per salvare il CSV delle feature scelte
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

    /**
     * Calcola il ranking (con segno) della correlazione di Pearson tra ogni feature numerica
     * e la buggyness (target binario), e salva in OTHERFILES/<proj>_featuresCorrelationWithBuggyness.csv.
     *
     * Requisiti:
     * - Il dataset deve avere la colonna target nominale {no, yes} chiamata "isBuggy"
     *   oppure, se assente, si usa l'ultima colonna come target.
     * - Considera SOLO le feature numeriche (ignora string/nominal non numeriche).
     * - Gestione dei missing: le coppie (x_i, y_i) con missing sono scartate nel calcolo della correlazione.
     *
     * @param arffPath path al .arff specifico
     * @param projName nome progetto (per FileCSVGenerator)
     * @param topN     se >0 salva solo le prime N feature per |corr| decrescente; se <=0 salva tutte
     */
    public static void runAndSaveFeatureCorrelationRanking(String arffPath, String projName, int topN) throws Exception {
        // 0) Caricamento robusto ARFF
        if (arffPath == null || arffPath.isBlank())
            throw new IllegalArgumentException("ARFF path nullo o vuoto.");
        File f = new File(arffPath);
        if (!f.exists() || !f.isFile() || !f.canRead())
            throw new IOException("ARFF non trovato/illeggibile: " + f.getAbsolutePath());

        Instances data;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            ArffLoader.ArffReader reader = new ArffLoader.ArffReader(br);
            data = reader.getData();
        }
        if (data == null) throw new IOException("Caricamento ARFF fallito: " + f.getAbsolutePath());

        // 1) Imposta target: preferisci "isBuggy", altrimenti ultima colonna
        int clsIdx = data.attribute("isBuggy") != null
                ? data.attribute("isBuggy").index()
                : data.numAttributes() - 1;
        data.setClassIndex(clsIdx);

        // 2) Costruisci il vettore binario y in {0,1}
        // - Se classe nominale con valore "yes": yes->1, altri->0
        // - Se classe nominale senza "yes" ma binaria: usa il SECONDO valore come 1 (convenzione)
        // - Se classe numerica: soglia 0.5 (>=0.5 -> 1, altrimenti 0)
        double[] y = new double[data.numInstances()];
        if (data.classAttribute().isNominal()) {
            int yesIdx = data.classAttribute().indexOfValue("yes");
            if (yesIdx >= 0) {
                for (int i = 0; i < data.numInstances(); i++) {
                    if (data.instance(i).isMissing(clsIdx)) { y[i] = Double.NaN; continue; }
                    y[i] = (int) data.instance(i).classValue() == yesIdx ? 1.0 : 0.0;
                }
            } else if (data.classAttribute().numValues() == 2) {
                int positiveIdx = 1; // convenzione: il secondo valore è "1"
                for (int i = 0; i < data.numInstances(); i++) {
                    if (data.instance(i).isMissing(clsIdx)) { y[i] = Double.NaN; continue; }
                    y[i] = (int) data.instance(i).classValue() == positiveIdx ? 1.0 : 0.0;
                }
            } else {
                throw new IllegalStateException("Classe nominale non binaria e senza valore 'yes': " +
                        data.classAttribute());
            }
        } else if (data.classAttribute().isNumeric()) {
            for (int i = 0; i < data.numInstances(); i++) {
                if (data.instance(i).isMissing(clsIdx)) { y[i] = Double.NaN; continue; }
                y[i] = data.instance(i).classValue() >= 0.5 ? 1.0 : 0.0;
            }
        } else {
            throw new IllegalStateException("Tipo attributo classe non gestito: " + data.classAttribute());
        }

        // 3) Calcola correlazione di Pearson per ogni feature NUMERICA != classe
        java.util.List<WekaClassifier.FeatureScore> scores = new java.util.ArrayList<>();

        for (int a = 0; a < data.numAttributes(); a++) {
            if (a == clsIdx) continue;
            if (!data.attribute(a).isNumeric()) continue; // considera solo feature numeriche

            // Estrai coppie valide (x_i, y_i) senza missing
            java.util.List<Double> xs = new java.util.ArrayList<>();
            java.util.List<Double> ys = new java.util.ArrayList<>();
            for (int i = 0; i < data.numInstances(); i++) {
                if (data.instance(i).isMissing(a) || Double.isNaN(y[i])) continue;
                xs.add(data.instance(i).value(a));
                ys.add(y[i]);
            }
            int n = xs.size();
            if (n < 3) continue; // troppo pochi punti per una correlazione sensata

            // Calcolo Pearson r
            double meanX = xs.stream().mapToDouble(d -> d).average().orElse(Double.NaN);
            double meanY = ys.stream().mapToDouble(d -> d).average().orElse(Double.NaN);

            double num = 0.0, denX = 0.0, denY = 0.0;
            for (int k = 0; k < n; k++) {
                double dx = xs.get(k) - meanX;
                double dy = ys.get(k) - meanY;
                num += dx * dy;
                denX += dx * dx;
                denY += dy * dy;
            }
            double denom = Math.sqrt(denX) * Math.sqrt(denY);
            if (denom == 0.0) continue;

            double r = num / denom; // in [-1, 1]
            scores.add(new WekaClassifier.FeatureScore(data.attribute(a).name(), a, r));
        }

        // 4) Ordina per |correlazione| decrescente
        scores.sort((p, q) -> Double.compare(Math.abs(q.score), Math.abs(p.score)));

        // Applica topN se richiesto
        if (topN > 0 && topN < scores.size()) {
            scores = new java.util.ArrayList<>(scores.subList(0, topN));
        }

        // 5) Salva CSV in OTHERFILES/<proj>_featuresCorrelationWithBuggyness.csv
        FileCSVGenerator csv = new FileCSVGenerator("src/main/resources/", projName);
        csv.saveFeaturesCorrelationRanking(scores);
    }

    private Evaluation crossValOnTrain(Classifier base, Instances train, int folds, long seed) throws Exception {
        Evaluation cv = new Evaluation(train);
        // stratifica automaticamente se la classe è nominale
        cv.crossValidateModel(base, train, folds, new Random(seed));
        return cv;
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
            if (nm != null && nm.toLowerCase().contains("LOC")) return i;
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
