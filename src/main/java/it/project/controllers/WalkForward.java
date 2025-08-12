package it.project.controllers;

import it.project.entities.Release;
import it.project.entities.Ticket;
import it.project.utils.FileARFFGenerator;
import it.project.utils.FileCSVGenerator;
import org.eclipse.jgit.api.Git;


import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class WalkForward {
    private final String projectName;
    private final List<Release> fullReleaseList;
    private final List<Ticket> fullTicketList;
    private final FileCSVGenerator csvGenerator;
    private final Buggyness buggyness;

    public WalkForward(String projectName, List<Release> releases, List<Ticket> tickets, FileCSVGenerator csvGenerator, Git git){
        this.projectName = projectName;
        this.fullReleaseList = releases;
        this.fullTicketList = tickets;
        this.csvGenerator = csvGenerator;
        this.buggyness = new Buggyness(git);
    }

    public void execute() {
        Logger.getAnonymousLogger().log(Level.INFO, "Starting walk forward...");

        int totalReleases = fullReleaseList.size();
        int loopLimit = (int) Math.round(totalReleases * 0.40);

        for(int i = 1; i < loopLimit; i++){
            int trainingSetLastIndex = i;
            int testingSetIndex = i + 1;

            Logger.getAnonymousLogger().log(Level.INFO, "--- Iteration {0}: Training on releases 1-{1}, Testing on release {2} ---", new Object[]{i, trainingSetLastIndex, testingSetIndex});

            //Training:
            //1. Select releases for training:
            final int lastTrainIndex = trainingSetLastIndex;
            List<Release> trainingReleases = fullReleaseList.stream()
                    .filter(r -> r.getIndex() <= lastTrainIndex)
                    .toList();

            //2. Select ticket for labelling: We want realistic training set, select only ticket with fix in training set
            List<Ticket> trainingTickets = fullTicketList.stream()
                    .filter(t -> t.getFixedVersion() != null && t.getFixedVersion().getIndex() <= trainingSetLastIndex+1)
                    .toList();

            //3. Labelling for training set
            buggyness.calculate(trainingReleases, trainingTickets);

            //4. Write training set on csv file
            csvGenerator.generateTrainingSet(trainingReleases, i);

            try {
                FileARFFGenerator arffGen = new FileARFFGenerator(projectName, i);
                arffGen.csvToARFFTraining();
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.SEVERE, "Errore conversione ARFF (training) iter " + i, e);

            }

            //Testing:
            //1. Select releases for training:
            Release testingRelease = fullReleaseList.stream()
                    .filter(r -> r.getIndex() == testingSetIndex)
                    .findFirst().orElse(null);

            if(testingRelease == null){
                Logger.getAnonymousLogger().log(Level.WARNING, "No testing release found at index {0}. Ending walk-forward", testingSetIndex);
                break;
            }

            //2. Labelling for testing: we use all tickets (we want most accurate test set)
            List<Release> testingReleaseList = new ArrayList<>();
            testingReleaseList.add(testingRelease);
            buggyness.calculate(testingReleaseList, fullTicketList);

            //3. Write testing set on csv file
            csvGenerator.generateTestingSet(testingReleaseList, i);

            // CONVERSIONE CSV -> ARFF (testing)
            try {
               FileARFFGenerator arffGen = new FileARFFGenerator(projectName, i);
                arffGen.csvToARFFTesting();
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.SEVERE, "Errore conversione ARFF (testing) iter " + i, e);

            }
        }
        Logger.getAnonymousLogger().log(Level.INFO, "Finished walk forward...");
    }



}
