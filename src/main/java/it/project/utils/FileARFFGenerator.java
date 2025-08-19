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

        // 1) rimuovi sempre index, methodName e methodSignature (case-insensitive)
        data = removeByNamesCaseInsensitive(data, "index", "methodname", "methodsignature");


        // 2) imposta classe isBuggy (case-insensitive); se non trovata -> ultima colonna
        setClassCaseInsensitive(data, "isbuggy");

        // 3) porta la classe in ultima posizione
        data = moveClassToLast(data);

        // 4) quick clean: elimina attributi costanti/duplicati
        data = applyRemoveUseless(data);

        // 5) salva ARFF
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

        Reorder reorder = new Reorder();
        reorder.setOptions(new String[]{"-R", order.toString()});
        reorder.setInputFormat(data);
        Instances out = Filter.useFilter(data, reorder);
        out.setClassIndex(out.numAttributes() - 1);
        return out;
    }

    private Instances applyRemoveUseless(Instances data) throws Exception {
        RemoveUseless ru = new RemoveUseless();
        ru.setInputFormat(data);
        return Filter.useFilter(data, ru);
    }
}
