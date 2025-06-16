package it.project.controllers;

import it.project.entities.Release;
import it.project.entities.Ticket;
import it.project.utils.FileCSVGenerator;
import it.project.utils.TicketUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Executor {
    private static final String DIRECTORY = "src/main/resources/";

    private Executor() {}

    public static void dataExtraction(String projectName) throws IOException {
        JiraExtraction jira = new JiraExtraction(projectName);
        FileCSVGenerator csv = new FileCSVGenerator(DIRECTORY, projectName);


        List<Release> releaseList = jira.getReleaseInfo();
        Logger.getAnonymousLogger().log(Level.INFO, "Data extraction: Releases List");

        //Generiamo il file csv relativo alle release estratte da jira
        csv.generateReleaseInfo(releaseList);

        List<Ticket> ticketList = jira.fetchTickets(releaseList);
        Logger.getAnonymousLogger().log(Level.INFO, "Data extraction: Tickets List");
        //Remove all ticket not reliable
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);
        //Order data by resolution date:
        ticketList.sort(Comparator.comparing(Ticket::getResolutionDate));

        //Compute proportion for have IV:
        Proportion.calculateProportion(ticketList, releaseList);
        Logger.getAnonymousLogger().log(Level.INFO, "Data extraction: Proportion computed!");
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);

        //Extraction from git:
        GitExtraction gitExtraction = new GitExtraction();
        try {
            gitExtraction.associateCommitsToReleases(releaseList);
            // Remove releases without commit
            long initialSize = releaseList.size();
            releaseList.removeIf(release -> release.getCommitList().isEmpty());
            long finalSize = releaseList.size();

            Logger.getAnonymousLogger().log(Level.INFO, "Filtering release: removed {0} uncommitted release. Remaining: {1}", new Object[]{initialSize - finalSize, finalSize});

            Logger.getAnonymousLogger().log((Level.INFO), "Data extraction: Extraction class and method");
            for (Release release : releaseList) {
                gitExtraction.analyzeReleaseCode(release);
                Logger.getAnonymousLogger().log(Level.INFO, "Release {0}: founded {1} class with method", new Object[]{release.getName(), release.getJavaClassList().size()});
            }
        } catch (GitAPIException | IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Fatal error while parsing Git repository. Unable to proceed.", e);
        }
        try {
            Git gitInstance = it.project.utils.RepoFactory.getGit();
            MetricsCalculator metricsCalculator = new MetricsCalculator(gitInstance);
            metricsCalculator.calculateHistoricalMetrics(releaseList);
            Logger.getAnonymousLogger().log(Level.INFO, "Calcolo metriche storiche completato.");
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore durante il calcolo delle metriche storiche", e);
        }

        //Export methodList in a csvFile:
        csv.csv_generateMethodList(releaseList);

        //Generate Dataset:
        csv.generateDataset(releaseList);

    }
}
