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
import java.util.stream.Collectors;

public class Executor {
    private static final String DIRECTORY = "src/main/resources/";

    private Executor() {
    }

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
        try{
            gitExtraction.associateCommitsToReleases(releaseList);
        }catch (GitAPIException e){
            Logger.getAnonymousLogger().log(Level.SEVERE, "GitExtraction Error", e);
        }


        // Remove releases without commit
        long initialSize = releaseList.size();
        releaseList.removeIf(release -> release.getCommitList().isEmpty());
        long finalSize = releaseList.size();
        Logger.getAnonymousLogger().log(Level.INFO, "Filtering release: removed {0} uncommitted release. Remaining: {1}", new Object[]{initialSize - finalSize, finalSize});

        //Re-assigning index:
        Logger.getAnonymousLogger().log(Level.INFO, "Re-assigning sequential indices to remaining releases...");
        int index = 1;
        for (Release release : releaseList) {
            release.setIndex(index);
            index++;
        }

        for (Ticket ticket : ticketList) {
            if (ticket.getFixedVersion() != null && ticket.getFixedVersion().getIndex() == 1) {
                String ivName = (ticket.getInjectedVersion() != null) ? ticket.getInjectedVersion().getName() : "null";
                String avNames = ticket.getAffectedVersionsList().stream()
                        .map(Release::getName)
                        .collect(Collectors.joining(", "));
                Logger.getAnonymousLogger().log(Level.INFO,
                        "[DEBUG TICKET FV=1] Key: {0}, IV: {1}, AV: [{2}]",
                        new Object[]{ticket.getTicketKey(), ivName, avNames});
            }
        }

        //Avoid snoring: keep only first 40% of releases:
        int totalReleases = releaseList.size();
        int releasesToKeep = (int) Math.round(totalReleases * 0.40);
        if(releasesToKeep == 0 && totalReleases > 0) releasesToKeep = 1;

        Logger.getAnonymousLogger().log(Level.INFO, "Anti-snoring filter: Total releases are {0}. Keeping the first 40% ({1} releases).", new Object[]{totalReleases, releasesToKeep});
        List<Release> releaseToProcess = releaseList.subList(0, releasesToKeep);

        Logger.getAnonymousLogger().log(Level.INFO, "Anti-snoring filter applied. Final number of releases: {0}", releaseToProcess.size());

        //Linkage tickets and commits:
        Logger.getAnonymousLogger().log(Level.INFO, "Data extraction: Tickets Summary");
        TicketUtils.linkTicketsToCommits(ticketList, releaseList);
        csv.generateTicketSummary(ticketList);


        //Extract class and method:
        Logger.getAnonymousLogger().log((Level.INFO), "Data extraction: Extraction class and method");
        for (Release release : releaseToProcess) {
            gitExtraction.analyzeReleaseCode(release);
            Logger.getAnonymousLogger().log(Level.INFO, "Release {0}: founded {1} class with method", new Object[]{release.getName(), release.getJavaClassList().size()});
        }

        //Export methodList in a csvFile:
        csv.generateMethodList(releaseToProcess);
        Logger.getAnonymousLogger().log(Level.INFO, "CSV creation: methods for each release saved in resources/otherFiles/{0}_MethodList", projectName);

        //Extract metrics:
        Logger.getAnonymousLogger().log(Level.INFO, "Start metrics extraction");
        try {
            Git gitInstance = it.project.utils.RepoFactory.getGit();
            MetricsCalculator metricsCalculator = new MetricsCalculator(gitInstance);
            metricsCalculator.calculateHistoricalMetrics(releaseToProcess);
            Logger.getAnonymousLogger().log(Level.INFO, "Metrics calculated.");
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Error during metrics calculation", e);
        }

        csv.generateDataset(releaseToProcess);
        Logger.getAnonymousLogger().log(Level.INFO, "CSV creation: metrics for each method saved in resources/otherFiles/{0}_dateset: bugginess not yet computed", projectName);

        /*Walk forward*/
        try {
            Git gitInstance = it.project.utils.RepoFactory.getGit();
            WalkForward walkForward = new WalkForward(releaseList, ticketList, csv, gitInstance);
            walkForward.execute();
        } catch (IOException e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "An error occurred during the Walk-Forward process", e);
        }

        Logger.getAnonymousLogger().log(Level.INFO, "Training set and testing set files generated!");


    }


}
