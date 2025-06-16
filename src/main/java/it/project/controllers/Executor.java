package it.project.controllers;

import it.project.entities.Release;
import it.project.entities.Ticket;
import it.project.utils.FileCSVGenerator;
import it.project.utils.TicketUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

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
        Logger.getAnonymousLogger() .log(Level.INFO, "Data extraction: Releases List");

        //Generiamo il file csv relativo alle release estratte da jira
        csv.generateReleaseInfo(releaseList);

        List<Ticket> ticketList = jira.fetchTickets(releaseList);
        Logger.getAnonymousLogger() .log(Level.INFO, "Data extraction: Tickets List");
        //Remove all ticket not reliable
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);
        //Order data by resolution date:
        ticketList.sort(Comparator.comparing(Ticket::getResolutionDate));

        //Compute proportion for have IV:
        Proportion.calculateProportion(ticketList, releaseList);
        Logger.getAnonymousLogger() .log(Level.INFO, "Data extraction: Proportion computed!");
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);

        //Extraction from git:
        GitExtraction gitExtraction = new GitExtraction();
        try {
            gitExtraction.associateCommitsToReleases(releaseList);
            // Remove releases without commit
            long initialSize = releaseList.size();
            releaseList.removeIf(release -> release.getCommitList().isEmpty());
            long finalSize = releaseList.size();

            Logger.getAnonymousLogger().log(Level.INFO, "Filtraggio release: rimosse {0} release senza commit. Restanti: {1}", new Object[]{initialSize - finalSize, finalSize});
        } catch (GitAPIException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore fatale durante l'analisi del repository Git. Impossibile procedere.", e);
        }

    }
}
