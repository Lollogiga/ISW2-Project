package it.project.controllers;

import it.project.entities.Release;
import it.project.utils.FileCSVGenerator;

import java.io.IOException;
import java.util.List;

public class Executor {
    private static final String DIRECTORY = "src/main/resources/";

    private Executor() {}

    public static void dataExtraction(String projectName) throws IOException {
        JiraExtraction jira = new JiraExtraction(projectName);

        //Create class:
        FileCSVGenerator csv = new FileCSVGenerator(DIRECTORY, projectName);

        //
        List<Release> releaseList = jira.getReleaseInfo();
    }
}
