package it.project.utils;

import java.io.File;

public class FileCSVGenerator {

    private final String directoryPath;
    private final String projectName;

    public FileCSVGenerator(String directoryPath, String projectName) {
        this.directoryPath = directoryPath + projectName.toLowerCase() + File.separator;
    }
}
