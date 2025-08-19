package it.project.utils;


import it.project.controllers.WekaClassifier;
import it.project.entities.*;
import weka.core.Instances;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;


public class FileCSVGenerator {
    private final String directoryPath;
    private final String projName;
    private static final String ATTRIBUTE_NAME = "attribute_name";
    private static final String ATTRIBUTE_INDEX = "attribute_index";


    private static final String TESTING = "testing" + File.separator;
    private static final String TRAINING = "training" + File.separator;
    private static final String OTHERFILES = "otherFiles" + File.separator;
    private static final String TRAINING_CSV = TRAINING + "CSV" + File.separator;
    private static final String TRAINING_ARFF = TRAINING + "ARFF" + File.separator;
    private static final String TESTING_CSV = TESTING + "CSV" + File.separator;
    private static final String TESTING_ARFF = TESTING + "ARFF" + File.separator;
    private static final String RESULT = "result" + File.separator;
    private static final String PREDICTION = "prediction" + File.separator;
    private static final String FEATURES_SELECTION = "features_selection" + File.separator;


    public FileCSVGenerator(String directoryPath, String projName) throws IOException {
        this.projName = projName;
        this.directoryPath = directoryPath + projName.toLowerCase() + File.separator;

        String[] subPaths = {
                "",
                TRAINING,
                TESTING,
                OTHERFILES,
                TRAINING_CSV,
                TRAINING_ARFF,
                TESTING_CSV,
                TESTING_ARFF,
                RESULT,
                PREDICTION,
                FEATURES_SELECTION
        };

        createDirectories(subPaths);
    }

    private void createDirectories(String[] subPaths) throws IOException {
        for (String subPath : subPaths) {
            Path path = Paths.get(this.directoryPath + subPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        }
    }

    private void writeToFile(FileWriter fileWriter, String content) throws IOException {
        fileWriter.append(content);
        fileWriter.append("\n");
    }

    private void closeWriter(FileWriter fileWriter) {
        if (fileWriter != null) {
            try {
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException e) {
                Logger.getAnonymousLogger().log(Level.INFO, e.getMessage());
            }
        }
    }


    public void generateReleaseInfo(List<Release> releases) {
        FileWriter fileWriter = null;

        try {
            String fileTitle = this.directoryPath + OTHERFILES + this.projName + "_releaseList.csv";

            fileWriter = new FileWriter(fileTitle);

            writeToFile(fileWriter, "Index,Version ID,Version Name,Date");

            for (int i = 0; i < releases.size(); i++) {
                Release release = releases.get(i);
                int index = i + 1;

                writeToFile(fileWriter, index + "," + release.getVersionID() + "," +
                        release.getName() + "," + release.getDate().toString());
            }

        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "An error occurred while generating release info", e);
        } finally {
            closeWriter(fileWriter);
        }
    }

    public void generateMethodList(List<Release> releases) {
        FileWriter fileWriter = null;
        String fileTitle = this.directoryPath + OTHERFILES + this.projName + "_MethodList.csv";

        try {
            fileWriter = new FileWriter(fileTitle);

            // 1. Modifica l'intestazione per riflettere le due colonne
            writeToFile(fileWriter, "Release,MethodFullName");

            // Iteriamo su ogni release
            for (Release release : releases) {
                String releaseName = release.getName(); // Otteniamo il nome della release

                // Per ogni release, iteriamo su ogni classe Java
                for (JavaClass javaClass : release.getJavaClassList()) {
                    // Per ogni classe, iteriamo su ogni metodo
                    for (JavaMethod javaMethod : javaClass.getMethods()) {

                        String methodIdentifier = javaClass.getPath() + "::" + javaMethod.getName();

                        // 2. Creiamo la riga del CSV con entrambi i valori, separati da una virgola
                        String csvLine = releaseName + "," + methodIdentifier;

                        // Scriviamo la riga completa nel file
                        writeToFile(fileWriter, csvLine);
                    }
                }
            }
            Logger.getAnonymousLogger().log(Level.INFO, "Generato file con elenco metodi per release: {0}", fileTitle);

        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante la generazione della lista dei metodi per release", e);
        } finally {
            closeWriter(fileWriter);
        }
    }

