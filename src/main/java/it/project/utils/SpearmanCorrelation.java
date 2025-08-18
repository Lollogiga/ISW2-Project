package it.project.utils;


import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Calcola Spearman su TUTTE le iterazioni (ogni training_iter_i.arff separatamente),
 * poi aggrega (media, std, min, max, n_iters) in DUE file finali:
 *   - feature_feature_spearman_summary.tsv
 *   - feature_target_spearman_summary.tsv
 *
 * Output in: src/main/resources/<projectName>/otherFiles/
 * Stampa a console la top-10 per |rho| medio (feature ↔ target).
 */
public class SpearmanCorrelation {
    private static final Logger LOG = Logger.getLogger(SpearmanCorrelation.class.getName());

    private final String projectName;

    public SpearmanCorrelation(String projectName) {
        this.projectName = projectName;
    }

    public void run() {
        try {
            String baseDir = "src/main/resources/";
            Path trainDir = Paths.get(baseDir, projectName.toLowerCase(), "training", "ARFF");
            Path outDir   = Paths.get(baseDir, projectName.toLowerCase(), "otherFiles");
            Files.createDirectories(outDir);

            List<Integer> iters = detectIterations(trainDir);
            if (iters.isEmpty()) {
                LOG.warning("Nessun ARFF di training trovato in " + trainDir);
                return;
            }

            // Aggregatori
            Map<Pair, Stats> ffAgg = new HashMap<>();     // feature↔feature
            Map<String, Stats> ftAgg = new HashMap<>();   // feature↔target

            for (int iter : iters) {
                Path trainArff = trainDir.resolve(projectName + "_training_iter_" + iter + ".arff");
                Instances data = loadArff(trainArff.toString());
                data.setClassIndex(data.numAttributes() - 1);

                List<Integer> feats = numericFeatureIndices(data);

                // --- feature-feature
                for (int i = 0; i < feats.size(); i++) {
                    for (int j = i + 1; j < feats.size(); j++) {
                        int ai = feats.get(i), aj = feats.get(j);
                        String fi = data.attribute(ai).name();
                        String fj = data.attribute(aj).name();
                        double rho = spearman(data, ai, aj);
                        ffAgg.computeIfAbsent(Pair.of(fi, fj), k -> new Stats()).add(rho);
                    }
                }

                // --- feature-target
                int yes = data.classAttribute().indexOfValue("yes");
                if (yes < 0) yes = 0;
                double[] yFull = new double[data.numInstances()];
                for (int r = 0; r < data.numInstances(); r++) {
                    yFull[r] = ((int) data.instance(r).classValue() == yes) ? 1.0 : 0.0;
                }
                for (int a : feats) {
                    String name = data.attribute(a).name();
                    double[] x = columnIgnoringMissingPairwise(data, a, data.classIndex());
                    double[] y = pairedOtherVector(data, a, data.classIndex(), yFull);
                    double rho = (x.length < 2) ? 0.0 : pearson(ranks(x), ranks(y));
                    ftAgg.computeIfAbsent(name, k -> new Stats()).add(rho);
                }
            }

            // --- scrivi file finali ---
            Path ffOut = outDir.resolve("feature_feature_spearman_summary.tsv");
            Path ftOut = outDir.resolve("feature_target_spearman_summary.tsv");
            writeFeatureFeatureSummary(ffOut, ffAgg);
            writeFeatureTargetSummary(ftOut, ftAgg);

            // --- stampa top-10 per |rho| medio (feature-target) ---
            System.out.println("=== Top-10 Feature–Target |rho| medio ===");
            ftAgg.entrySet().stream()
                    .sorted((a, b) -> Double.compare(Math.abs(b.getValue().mean()), Math.abs(a.getValue().mean())))
                    .limit(10)
                    .forEach(e -> System.out.printf(java.util.Locale.US,
                            "%-30s  mean ρ = %+.3f  (std=%.3f, min=%.3f, max=%.3f, n=%d)%n",
                            e.getKey(), e.getValue().mean(), e.getValue().std(), e.getValue().min, e.getValue().max, e.getValue().n)
                    );
            System.out.println();

            LOG.info("Spearman aggregato scritto in: " + outDir);

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Errore in SpearmanCorrelationAggregator", e);
        }
    }

    /* ================= helpers: IO & scansione ================= */

