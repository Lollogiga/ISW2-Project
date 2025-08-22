package it.project.utils;

import weka.core.Attribute;
import weka.core.Instances;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;
import weka.filters.unsupervised.attribute.RemoveUseless;
import weka.filters.unsupervised.attribute.Reorder;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileARFFGenerator {
    private static final Logger LOG = Logger.getLogger(FileARFFGenerator.class.getName());

    private final String projectName;
    private final int index;
    private static final String PATH = "src/main/resources/";

    public FileARFFGenerator(String projectName, int index) {
        this.projectName = projectName;
        this.index = index;
    }

    public void csvToARFFTraining() throws Exception {
        String csvFile  = PATH + projectName.toLowerCase() + "/training/CSV/"  + projectName + "_training_iter_" + index + ".csv";
        String arffFile = PATH + projectName.toLowerCase() + "/training/ARFF/" + projectName + "_training_iter_" + index + ".arff";
        csvToARFF(csvFile, arffFile);
    }

    public void csvToARFFTesting() throws Exception {
        String csvFile  = PATH + projectName.toLowerCase() + "/testing/CSV/"  + projectName + "_testing_iter_" + index + ".csv";
        String arffFile = PATH + projectName.toLowerCase() + "/testing/ARFF/" + projectName + "_testing_iter_" + index + ".arff";
        csvToARFF(csvFile, arffFile);
    }


    /* ================= core ================= */

    private void csvToARFF(String csvFile, String arffFile) throws Exception {
        Instances data = loadCsv(csvFile);

        // 1) rimuovi id/nomi
        data = removeByNamesCaseInsensitive(data, "index", "methodname", "methodsignature");


        // 3) imposta classe (case-insensitive)
        setClassCaseInsensitive(data, "isbuggy");

        // 4) porta la classe in ultima posizione
        data = moveClassToLast(data);

        // 5) pulizia veloce ma SENZA rimuovere costanti
        data = applyRemoveUseless(data); // ora NON tocca le costanti (grazie a -M 0.0)

        // 2) assicurati che le colonne-chiave esistano SEMPRE (zero se mancano)
        ensureNumericAttrInPlace(data, "WeekendCommit", 0.0);

        // 6) ordine canonico (opzionale ma consigliato: schema fisso)
        data = reorderByNames(
                data,
                java.util.List.of(
                        "LOC","CyclomaticComplexity","Churn","LocAdded","fan-in","fan-out",
                        "NewcomerRisk","Auth","WeekendCommit","nSmell" // prima della classe
                )
        );

        // 7) salva
        saveArff(data, arffFile);
        LOG.log(Level.INFO, "ARFF scritto: {0}", arffFile);
    }


    /* =============== helpers =============== */

    private Instances loadCsv(String path) throws IOException {
        CSVLoader loader = new CSVLoader();
        loader.setSource(new File(path));
        return loader.getDataSet();
    }

    private void saveArff(Instances data, String path) throws IOException {
        File out = new File(path);
        out.getParentFile().mkdirs();
        ArffSaver saver = new ArffSaver();
        saver.setInstances(data);
        saver.setFile(out);
        saver.writeBatch();
    }

    private Instances removeByNamesCaseInsensitive(Instances data, String... lowerNames) throws Exception {
        StringBuilder idxs = new StringBuilder();
        for (int i = 0; i < data.numAttributes(); i++) {
            String n = data.attribute(i).name();
            for (String target : lowerNames) {
                if (n.toLowerCase(Locale.ROOT).equals(target)) {
                    if (!idxs.isEmpty()) idxs.append(",");
                    idxs.append(i + 1); // 1-based
                    break;
                }
            }
        }
        if (idxs.isEmpty()) return data;

        Remove remove = new Remove();
        remove.setOptions(new String[]{"-R", idxs.toString()});
        remove.setInputFormat(data);
        return Filter.useFilter(data, remove);
    }

    private void setClassCaseInsensitive(Instances data, String classLowerName) {
        int found = -1;
        for (int i = 0; i < data.numAttributes(); i++) {
            if (data.attribute(i).name().toLowerCase(Locale.ROOT).equals(classLowerName)) {
                found = i; break;
            }
        }
        if (found >= 0) {
            data.setClassIndex(found);
        } else {
            data.setClassIndex(data.numAttributes() - 1);
            LOG.log(Level.WARNING, "Classe \"{0}\" non trovata: uso ultima colonna come classe: {1}",
                    new Object[]{classLowerName, data.attribute(data.numAttributes() - 1).name()});
        }

        // sanity check: deve essere nominale con valori yes/no
        Attribute cls = data.classAttribute();
        if (!cls.isNominal() || cls.numValues() != 2) {
            LOG.warning("Attenzione: la classe non è nominale binaria. Attesa: nominale {yes,no}.");
        } else {
            String v0 = cls.value(0).toLowerCase(Locale.ROOT);
            String v1 = cls.value(1).toLowerCase(Locale.ROOT);
            if (
                    !((v0.equals("yes") && v1.equals("no")) || (v0.equals("no") && v1.equals("yes")))
                            && LOG.isLoggable(Level.WARNING)
            ) {
                LOG.warning(String.format(
                        "Attenzione: valori classe attesi {yes,no}; trovati {%s,%s}.",
                        cls.value(0),
                        cls.value(1)
                ));
            }

        }
    }

    private Instances moveClassToLast(Instances data) throws Exception {
        int cls = data.classIndex();
        if (cls == data.numAttributes() - 1) return data;

        StringBuilder order = new StringBuilder();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i == cls) continue;
            order.append(i + 1).append(",");
        }
        order.append(cls + 1);

        return getInstances(data, order);
    }

    private Instances applyRemoveUseless(Instances data) throws Exception {
        RemoveUseless ru = new RemoveUseless();
        // Non rimuovere attributi a varianza 0 (es. WeekendCommit tutto zero)
        ru.setOptions(new String[] { "-M", "0.0" });
        ru.setInputFormat(data);
        return Filter.useFilter(data, ru);
    }


    public void csvToARFFFull() throws Exception {
        String base = PATH + projectName.toLowerCase() + "/otherFiles/";
        String csvFile  = base + projectName + "_fullDataset.csv";           // dove già scrivi il full CSV
        String arffFile = base + projectName + "_fullDataset.arff"; // destinazione ARFF
        csvToARFF(csvFile, arffFile);
    }

    // --- helpers robustezza ---

    /** Se manca un attributo numerico, lo crea e lo riempie con defaultVal. Ritorna sempre l'istanza (modificata in-place). */
    private static void ensureNumericAttrInPlace(Instances ds, String name, double defaultVal) {
        if (ds.attribute(name) != null) return;
        weka.core.Attribute attr = new weka.core.Attribute(name);
        ds.insertAttributeAt(attr, ds.numAttributes());
        int idx = ds.attribute(name).index();
        for (int i = 0; i < ds.numInstances(); i++) {
            ds.instance(i).setValue(idx, defaultVal);
        }
    }


    /** Riordina gli attributi nell'ordine esatto fornito (1-based list per Reorder). Ignora i nomi non presenti. */
    private static Instances reorderByNames(Instances data, java.util.List<String> desiredOrder) throws Exception {
        java.util.List<Integer> order1Based = new java.util.ArrayList<>();
        for (String nm : desiredOrder) {
            if (data.attribute(nm) != null) {
                order1Based.add(data.attribute(nm).index() + 1);
            }
        }
        // aggiungi eventuali rimanenti non specificati (tranne la classe, che sposteremo dopo)
        int cls = data.classIndex();
        for (int i = 0; i < data.numAttributes(); i++) {
            if (i == cls) continue;
            int one = i + 1;
            if (!order1Based.contains(one)) order1Based.add(one);
        }
        // classe in coda
        order1Based.add(cls + 1);

        StringBuilder spec = new StringBuilder();
        for (int k = 0; k < order1Based.size(); k++) {
            if (k > 0) spec.append(",");
            spec.append(order1Based.get(k));
        }
        return getInstances(data, spec);
    }

    private static Instances getInstances(Instances data, StringBuilder spec) throws Exception {
        Reorder r = new Reorder();
        r.setOptions(new String[]{"-R", spec.toString()});
        r.setInputFormat(data);
        Instances out = Filter.useFilter(data, r);
        out.setClassIndex(out.numAttributes() - 1);
        return out;
    }


}