    public void generateDataset(List<Release> releases) {
        FileWriter fileWriter = null;
        String fileTitle = this.directoryPath + OTHERFILES + this.projName + "_dataset.csv";

        try {
            fileWriter = new FileWriter(fileTitle);

            // 1. Modifica l'intestazione per usare "Release_Index" e metterlo per primo.
            String header = "Release_Index,MethodName,LOC,Parameters_Count,Fan_Out,Cyclomatic_Complexity,LCOM,Churn,LOC_Added,Newcomer_Risk,n_Auth,Weekend_Commit,numSmell,isBuggy";
            writeToFile(fileWriter, header);

            Logger.getAnonymousLogger().log(Level.INFO, "Generazione dataset in corso... {0}", fileTitle);

            for (Release release : releases) {
                for (JavaClass javaClass : release.getJavaClassList()) {
                    for (JavaMethod javaMethod : javaClass.getMethods()) {
                        String methodName = javaClass.getPath() + "::" + javaMethod.getName();

                        // 2. Modifica l'ordine dei valori e usa getIndex()
                        String[] values = {
                                String.valueOf(release.getIndex()), // Usa l'indice numerico
                                methodName,
                                String.valueOf(javaMethod.getLoc()),
                                String.valueOf(javaMethod.getParametersCount()),
                                String.valueOf(javaMethod.getFanOut()),
                                String.valueOf(javaMethod.getCyclomaticComplexity()),
                                String.valueOf(javaClass.getLcom()),
                                String.valueOf(javaMethod.getChurn()),
                                String.valueOf(javaMethod.getLocAdded()),
                                String.valueOf(javaMethod.getNewcomerRisk()),
                                String.valueOf(javaMethod.getnAuth()),
                                String.valueOf(javaMethod.getWeekendCommit()),
                                String.valueOf(javaMethod.getnSmells()),
                                // 3. Correzione: Converti il booleano 'isBuggy' in una stringa "YES" o "NO"
                                javaMethod.isBuggy()
                        };

                        String csvLine = String.join(",", values);
                        writeToFile(fileWriter, csvLine);
                    }
                }
            }
            Logger.getAnonymousLogger().log(Level.INFO, "Generazione dataset completata.");

        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante la generazione del dataset CSV", e);
        } finally {
            closeWriter(fileWriter);
        }
    }

    public void generateTicketSummary(List<Ticket> ticketList) {
        FileWriter fileWriter = null;
        String fileTitle = this.directoryPath + OTHERFILES + this.projName + "_ticket_summary.csv";

        try {
            fileWriter = new FileWriter(fileTitle);

            // 1. Scrivi l'intestazione del CSV
            writeToFile(fileWriter, "Key,Injected Version,Opening Version,Fixed Version,\"Affected Version List\"");

            Logger.getAnonymousLogger().log(Level.INFO, "Generating ticket summary file at: {0}", fileTitle);

            for (Ticket ticket : ticketList) {
                // 2. Estrai i dati in modo sicuro, gestendo i valori null
                String key = ticket.getTicketKey();

                // Usiamo un operatore ternario per evitare NullPointerException
                String iv = (ticket.getInjectedVersion() != null) ? ticket.getInjectedVersion().getName() : "N/A";
                String ov = (ticket.getOpeningVersion() != null) ? ticket.getOpeningVersion().getName() : "N/A";
                String fv = (ticket.getFixedVersion() != null) ? ticket.getFixedVersion().getName() : "N/A";

                // 3. Formatta la lista delle Affected Versions come una singola stringa separata da spazi
                String affectedVersions = ticket.getAffectedVersionsList().stream()
                        .map(Release::getName)
                        .collect(Collectors.joining(" "));

                // Se la lista è vuota, usiamo "N/A" per coerenza
                if (affectedVersions.isEmpty()) {
                    affectedVersions = "N/A";
                }

                // 4. Assembla la riga CSV. Usiamo String.format per leggibilità e sicurezza.
                //    Le virgolette intorno ad affectedVersions sono importanti per il formato CSV,
                //    nel caso i nomi contengano caratteri speciali.
                String csvLine = String.format("%s,%s,%s,%s,\"%s\"", key, iv, ov, fv, affectedVersions);

                writeToFile(fileWriter, csvLine);
            }

            Logger.getAnonymousLogger().log(Level.INFO, "Ticket summary file generated successfully.");

        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Error while generating ticket summary file", e);
        } finally {
            closeWriter(fileWriter);
        }
    }

