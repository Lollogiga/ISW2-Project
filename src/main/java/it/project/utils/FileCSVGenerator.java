package it.project.utils;


import it.project.entities.Release;
import it.project.entities.JavaClass;
import it.project.entities.JavaMethod;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileCSVGenerator {
    private final String directoryPath;
    private final String projName;

    private static final String TRAINING_FILE = "_trainingSet";
    private static final String TESTING_FILE = "_testingSet";

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

    public void csv_generateMethodList(List<Release> releases) {
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
}
