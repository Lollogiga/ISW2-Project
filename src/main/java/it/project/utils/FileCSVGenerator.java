package it.project.utils;


import it.project.entities.Release;
import it.project.entities.JavaClass;
import it.project.entities.JavaMethod;
import it.project.entities.Ticket;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FileCSVGenerator {
    private final String directoryPath;
    private final String projName;


    private static final String TESTING = "testing" + File.separator;
    private static final String TRAINING = "training" + File.separator;
    private static final String OTHERFILES = "otherFiles" + File.separator;
    private static final String TRAINING_CSV = TRAINING + "CSV" + File.separator;
    private static final String TRAINING_ARFF = TRAINING + "ARFF" + File.separator;
    private static final String TESTING_CSV = TESTING + "CSV" + File.separator;
    private static final String TESTING_ARFF = TESTING + "ARFF" + File.separator;
    private static final String RESULT = "result" + File.separator;
    private static final String ACUME = "acumeFiles" + File.separator;

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
                ACUME
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
            String header = "Release_Index,MethodName,LOC,Parameters_Count,Fan_Out,Cyclomatic_Complexity,LCOM,Churn,LOC_Added,Newcomer_Risk,n_Auth,Weekend_Commit,isBuggy";
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
            writeToFile(fileWriter, "Index,MethodName,LOC,CyclomaticComplexity,Churn,LocAdded,NewcomerRisk,Auth,WeekendCommit, nSmell, isBuggy");

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

}