    private static Instances loadArff(String path) throws Exception {
        DataSource ds = new DataSource(path);
        Instances data = ds.getDataSet();
        if (data.classIndex() < 0) data.setClassIndex(data.numAttributes() - 1);
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

    /* ================= helpers: calcolo Spearman ================= */

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

    /** Spearman ρ tra due attributi numerici (idxA, idxB), con deletion pairwise su missing. */
    private static double spearman(Instances data, int idxA, int idxB) {
        double[] a = columnIgnoringMissingPairwise(data, idxA, idxB);
        double[] b = columnIgnoringMissingPairwiseOther(data, idxA, idxB);
        if (a.length < 2) return 0.0;
        return pearson(ranks(a), ranks(b));
    }

    private static double[] columnIgnoringMissingPairwise(Instances d, int idxA, int idxB) {
        double[] tmp = new double[d.numInstances()];
        int k = 0;
        for (int i = 0; i < d.numInstances(); i++) {
            Instance inst = d.instance(i);
            if (!inst.isMissing(idxA) && !inst.isMissing(idxB)) tmp[k++] = inst.value(idxA);
        }
        return Arrays.copyOf(tmp, k);
    }
    private static double[] columnIgnoringMissingPairwiseOther(Instances d, int idxA, int idxB) {
        double[] tmp = new double[d.numInstances()];
        int k = 0;
        for (int i = 0; i < d.numInstances(); i++) {
            Instance inst = d.instance(i);
            if (!inst.isMissing(idxA) && !inst.isMissing(idxB)) tmp[k++] = inst.value(idxB);
        }
        return Arrays.copyOf(tmp, k);
    }
    private static double[] pairedOtherVector(Instances d, int idxA, int idxClass, double[] yFull) {
        double[] tmp = new double[d.numInstances()];
        int k = 0;
        for (int i = 0; i < d.numInstances(); i++) {
            Instance inst = d.instance(i);
            if (!inst.isMissing(idxA) && !inst.isMissing(idxClass)) tmp[k++] = yFull[i];
        }
        return Arrays.copyOf(tmp, k);
    }

    /** ranks con gestione dei pari (average ranks). */
    private static double[] ranks(double[] x) {
        int n = x.length;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        Arrays.sort(ord, Comparator.comparingDouble(i -> x[i]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && x[ord[j + 1]] == x[ord[i]]) j++;
            double rank = (i + j) / 2.0 + 1.0; // 1-based
            for (int k = i; k <= j; k++) r[ord[k]] = rank;
            i = j + 1;
        }
        return r;
    }

    /** Pearson tra due vettori (stessa lunghezza). */
    private static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n; mb /= n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double xa = a[i] - ma, xb = b[i] - mb;
            num += xa * xb; da += xa * xa; db += xb * xb;
        }
        double den = Math.sqrt(da * db);
        return den == 0 ? 0.0 : (num / den);
    }

    /* ================= helpers: aggregazione & scrittura ================= */

    private static class Stats {
        double sum=0, sumsq=0, min=Double.POSITIVE_INFINITY, max=Double.NEGATIVE_INFINITY; int n=0;
        void add(double v){ sum+=v; sumsq+=v*v; min=Math.min(min,v); max=Math.max(max,v); n++; }
        double mean(){ return n==0?0:sum/n; }
        double std(){ if (n<=1) return 0; double m=mean(); return Math.sqrt(Math.max(0, sumsq/n - m*m)); }
    }

    private record Pair(String a, String b) {
        static Pair of(String x, String y) {
            return (x.compareTo(y) <= 0) ? new Pair(x,y) : new Pair(y,x);
        }
    }

    private static void writeFeatureFeatureSummary(Path out, Map<Pair, Stats> agg) throws IOException {
        // ordina per |mean| desc
        List<Map.Entry<Pair, Stats>> rows = new ArrayList<>(agg.entrySet());
        rows.sort((r1, r2) -> Double.compare(Math.abs(r2.getValue().mean()), Math.abs(r1.getValue().mean())));

        try (BufferedWriter bw = Files.newBufferedWriter(out)) {
            bw.write("feature_i\tfeature_j\tmean_rho\tstd\tmin\tmax\tn_iters");
            bw.newLine();
            for (var e : rows) {
                Stats s = e.getValue();
                bw.write(e.getKey().a + "\t" + e.getKey().b + "\t" +
                        fmt(s.mean()) + "\t" + fmt(s.std()) + "\t" +
                        fmt(s.min) + "\t" + fmt(s.max) + "\t" + s.n);
                bw.newLine();
            }
        }
    }

    private static void writeFeatureTargetSummary(Path out, Map<String, Stats> agg) throws IOException {
        // ordina per |mean| desc
        List<Map.Entry<String, Stats>> rows = new ArrayList<>(agg.entrySet());
        rows.sort((r1, r2) -> Double.compare(Math.abs(r2.getValue().mean()), Math.abs(r1.getValue().mean())));

        try (BufferedWriter bw = Files.newBufferedWriter(out)) {
            bw.write("feature\tmean_rho\tstd\tmin\tmax\tn_iters");
            bw.newLine();
            for (var e : rows) {
                Stats s = e.getValue();
                bw.write(e.getKey() + "\t" + fmt(s.mean()) + "\t" + fmt(s.std()) + "\t" +
                        fmt(s.min) + "\t" + fmt(s.max) + "\t" + s.n);
                bw.newLine();
            }
        }
    }

    private static String fmt(double v){ return String.format(Locale.US, "%.6f", v); }
}

