package it.project.controllers;


import it.project.utils.FileCSVGenerator;
import weka.classifiers.Classifier;
import weka.classifiers.AbstractClassifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.converters.ConverterUtils.DataSource;

import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SmellImpactAnalyzer {

    private static final Logger LOG = Logger.getLogger(SmellImpactAnalyzer.class.getName());

    /*DatasetA = Dataset completo*/
    /*DatasetB+ = porzione di A contente solo righe con nSmell>0*/
    /*Dataset B = Dataset B+ in cui setto nSmell = 0*/
    /*Dataset C = Porzione di A contenente solo righe con nSmell = 0*/
    // Dentro: it.project.controllers.SmellImpactAnalyzer

    // Overload per retro-compatibilità (nessun salvataggio)
    public void run(String arffPath, Classifier prototype, String smellAttrNameOrNull) throws Exception {
        run(arffPath, prototype, smellAttrNameOrNull, null);
    }

    // Overload completo con salvataggi via FileCSVGenerator
    public void run(String arffPath,
                    Classifier prototype,
                    String smellAttrNameOrNull,
                    it.project.utils.FileCSVGenerator csvGen) throws Exception {

        // 1) Carica A e imposta classe
        Instances datasetA = loadArff(arffPath);
        ensureClassSet(datasetA);

        // 2) Trova indice nSmells (forzato o inferito)
        final int smellIdx = (smellAttrNameOrNull != null)
                ? indexOfAttrCaseInsensitive(datasetA, smellAttrNameOrNull)
                : inferSmellIndex(datasetA);
        if (smellIdx < 0) {
            throw new IllegalArgumentException("Attributo 'NSmells' non trovato (usa smellAttrNameOrNull per forzarlo).");
        }
        LOG.log(Level.INFO, "Smell attribute: {0} (index {1})",
                new Object[]{datasetA.attribute(smellIdx).name(), smellIdx});

        // 3) Costruisci i sotto-dataset
        Instances datasetBPlus = filterBySmell(datasetA, smellIdx, true);   // NSmells > 0
        Instances datasetC     = filterBySmell(datasetA, smellIdx, false);  // NSmells = 0
        Instances datasetB     = setSmellToZero(datasetBPlus, smellIdx);    // copia di B+ con NSmells=0

        // 3.b) Salva SUBITO le versioni “pure” se richiesto
        if (csvGen != null) {
            csvGen.saveInstancesToOtherFiles("Bplus", datasetBPlus);
            csvGen.saveInstancesToOtherFiles("B",      datasetB);
            csvGen.saveInstancesToOtherFiles("C",      datasetC);
        }

        // 4) Allena il modello su A
        Classifier model = AbstractClassifier.makeCopy(prototype);
        model.buildClassifier(datasetA);

        // 5) Calcola metriche sintetiche su A, B+, B, C
        PredictionStats statsA     = predictStats(datasetA, model);
        PredictionStats statsBPlus = predictStats(datasetBPlus, model);
        PredictionStats statsB     = predictStats(datasetB, model);
        PredictionStats statsC     = predictStats(datasetC, model);

        // 6) Effetto “prevenibile” (B+ vs B)
        PreventableStats prevent = compareBPlusVsB(datasetBPlus, datasetB, model);

        // 7) Stampa riepiloghi
        printSummaryTable(
                statsA, statsB, statsBPlus, statsC, prevent,
                datasetA.attribute(datasetA.classIndex()).value(positiveIndex(datasetA))
        );
        // Tabella compatta A/E come nello screenshot
        printCompactAETable(statsA, statsBPlus, statsB, statsC);

        // 8) Salva le versioni con colonna di predizione (dopo l’addestramento)
        if (csvGen != null) {
            saveWithPred(csvGen, "Bplus_with_pred", datasetBPlus, model);
            saveWithPred(csvGen, "B_with_pred",     datasetB,     model);
            saveWithPred(csvGen, "C_with_pred",     datasetC,     model);
        }
    }

    /* ==================== CORE ==================== */

    private void saveWithPred(FileCSVGenerator csvGen, String baseName, Instances data, Classifier model) throws Exception {
        int n = data.numInstances();
        String[] predLabels = new String[n];

        for (int i = 0; i < n; i++) {
            double[] dist = model.distributionForInstance(data.instance(i));
            int yhat = Utils.maxIndex(dist);
            predLabels[i] = data.classAttribute().value(yhat);
        }

        csvGen.saveInstancesToOtherFilesWithPred(baseName, data, "isBuggy_pred", predLabels);
    }

    private Instances loadArff(String path) throws Exception {
        DataSource ds = new DataSource(path);
        return ds.getDataSet();
    }

    private void ensureClassSet(Instances data) {
        if (data.classIndex() < 0) {
            data.setClassIndex(data.numAttributes() - 1);
            LOG.log(Level.INFO, "Classe non impostata: uso ultima colonna come classe: {0}",
                    data.attribute(data.numAttributes() - 1).name());
        }
        if (!data.classAttribute().isNominal() || data.classAttribute().numValues() != 2) {
            LOG.warning("Attenzione: classe attesa nominale binaria {no,yes}. Verifica l'ARFF.");
        }
    }

    private int indexOfAttrCaseInsensitive(Instances data, String lowerOrAnyName) {
        String needle = lowerOrAnyName.toLowerCase(Locale.ROOT).trim();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (data.attribute(i).name().toLowerCase(Locale.ROOT).equals(needle)) return i;
        }
        return -1;
    }

    private int inferSmellIndex(Instances data) {
        int fallback = -1;
        for (int i = 0; i < data.numAttributes(); i++) {
            String n = data.attribute(i).name().toLowerCase(Locale.ROOT);
            if (n.contains("smell")) {
                // preferisci varianti con "n", "num"
                if (n.contains("nsmell") || n.contains("numsmell") || n.contains("n_smell") || n.contains("num_smell"))
                    return i;
                fallback = i;
            }
        }
        return fallback;
    }

    private Instances filterBySmell(Instances src, int smellIdx, boolean greaterThanZero) {
        Instances out = new Instances(src, 0);
        for (int i = 0; i < src.numInstances(); i++) {
            Instance inst = src.instance(i);
            double v = inst.value(smellIdx);
            if (greaterThanZero ? v > 0.0 : v == 0.0) {
                out.add((Instance) inst.copy());
            }
        }
        out.setClassIndex(src.classIndex());
        return out;
    }

    private Instances setSmellToZero(Instances src, int smellIdx) {
        Instances out = new Instances(src);
        for (int i = 0; i < out.numInstances(); i++) {
            out.instance(i).setValue(smellIdx, 0.0);
        }
        out.setClassIndex(src.classIndex());
        return out;
    }

    private int positiveIndex(Instances data) {
        // preferisci il valore "yes" (case-insensitive). Se non c'è, usa l'indice 1.
        for (int k = 0; k < data.classAttribute().numValues(); k++) {
            if ("yes".equalsIgnoreCase(data.classAttribute().value(k))) return k;
        }
        return Math.min(1, data.classAttribute().numValues() - 1);
    }

    private PredictionStats predictStats(Instances data, Classifier model) throws Exception {
        int clsYes = positiveIndex(data);

        long actualYes = 0;
        long predYes   = 0;
        double sumPYes = 0.0;

        for (int i = 0; i < data.numInstances(); i++) {
            Instance x = data.instance(i);
            if ((int) x.classValue() == clsYes) actualYes++;
            double[] dist = model.distributionForInstance(x);
            int yhat = Utils.maxIndex(dist);
            if (yhat == clsYes) predYes++;
            sumPYes += dist[clsYes];
        }
        return new PredictionStats(
                data.relationName(),
                data.numInstances(),
                actualYes,
                predYes,
                sumPYes / Math.max(1, data.numInstances())
        );
    }

    private PreventableStats compareBPlusVsB(Instances datasetBPlus, Instances datasetB, Classifier model) throws Exception {
        if (datasetBPlus.numInstances() != datasetB.numInstances()) {
            throw new IllegalStateException("datasetB+ e datasetB devono avere lo stesso numero di istanze.");
        }
        if (datasetBPlus.numInstances() == 0) {
            return new PreventableStats(0, 0, 0, 0, 0.0, 0.0);
        }

        int clsYes = positiveIndex(datasetBPlus);
        long flipsYesToNo = 0;    // prevenuti
        long flipsNoToYes = 0;    // effetti collaterali
        long predYesBplus = 0;

        for (int i = 0; i < datasetBPlus.numInstances(); i++) {
            Instance xPlus = datasetBPlus.instance(i);
            Instance xZero = datasetB.instance(i);

            int yPlus = Utils.maxIndex(model.distributionForInstance(xPlus));
            int yZero = Utils.maxIndex(model.distributionForInstance(xZero));

            if (yPlus == clsYes) predYesBplus++;
            if (yPlus == clsYes && yZero != clsYes) flipsYesToNo++;
            if (yPlus != clsYes && yZero == clsYes) flipsNoToYes++;
        }

        double propOverPredBuggy = (predYesBplus == 0) ? 0.0 : (double) flipsYesToNo / predYesBplus;
        double propOverBplus     = (datasetBPlus.numInstances() == 0) ? 0.0 : (double) flipsYesToNo / datasetBPlus.numInstances();

        return new PreventableStats(
                datasetBPlus.numInstances(),
                predYesBplus,
                flipsYesToNo,
                flipsNoToYes,
                propOverPredBuggy,
                propOverBplus
        );
    }

    private void printSummaryTable(PredictionStats datasetA, PredictionStats datasetB, PredictionStats datasetBPlus,
                                   PredictionStats datasetC, PreventableStats preventableStats, String positiveLabel) {

        final String nl = System.lineSeparator();
        final String sep = "---------------------------------------------------------------------";

        final String fmtHead = "%-8s | %8s | %12s | %14s | %10s%n";
        final String fmtRow  = "%-8s | %8d | %12d | %14d | %10.4f%n";

        StringBuilder sb = new StringBuilder(512);
        sb.append(nl);
        sb.append(String.format(fmtHead, "Dataset", "Size", "Actual(" + positiveLabel + ")",
                "Pred(" + positiveLabel + ")", "Avg preventableStats(yes)"));
        sb.append(sep).append(nl);

        sb.append(String.format(Locale.US, fmtRow, "datasetA",  datasetA.size,     datasetA.actualYes,  datasetA.predYes,  datasetA.avgProbYes));
        sb.append(String.format(Locale.US, fmtRow, "datasetB+", datasetBPlus.size, datasetBPlus.actualYes, datasetBPlus.predYes, datasetBPlus.avgProbYes));
        sb.append(String.format(Locale.US, fmtRow, "datasetB",  datasetB.size,     datasetB.actualYes,  datasetB.predYes,  datasetB.avgProbYes));
        sb.append(String.format(Locale.US, fmtRow, "datasetC",  datasetC.size,     datasetC.actualYes,  datasetC.predYes,  datasetC.avgProbYes));
        sb.append(nl);

        sb.append("Effect of setting NSmells=0 on datasetB+ (\"preventable bugs\"):").append(nl);
        sb.append(String.format(Locale.US, " - datasetB+ size:                         %d%n", preventableStats.bplusSize));
        sb.append(String.format(Locale.US, " - Predicted buggy in datasetB+:           %d%n", preventableStats.predYesInBplus));
        sb.append(String.format(Locale.US, " - FLIPS yes->no (prevented):       %d%n", preventableStats.flipsYesToNo));
        sb.append(String.format(Locale.US, " - FLIPS no->yes (side-effects):    %d%n", preventableStats.flipsNoToYes));
        sb.append(String.format(Locale.US, " - Prevented / PredBuggy(datasetB+):       %.4f%n", preventableStats.propOverPredBuggy));
        sb.append(String.format(Locale.US, " - Prevented / datasetB+ size:             %.4f%n", preventableStats.propOverBplus));

        LOG.log(Level.INFO, sb::toString);
    }

    /* ================ DTO interni ================ */

    private static class PredictionStats {
        final String name;
        final int size;
        final long actualYes;
        final long predYes;
        final double avgProbYes;

        PredictionStats(String name, int size, long actualYes, long predYes, double avgProbYes) {
            this.name = Objects.requireNonNullElse(name, "");
            this.size = size;
            this.actualYes = actualYes;
            this.predYes = predYes;
            this.avgProbYes = avgProbYes;
        }
    }

    private static class PreventableStats {
        final int bplusSize;
        final long predYesInBplus;
        final long flipsYesToNo;   // “prevenuti”
        final long flipsNoToYes;   // “collaterali”
        final double propOverPredBuggy; // prevenuti / pred yes in B+
        final double propOverBplus;     // prevenuti / size B+

        PreventableStats(int bplusSize, long predYesInBplus, long flipsYesToNo, long flipsNoToYes,
                         double propOverPredBuggy, double propOverBplus) {
            this.bplusSize = bplusSize;
            this.predYesInBplus = predYesInBplus;
            this.flipsYesToNo = flipsYesToNo;
            this.flipsNoToYes = flipsNoToYes;
            this.propOverPredBuggy = propOverPredBuggy;
            this.propOverBplus = propOverBplus;
        }
    }


    private void printCompactAETable(PredictionStats sA,
                                     PredictionStats sBplus,
                                     PredictionStats sB,
                                     PredictionStats sC) {
        final String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(256);

        // intestazione
        sb.append(nl);
        sb.append(String.format("%-10s | %12s | %12s | %12s | %12s%n",
                "", "Dataset A", "Dataset B+", "Dataset B", "Dataset C"));
        sb.append("--------------------------------------------------------------------------").append(nl);

        // riga A (Actual)
        sb.append(String.format("%-10s | %12d | %12d | %12d | %12d%n",
                "A", sA.actualYes, sBplus.actualYes, sB.actualYes, sC.actualYes));

        // riga E (Estimated)
        sb.append(String.format("%-10s | %12d | %12d | %12d | %12d%n",
                "E", sA.predYes, sBplus.predYes, sB.predYes, sC.predYes));

        LOG.log(Level.INFO, sb::toString);
    }

}