    private void generateDatasetFile(List<Release> releases, String filePath) {
        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(filePath);

            // Intestazione del CSV
            writeToFile(fileWriter, "Index,MethodName,LOC,CyclomaticComplexity,Churn,LocAdded,fan-in,fan-out, NewcomerRisk,Auth,WeekendCommit, nSmell, isBuggy");

            for (Release release : releases) {
                for (JavaClass jc : release.getJavaClassList()) {
                    for (JavaMethod jm : jc.getMethods()) {
                        String line = String.join(",",
                                String.valueOf(release.getIndex()),
                                "\"" + jc.getPath() + "::" + jm.getName() + "\"", // Metti tra virgolette per sicurezza
                                String.valueOf(jm.getLoc()),
                                String.valueOf(jm.getCyclomaticComplexity()),
                                String.valueOf(jm.getChurn()),
                                String.valueOf(jm.getLocAdded()),
                                String.valueOf(jm.getFanIn()),
                                String.valueOf(jm.getFanOut()),
                                String.valueOf(jm.getNewcomerRisk()),
                                String.valueOf(jm.getnAuth()),
                                String.valueOf(jm.getWeekendCommit()),
                                String.valueOf(jm.getnSmells()),
                                jm.isBuggy()
                        );
                        writeToFile(fileWriter, line);
                    }
                }
            }
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, e, () -> "Error generating dataset file: " + filePath);

        } finally {
            closeWriter(fileWriter);
        }
    }

    public void generateTrainingSet(List<Release> releases, int iteration) {
        String filePath = this.directoryPath + TRAINING_CSV + this.projName + "_training_iter_" + iteration + ".csv";
        Logger.getAnonymousLogger().log(Level.INFO, "Generating Training Set: {0}", filePath);
        generateDatasetFile(releases, filePath);
    }

    public void generateTestingSet(List<Release> releases, int iteration) {
        String filePath = this.directoryPath + TESTING_CSV + this.projName + "_testing_iter_" + iteration + ".csv";
        Logger.getAnonymousLogger().log(Level.INFO, "Generating Testing Set: {0}", filePath);
        generateDatasetFile(releases, filePath);
    }

    public void generatePredictionsFile(List<String[]> rows,
                                        String classifierName,
                                        String featureSelection,
                                        int iteration) {
        String safeFS = toSafeSlug(featureSelection);
        String dir = this.directoryPath + PREDICTION;
        String fileName = String.format("%s_predictions_iter_%d_%s_%s.csv",
                projName, iteration, toSafeSlug(classifierName), safeFS);

        ensureDir(dir);
        File out = new File(dir, fileName);

        try (FileWriter fw = new FileWriter(out)) {
            for (String[] r : rows) fw.write(csvLine(r));
            Logger.getAnonymousLogger().log(Level.INFO, "Scritto file di predizioni: {0}", out.getAbsolutePath());
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore scrittura predizioni: ", e);
        }
    }

    public void generateWekaResultFile(List<ClassifierResults> classifierResultsList, int iteration) {
        // Raggruppa per (classifier | feature_selection_on/off)
        Map<String, List<ClassifierResults>> groups = classifierResultsList.stream()
                .collect(Collectors.groupingBy(res -> key(res.getClassifierName(), res.isSelection())));

        String dir = this.directoryPath + RESULT;

        final String fileName = (iteration != 0)
                ? String.format("%s_results_iter_%d.csv", projName, iteration)
                : String.format("%s_weka_metrics_aggregate.csv", projName);

        ensureDir(dir);
        File out = new File(dir, fileName);

        try (FileWriter fw = new FileWriter(out)) {
            // Header con Accuracy + PofB20 / NPofB20
            fw.write(csvLine(new String[]{
                    "classifier","feature_selection","iterations",
                    "avg_recall","avg_precision","avg_f1","avg_auc","avg_kappa","avg_accuracy",
                    "sum_tp","sum_fp","sum_tn","sum_fn",
                    "avg_pofb20","avg_npofb20"
            }));

            for (Map.Entry<String, List<ClassifierResults>> e : groups.entrySet()) {
                String[] parts = e.getKey().split("\\|", -1);
                String clf = parts[0];
                String featSel = (parts.length > 1) ? parts[1] : "";

                List<ClassifierResults> list = e.getValue();
                int n = list.size();

                // Medie classiche
                double avgRec = averageOrNaN(list, ClassifierResults::getRec);
                double avgPre = averageOrNaN(list, ClassifierResults::getPreci);
                double avgF1  = averageOrNaN(list, ClassifierResults::getFMeasure);
                double avgAuc = averageOrNaN(list, ClassifierResults::getAuc);
                double avgKap = averageOrNaN(list, ClassifierResults::getKappa);
                double avgAcc = averageOrNaN(list, ClassifierResults::getAccuracy);

                long sumTP = Math.round(list.stream().mapToDouble(ClassifierResults::getTruePositives).sum());
                long sumFP = Math.round(list.stream().mapToDouble(ClassifierResults::getFalsePositives).sum());
                long sumTN = Math.round(list.stream().mapToDouble(ClassifierResults::getTrueNegatives).sum());
                long sumFN = Math.round(list.stream().mapToDouble(ClassifierResults::getFalseNegatives).sum());

                // Medie effort-aware (gestione null/NaN)
                double avgPofB20  = averageNullable(list, ClassifierResults::getPofB20);
                double avgNPofB20 = averageNullable(list, ClassifierResults::getNpofB20);

                fw.write(csvLine(new String[]{
                        clf, featSel, String.valueOf(n),
                        formatOrNaN(avgRec), formatOrNaN(avgPre), formatOrNaN(avgF1),
                        formatOrNaN(avgAuc), formatOrNaN(avgKap), formatOrNaN(avgAcc),
                        String.valueOf(sumTP), String.valueOf(sumFP),
                        String.valueOf(sumTN), String.valueOf(sumFN),
                        formatOrNaN(avgPofB20), formatOrNaN(avgNPofB20)
                }));
            }

            Logger.getAnonymousLogger().log(Level.INFO, "Scritto file aggregato: {0}", out.getAbsolutePath());
        } catch (IOException ex) {
            Logger logger = Logger.getAnonymousLogger();
            if (logger.isLoggable(Level.SEVERE)) {
                logger.log(Level.SEVERE, "Errore scrittura aggregato", ex);
            }
        }


    }

    private static double averageOrNaN(List<ClassifierResults> list, ToDoubleFunction<ClassifierResults> getter) {
        return list.stream().mapToDouble(getter).average().orElse(Double.NaN);
    }



    private static String formatOrNaN(Double d) {
        if (d == null) return "NaN";
        if (d.isNaN()) return "NaN";
        return String.format(java.util.Locale.US, "%.4f", d);
    }

    private static <T> double averageNullable(List<T> list, java.util.function.Function<T, Double> getter) {
        // media su valori non-null e non-NaN; se nessuno disponibile -> NaN
        double[] arr = list.stream()
                .map(getter)
                .filter(v -> v != null && !v.isNaN())
                .mapToDouble(Double::doubleValue)
                .toArray();
        if (arr.length == 0) return Double.NaN;
        double sum = 0.0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    private String key(String classifierName, String featSel) {
        return (classifierName == null ? "" : classifierName) + "|" + (featSel == null ? "" : featSel);
    }

    public void saveSelectedFeatures(int iteration,
                                     String featureSelection,
                                     Instances dataset,
                                     int[] selectedIndices) {
        // cartelle/nomi file
        String attributeIndex = ATTRIBUTE_INDEX;

        String dirPerFS = this.directoryPath +  FEATURES_SELECTION + File.separator;
        ensureDir(dirPerFS);

        String safeFS = toSafeSlug(featureSelection);
        File perFsFile = new File(dirPerFS,
                String.format("%s_fs_iter_%d_%s.csv", projName, iteration, safeFS));

        // file cumulativo
        String cumulativePath = this.directoryPath + RESULT +
                String.format("%s_features_selected_all.csv", projName);
        File cumulativeFile = new File(cumulativePath);

        try (FileWriter fwPer = new FileWriter(perFsFile);
             FileWriter fwAll = new FileWriter(cumulativeFile, /*append*/ true)) {

            // header per-file
            fwPer.write(csvLine(new String[]{"rank", ATTRIBUTE_NAME,attributeIndex}));

            // se il cumulativo non esiste (o è vuoto), scrivi header
            if (!cumulativeFile.exists() || cumulativeFile.length() == 0) {
                fwAll.write(csvLine(new String[]{
                        "iteration","feature_selection","rank", ATTRIBUTE_NAME,attributeIndex
                }));
            }

            int classIdx = dataset.classIndex();
            int rank = 1;
            for (int idx : selectedIndices) {
                if (idx == classIdx) continue; // escludi la classe
                String attrName = dataset.attribute(idx).name();

                // riga per-file
                fwPer.write(csvLine(new String[]{
                        String.valueOf(rank),
                        attrName,
                        String.valueOf(idx)
                }));

                // riga cumulativa
                fwAll.write(csvLine(new String[]{
                        String.valueOf(iteration),
                        featureSelection == null ? "base" : featureSelection,
                        String.valueOf(rank),
                        attrName,
                        String.valueOf(idx)
                }));

                rank++;
            }

            Logger.getAnonymousLogger().log(Level.INFO,
                    "Salvate feature selezionate: {0}",
                    perFsFile.getAbsolutePath());

        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE,
                    "Errore salvataggio feature selezionate", e);
        }
    }

    private String csvLine(String[] cols) {
        return Arrays.stream(cols).map(this::csvEscape).collect(Collectors.joining(",")) + "\n";
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        String z = s.replace("\"", "\"\"");
        return "\"" + z + "\"";
    }

    private void ensureDir(String dirPath) {
        try {
            Files.createDirectories(new File(dirPath).toPath());
        } catch (IOException e) {
            Logger log = Logger.getAnonymousLogger();
            if (log.isLoggable(Level.SEVERE)) {
                log.log(Level.SEVERE, String.format("Impossibile creare directory %s: %s", dirPath, e.getMessage()), e);
            }
        }
    }


    private String toSafeSlug(String s) {
        if (s == null || s.isBlank()) return "base";
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    public void saveFeaturesCorrelationRanking(List<WekaClassifier.FeatureScore> scores) {
        String fileTitle = this.directoryPath + OTHERFILES +
                this.projName + "_featuresCorrelationWithBuggyness.csv";

        try (FileWriter fw = new FileWriter(fileTitle)) {
            // Intestazione
            fw.write(csvLine(new String[]{"rank", ATTRIBUTE_NAME,ATTRIBUTE_INDEX,"score"}));

            int rank = 1;
            for (WekaClassifier.FeatureScore fs : scores) {
                fw.write(csvLine(new String[]{
                        String.valueOf(rank),
                        fs.name,
                        String.valueOf(fs.index),
                        String.format("%.6f", fs.score) // formattato a 6 decimali
                }));
                rank++;
            }

            Logger.getAnonymousLogger().log(Level.INFO,
                    "Salvato ranking correlazione feature-buggyness in: {0}", fileTitle);

        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE,
                    "Errore durante il salvataggio del ranking correlazione feature-buggyness", e);
        }
    }


}
