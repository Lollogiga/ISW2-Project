package it.project.controllers;


import weka.attributeSelection.GreedyStepwise;
import weka.attributeSelection.WrapperSubsetEval;
import weka.classifiers.Classifier;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.functions.Logistic;
import weka.classifiers.meta.FilteredClassifier;
import weka.classifiers.trees.J48;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.Instances;
import weka.core.SelectedTag;
import weka.core.converters.ConverterUtils.DataSource;
import weka.filters.supervised.attribute.AttributeSelection;
import weka.filters.unsupervised.attribute.Remove;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PredictionPipeline {
    private static final Logger LOG = Logger.getLogger(PredictionPipeline.class.getName());

    private final String baseDir;     // es. "src/main/resources/"
    private final String projectName; // es. "BookKeeper"
    private final double corrThreshold; // es. 0.80
    private final boolean useJ48InsteadOfNaiveBayes; // se true usa J48 al posto di NB
    private final long seed = 42L;

    public PredictionPipeline(String baseDir, String projectName, double corrThreshold, boolean useJ48InsteadOfNaiveBayes) {
        this.baseDir = baseDir.endsWith(File.separator) ? baseDir : baseDir + File.separator;
        this.projectName = projectName;
        this.corrThreshold = corrThreshold;
        this.useJ48InsteadOfNaiveBayes = useJ48InsteadOfNaiveBayes;
    }

    public void run() {
        try {
            Path root = Paths.get(baseDir, projectName.toLowerCase());
            Path trainDir = root.resolve("training/ARFF");
            Path testDir  = root.resolve("testing/ARFF");
            Path outDir   = root.resolve("acumeResult");
            Files.createDirectories(outDir);

            // Detect iterations: <proj>_training_iter_<i>.arff
            List<Integer> iters = detectIterations(trainDir);
            if (iters.isEmpty()) {
                LOG.warning("Nessun ARFF di training trovato in " + trainDir);
                return;
            }

            // Modelli
            List<Classifier> models = new ArrayList<>();
            models.add(makeRF());
            models.add(new Logistic());
            models.add(useJ48InsteadOfNaiveBayes ? new J48() : new NaiveBayes());

            // Writers: uno "dettaglio per iterazione" e uno "aggregato finale"
            Path perIterCsv = outDir.resolve(projectName + "_per_iteration.csv");
            Path summaryCsv = outDir.resolve(projectName + "_summary.csv");
            try (BufferedWriter perIter = Files.newBufferedWriter(perIterCsv)) {
                perIter.write("iter,scenario,model,F1,MCC,AUC,Accuracy,Precision_yes,Recall_yes,TP,FP,TN,FN,kept_after_corr,kept_after_fs");
                perIter.newLine();

                // Per salvare feature selezionate e aggregare frequenze
                Path featDir = outDir.resolve("selected_features");
                Files.createDirectories(featDir);
                Map<String, Integer> fsFreqForward = new HashMap<>();
                Map<String, Integer> fsFreqBackward = new HashMap<>();

                // Collezioniamo metriche per aggregato
                Map<String, List<double[]>> metricsByScenarioModel = new LinkedHashMap<>();
                // key = scenario|model, value list of [F1,MCC,AUC,ACC,Prec,Rec]

                for (int iter : iters) {
                    String trPath = trainDir.resolve(projectName + "_training_iter_" + iter + ".arff").toString();
                    String tePath = testDir.resolve(projectName + "_testing_iter_" + iter + ".arff").toString();
                    if (!Files.exists(Paths.get(tePath))) {
                        LOG.log(Level.WARNING, "Testing mancante per iter {0}: {1}", new Object[]{iter, tePath});
                        continue;
                    }

                    Instances train = loadArff(trPath);
                    Instances test  = loadArff(tePath);
                    train.setClassIndex(train.numAttributes()-1);
                    test.setClassIndex(test.numAttributes()-1);

                    // === Scenario 1: baseline (nessun filtro) ===
                    for (Classifier base : models) {
                        Evaluation ev = evalPlain(train, test, base);
                        writePerIter(perIter, iter, "baseline", base, ev, train.numAttributes()-1, train.numAttributes()-1, test);
                        collect(metricsByScenarioModel, key(base,"baseline"), ev, test);
                    }

                    // === Scenario 2: correlation filter (rho>|thr|) ===
                    Instances trCorr = train, teCorr = test;
                    int keptAfterCorr;
                    {
                        KeepSet keep = computeCorrelationKeepSet(train, corrThreshold);
                        Instances[] pair = applyKeepSubset(train, test, keep.indices0);
                        trCorr = pair[0]; teCorr = pair[1];
                        keptAfterCorr = trCorr.numAttributes()-1;
                    }
                    for (Classifier base : models) {
                        Evaluation ev = evalPlain(trCorr, teCorr, base);
                        writePerIter(perIter, iter, "corr_only", base, ev, keptAfterCorr, trCorr.numAttributes()-1, teCorr);
                        collect(metricsByScenarioModel, key(base,"corr_only"), ev, teCorr);
                    }

                    // === Scenario 3: FS Forward / Backward (WrapperSubsetEval AUC) ===
                    for (boolean backward : new boolean[]{false, true}) {
                        String scen = backward ? "fs_backward" : "fs_forward";
                        AttributeSelection fs = buildWrapperFsAuc(backward);
                        // per log: quante feature selezionate (standalone)
                        int keptFs = estimateSelectedCount(train, backward);
                        // salva lista feature selezionate su file
                        saveSelectedFeatureList(
                                featDir,
                                iter,
                                backward ? "backward" : "forward",
                                standaloneSelectedNames(train, backward)
                        );
                        // aggiorna frequenze
                        bumpFreq(backward ? fsFreqBackward : fsFreqForward, standaloneSelectedNames(train, backward));

                        for (Classifier base : models) {
                            Evaluation ev = evalWithFilter(train, test, base, fs);
                            writePerIter(perIter, iter, scen, base, ev, train.numAttributes()-1, keptFs, test);
                            collect(metricsByScenarioModel, key(base,scen), ev, test);
                        }
                    }

                    // === Scenario 4: Corr + FS (Forward/Backward) ===
                    for (boolean backward : new boolean[]{false, true}) {
                        String scen = backward ? "corr_fs_backward" : "corr_fs_forward";
                        AttributeSelection fs = buildWrapperFsAuc(backward);
                        int keptFs = estimateSelectedCount(trCorr, backward);

                        for (Classifier base : models) {
                            Evaluation ev = evalWithFilter(trCorr, teCorr, base, fs);
                            writePerIter(perIter, iter, scen, base, ev, keptAfterCorr, keptFs, teCorr);
                            collect(metricsByScenarioModel, key(base,scen), ev, teCorr);
                        }
                    }
                }

                // === Aggregato finale ===
                try (BufferedWriter sum = Files.newBufferedWriter(summaryCsv)) {
                    sum.write("scenario,model,mean_F1,std_F1,mean_MCC,std_MCC,mean_AUC,std_AUC,mean_Acc,std_Acc,mean_Prec,std_Prec,mean_Rec,std_Rec,n_iters");
                    sum.newLine();
                    for (var e : metricsByScenarioModel.entrySet()) {
                        String[] parts = e.getKey().split("\\|");
                        String scenario = parts[0];
                        String model = parts[1];

                        Stats f1 = new Stats(), mcc=new Stats(), auc=new Stats(), acc=new Stats(), prec=new Stats(), rec=new Stats();
                        for (double[] row : e.getValue()) {
                            f1.add(row[0]); mcc.add(row[1]); auc.add(row[2]);
                            acc.add(row[3]); prec.add(row[4]); rec.add(row[5]);
                        }
                        sum.write(String.join(",",
                                scenario, model,
                                fmt(f1.mean()), fmt(f1.std()),
                                fmt(mcc.mean()), fmt(mcc.std()),
                                fmt(auc.mean()), fmt(auc.std()),
                                fmt(acc.mean()), fmt(acc.std()),
                                fmt(prec.mean()), fmt(prec.std()),
                                fmt(rec.mean()), fmt(rec.std()),
                                Integer.toString(f1.n)
                        ));
                        sum.newLine();
                    }
                }

                // === Frequenze aggregate feature selezionate ===
                writeFsFrequency(outDir.resolve("fs_forward_feature_frequency.tsv"), fsFreqForward);
                writeFsFrequency(outDir.resolve("fs_backward_feature_frequency.tsv"), fsFreqBackward);
            }

            LOG.info("Pipeline conclusa. CSV generati in: " + outDir);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Errore PredictionPipeline", e);
        }
    }

    /* ========================= Core scenari ========================= */

    private static Evaluation evalPlain(Instances train, Instances test, Classifier base) throws Exception {
        Classifier c = AbstractClassifier.makeCopy(base);
        c.buildClassifier(train);
        Evaluation ev = new Evaluation(train);
        ev.evaluateModel(c, test);
        return ev;
    }

    private static Evaluation evalWithFilter(Instances train, Instances test, Classifier base, AttributeSelection fs) throws Exception {
        FilteredClassifier pipe = new FilteredClassifier();
        pipe.setFilter(fs);
        pipe.setClassifier(base);
        pipe.buildClassifier(train);
        Evaluation ev = new Evaluation(train);
        ev.evaluateModel(pipe, test);
        return ev;
    }

    /* ========================= Wrapper FS (AUC) ========================= */

    private AttributeSelection buildWrapperFsAuc(boolean backward) {
        WrapperSubsetEval eval = new WrapperSubsetEval();
        // Classifier usato nel wrapper per stimare il merito del subset (veloce e robusto)
        eval.setClassifier(new Logistic());
        eval.setFolds(5);
        eval.setSeed((int) seed);
        // scegli AUC come metrica di selezione
        eval.setEvaluationMeasure(new SelectedTag(WrapperSubsetEval.EVAL_AUC, WrapperSubsetEval.TAGS_EVALUATION)); // :contentReference[oaicite:0]{index=0}

        GreedyStepwise search = new GreedyStepwise();
        search.setSearchBackwards(backward);

        AttributeSelection fs = new AttributeSelection();
        fs.setEvaluator(eval);
        fs.setSearch(search);
        return fs;
    }

    /* ========================= Correlation filter per-iter ========================= */

    private static class KeepSet { Set<Integer> indices0 = new LinkedHashSet<>(); }

    /** Calcola Spearman pairwise su train e restituisce set di feature da TENERE (greedy, ordinamento alfabetico). */
    private KeepSet computeCorrelationKeepSet(Instances train, double thr) {
        int classIdx = train.classIndex();
        List<Integer> idx = numericFeatureIndices(train);
        // calcola matrice rho (solo numeriche)
        double[][] rho = new double[idx.size()][idx.size()];
        for (int i=0;i<idx.size();i++){
            rho[i][i]=1.0;
            for (int j=i+1;j<idx.size();j++){
                rho[i][j] = spearman(train, idx.get(i), idx.get(j));
                rho[j][i] = rho[i][j];
            }
        }
        // greedy deterministico per nome
        idx.sort(Comparator.comparing(i -> train.attribute(i).name()));
        Set<Integer> keep = new LinkedHashSet<>();
        for (int a : idx) {
            int posA = indexOf(idx, a);
            boolean tooCorr = false;
            for (int k : keep) {
                int posK = indexOf(idx, k);
                if (Math.abs(rho[posA][posK]) > thr) { tooCorr = true; break; }
            }
            if (!tooCorr) keep.add(a);
        }
        KeepSet ks = new KeepSet();
        ks.indices0.addAll(keep);
        ks.indices0.add(classIdx); // la classe sempre
        return ks;
    }

    private static Instances[] applyKeepSubset(Instances train, Instances test, Set<Integer> keep0) throws Exception {
        String keepIdx = toIndexList1Based(keep0);
        Remove rm = new Remove();
        rm.setInvertSelection(true);
        rm.setAttributeIndices(keepIdx);
        rm.setInputFormat(train);
        Instances tr = weka.filters.Filter.useFilter(train, rm);
        rm.setInputFormat(test);
        Instances te = weka.filters.Filter.useFilter(test, rm);
        tr.setClassIndex(tr.numAttributes()-1);
        te.setClassIndex(te.numAttributes()-1);
        return new Instances[]{tr, te};
    }

    /* ========================= Utilities ========================= */

    private RandomForest makeRF() {
        RandomForest rf = new RandomForest();
        rf.setNumIterations(200);
        rf.setSeed((int) seed);
        rf.setNumExecutionSlots(Math.max(1, Runtime.getRuntime().availableProcessors()-1));
        return rf;
    }

    private static Instances loadArff(String path) throws Exception {
        DataSource ds = new DataSource(path);
        Instances data = ds.getDataSet();
        if (data.classIndex() < 0) data.setClassIndex(data.numAttributes()-1);
        return data;
    }

    private static List<Integer> detectIterations(Path trainArffDir) throws IOException {
        List<Integer> iters = new ArrayList<>();
        if (!Files.exists(trainArffDir)) return iters;
        Pattern p = Pattern.compile(".*_training_iter_(\\d+)\\.arff");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(trainArffDir, "*.arff")) {
            for (Path f : ds) {
                var m = p.matcher(f.getFileName().toString());
                if (m.matches()) iters.add(Integer.parseInt(m.group(1)));
            }
        }
        Collections.sort(iters);
        return iters;
    }

    private static List<Integer> numericFeatureIndices(Instances data) {
        int classIdx = data.classIndex();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i == classIdx) continue;
            Attribute a = data.attribute(i);
            if (a.isNumeric()) idx.add(i);
        }
        return idx;
    }

    /** Spearman ρ tra due attributi numerici (pairwise deletion). */
    private static double spearman(Instances data, int idxA, int idxB) {
        double[] a = columnIgnoringMissingPairwise(data, idxA, idxB);
        double[] b = columnIgnoringMissingPairwiseOther(data, idxA, idxB);
        if (a.length < 2) return 0.0;
        return pearson(ranks(a), ranks(b));
    }

    private static double[] columnIgnoringMissingPairwise(Instances d, int idxA, int idxB) {
        double[] tmp = new double[d.numInstances()];
        int k=0;
        for (int i=0;i<d.numInstances();i++){
            var inst = d.instance(i);
            if (!inst.isMissing(idxA) && !inst.isMissing(idxB)) tmp[k++] = inst.value(idxA);
        }
        return Arrays.copyOf(tmp, k);
    }
    private static double[] columnIgnoringMissingPairwiseOther(Instances d, int idxA, int idxB) {
        double[] tmp = new double[d.numInstances()];
        int k=0;
        for (int i=0;i<d.numInstances();i++){
            var inst = d.instance(i);
            if (!inst.isMissing(idxA) && !inst.isMissing(idxB)) tmp[k++] = inst.value(idxB);
        }
        return Arrays.copyOf(tmp, k);
    }
    private static double[] ranks(double[] x) {
        int n=x.length;
        Integer[] ord = new Integer[n];
        for (int i=0;i<n;i++) ord[i]=i;
        Arrays.sort(ord, Comparator.comparingDouble(i -> x[i]));
        double[] r = new double[n];
        int i=0;
        while (i<n) {
            int j=i;
            while (j+1<n && x[ord[j+1]]==x[ord[i]]) j++;
            double rank = (i + j)/2.0 + 1.0;
            for (int k=i;k<=j;k++) r[ord[k]] = rank;
            i=j+1;
        }
        return r;
    }
    private static double pearson(double[] a, double[] b) {
        int n=a.length;
        double ma=0, mb=0;
        for (int i=0;i<n;i++){ ma+=a[i]; mb+=b[i]; }
        ma/=n; mb/=n;
        double num=0, da=0, db=0;
        for (int i=0;i<n;i++){
            double xa=a[i]-ma, xb=b[i]-mb;
            num+=xa*xb; da+=xa*xa; db+=xb*xb;
        }
        double den=Math.sqrt(da*db);
        return den==0?0:(num/den);
    }
    private static int indexOf(List<Integer> list, int v){ for (int i=0;i<list.size();i++) if (list.get(i)==v) return i; return -1; }
    private static String toIndexList1Based(Set<Integer> idx0) {
        List<Integer> list = new ArrayList<>(idx0);
        Collections.sort(list);
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<list.size();i++){ if (i>0) sb.append(","); sb.append(list.get(i)+1); }
        return sb.toString();
    }

    private static void writePerIter(BufferedWriter perIter, int iter, String scenario, Classifier base, Evaluation ev,
                                     int keptAfterCorr, int keptAfterFs, Instances test) throws IOException {
        int pos = test.classAttribute().indexOfValue("yes");
        if (pos < 0) pos = 0;

        double precision = ev.precision(pos);
        double recall    = ev.recall(pos);
        double f1        = ev.fMeasure(pos);
        double auc       = ev.areaUnderROC(pos);
        double acc       = 1.0 - ev.errorRate();
        double[][] cm = ev.confusionMatrix();
        long tn = Math.round(cm[0][0]);
        long fp = Math.round(cm[0][1]);
        long fn = Math.round(cm[1][0]);
        long tp = Math.round(cm[1][1]);
        double mcc = ev.matthewsCorrelationCoefficient(pos);

        perIter.write(String.join(",",
                Integer.toString(iter),
                scenario,
                base.getClass().getSimpleName(),
                fmt(f1), fmt(mcc), fmt(auc), fmt(acc), fmt(precision), fmt(recall),
                Long.toString(tp), Long.toString(fp), Long.toString(tn), Long.toString(fn),
                Integer.toString(keptAfterCorr),
                Integer.toString(keptAfterFs)
        ));
        perIter.newLine();
    }

    private static void collect(Map<String, List<double[]>> map, String key, Evaluation ev, Instances test) {
        int pos = test.classAttribute().indexOfValue("yes");
        if (pos < 0) pos = 0;
        double[] row = new double[]{
                ev.fMeasure(pos), ev.matthewsCorrelationCoefficient(pos), ev.areaUnderROC(pos),
                (1.0-ev.errorRate()), ev.precision(pos), ev.recall(pos)
        };
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }

    private static String key(Classifier base, String scenario) {
        return scenario + "|" + base.getClass().getSimpleName();
    }

    /* ===== selected features report & frequencies ===== */

    private static int estimateSelectedCount(Instances train, boolean backward) throws Exception {
        var sel = new weka.attributeSelection.AttributeSelection();
        var eval = new WrapperSubsetEval();
        eval.setClassifier(new Logistic());
        eval.setFolds(5);
        eval.setSeed(1);
        eval.setEvaluationMeasure(new SelectedTag(WrapperSubsetEval.EVAL_AUC, WrapperSubsetEval.TAGS_EVALUATION)); // :contentReference[oaicite:1]{index=1}
        var gs = new GreedyStepwise();
        gs.setSearchBackwards(backward);
        sel.setEvaluator(eval);
        sel.setSearch(gs);
        sel.SelectAttributes(train);
        int[] idx = sel.selectedAttributes();
        int classIdx = train.classIndex();
        int count=0; for (int k : idx) if (k!=classIdx) count++;
        return count;
    }

    private static List<String> standaloneSelectedNames(Instances train, boolean backward) throws Exception {
        var sel = new weka.attributeSelection.AttributeSelection();
        var eval = new WrapperSubsetEval();
        eval.setClassifier(new Logistic());
        eval.setFolds(5);
        eval.setSeed(1);
        eval.setEvaluationMeasure(new SelectedTag(WrapperSubsetEval.EVAL_AUC, WrapperSubsetEval.TAGS_EVALUATION)); // :contentReference[oaicite:2]{index=2}
        var gs = new GreedyStepwise();
        gs.setSearchBackwards(backward);
        sel.setEvaluator(eval);
        sel.setSearch(gs);
        sel.SelectAttributes(train);
        int[] idx = sel.selectedAttributes();
        List<String> names = new ArrayList<>(idx.length);
        for (int k : idx) names.add(train.attribute(k).name());
        return names;
    }


    private static void saveSelectedFeatureList(Path featDir, int iter, String dirKey, List<String> names) throws IOException {
        Path f = featDir.resolve(String.format("iter_%d_%s.txt", iter, dirKey));
        try (BufferedWriter bw = Files.newBufferedWriter(f)) {
            bw.write("# " + dirKey + " - WrapperSubsetEval(AUC) + GreedyStepwise");
            bw.newLine();
            bw.write("index\tname");
            bw.newLine();
            for (int i = 0; i < names.size(); i++) {
                bw.write(i + "\t" + names.get(i));
                bw.newLine();
            }
        }
    }

    private static void bumpFreq(Map<String,Integer> freq, List<String> names) {
        for (String n : names) freq.merge(n, 1, Integer::sum);
    }
    private static void writeFsFrequency(Path out, Map<String,Integer> freq) throws IOException {
        List<Map.Entry<String,Integer>> rows = new ArrayList<>(freq.entrySet());
        rows.sort((a,b)->Integer.compare(b.getValue(), a.getValue()));
        try (BufferedWriter bw = Files.newBufferedWriter(out)) {
            bw.write("feature\tcount"); bw.newLine();
            for (var e : rows) { bw.write(e.getKey() + "\t" + e.getValue()); bw.newLine(); }
        }
    }

    /* ===== stats & fmt ===== */
    private static class Stats { double sum=0, sumsq=0; int n=0; void add(double v){ sum+=v; sumsq+=v*v; n++; }
        double mean(){ return n==0?0:sum/n; }
        double std(){ if (n<=1) return 0; double m=mean(); return Math.sqrt(Math.max(0, sumsq/n - m*m)); }
    }
    private static String fmt(double v){ return String.format(Locale.US, "%.4f", v); }
}
