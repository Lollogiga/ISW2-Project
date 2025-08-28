package it.project.controllers;

import it.project.entities.Release;
import it.project.entities.Ticket;
import it.project.utils.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class DatasetCreation {
    private static final String DIRECTORY = "src/main/resources/";
    private static final Logger LOG = Logger.getLogger(DatasetCreation.class.getName());

    private DatasetCreation() {}

    public static void dataExtraction(String projectName, String pmdPath) throws IOException {
        JiraExtraction jira = new JiraExtraction(projectName);
        FileCSVGenerator csv = new FileCSVGenerator(DIRECTORY, projectName);

        // Releases + CSV
        List<Release> releaseList = jira.getReleaseInfo();
        LOG.info("Data extraction: Releases List");
        csv.generateReleaseInfo(releaseList);

        // Tickets preparati (fix inconsistenze, ordine, proporzioni)
        List<Ticket> ticketList = prepareTickets(jira, releaseList);
        // Git: associa commit alle release
        GitExtraction gitExtraction = new GitExtraction(pmdPath);
        associateCommitsSafe(gitExtraction, releaseList);

        // Filtra release senza commit e riassegna indici
        filterReleasesWithoutCommits(releaseList);
        reassignSequentialIndices(releaseList);

        // Log debug FV=1
        logTicketsFixedInFirstRelease(ticketList);

        // Anti-snoring (34%)
        List<Release> releaseToProcess = selectHeadReleases(releaseList, 0.34);

        // Link ticket-commit + export riassunto
        LOG.info("Data extraction: Tickets Summary");
        TicketUtils.linkTicketsToCommits(ticketList, releaseList);
        csv.generateTicketSummary(ticketList);

        // Analisi codice per release selezionate
        analyzeCode(gitExtraction, releaseToProcess);

        // Elenco metodi
        csv.generateMethodList(releaseToProcess);
        LOG.log(Level.INFO, "CSV creation: methods for each release saved in resources/otherFiles/{0}_MethodList", projectName);

        // Metriche storiche
        extractMetricsSafe(releaseToProcess);

        // Walk-forward (training/testing)
        runWalkForwardSafe(projectName, releaseList, ticketList, csv);

        LOG.info("Training set and testing set files generated!");

        // Labelling full dataset + ARFF (post-Weka) con TUTTI i ticket
        labelAndExportFullDatasetSafe(projectName, releaseList, ticketList, releaseToProcess, csv);
    }

    /* ==================== Helpers ==================== */

    private static List<Ticket> prepareTickets(JiraExtraction jira, List<Release> releaseList) throws IOException {
        List<Ticket> ticketList = jira.fetchTickets(releaseList);
        LOG.info("Data extraction: Tickets List");
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);
        ticketList.sort(Comparator.comparing(Ticket::getResolutionDate));
        Proportion.calculateProportion(ticketList, releaseList);
        LOG.info("Data extraction: Proportion computed!");
        TicketUtils.fixInconsistentTickets(ticketList, releaseList);
        return ticketList;
    }

    private static void associateCommitsSafe(GitExtraction gitExtraction, List<Release> releaseList) {
        try {
            gitExtraction.associateCommitsToReleases(releaseList);
        } catch (GitAPIException | IOException e) {
            LOG.log(Level.SEVERE, "GitExtraction Error", e);
        }
    }

    private static void filterReleasesWithoutCommits(List<Release> releaseList) {
        long initialSize = releaseList.size();
        releaseList.removeIf(r -> r.getCommitList().isEmpty());
        long finalSize = releaseList.size();
        LOG.log(Level.INFO, "Filtering release: removed {0} uncommitted release. Remaining: {1}",
                new Object[]{initialSize - finalSize, finalSize});
    }

    private static void reassignSequentialIndices(List<Release> releaseList) {
        LOG.info("Re-assigning sequential indices to remaining releases...");
        int idx = 1;
        for (Release r : releaseList) r.setIndex(idx++);
    }

    private static void logTicketsFixedInFirstRelease(List<Ticket> ticketList) {
        ticketList.stream()
                .filter(t -> t.getFixedVersion() != null && t.getFixedVersion().getIndex() == 1)
                .forEach(t -> {
                    String ivName = (t.getInjectedVersion() != null) ? t.getInjectedVersion().getName() : "null";
                    String avNames = t.getAffectedVersionsList().stream()
                            .map(Release::getName).collect(Collectors.joining(", "));
                    LOG.log(Level.INFO, "[DEBUG TICKET FV=1] Key: {0}, IV: {1}, AV: [{2}]",
                            new Object[]{t.getTicketKey(), ivName, avNames});
                });
    }

    private static List<Release> selectHeadReleases(List<Release> releaseList, double fraction) {
        int total = releaseList.size();
        int keep = (int) Math.round(total * fraction);
        if (keep == 0 && total > 0) keep = 1;
        LOG.log(Level.INFO, "Anti-snoring filter: Total releases are {0}. Keeping the first {1}% ({2} releases).",
                new Object[]{total, (int) (fraction * 100), keep});
        List<Release> head = releaseList.subList(0, keep);
        LOG.log(Level.INFO, "Anti-snoring filter applied. Final number of releases: {0}", head.size());
        return head;
    }

    private static void analyzeCode(GitExtraction gitExtraction, List<Release> releaseToProcess) throws IOException {
        LOG.info("Data extraction: Extraction class and method");
        for (Release r : releaseToProcess) {
            gitExtraction.analyzeReleaseCode(r);
            LOG.log(Level.INFO, "Release {0}: founded {1} class with method",
                    new Object[]{r.getName(), r.getJavaClassList().size()});
        }
    }

    private static void extractMetricsSafe(List<Release> releaseToProcess) {
        LOG.info("Start metrics extraction");
        try {
            Git git = RepoFactory.getGit();
            new MetricsCalculator(git).calculateHistoricalMetrics(releaseToProcess);
            LOG.info("Metrics calculated.");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Error during metrics calculation", e);
        }
    }

    private static void runWalkForwardSafe(String projectName, List<Release> releaseList,
                                           List<Ticket> ticketList, FileCSVGenerator csv) {
        try {
            Git git = RepoFactory.getGit();
            new WalkForward(projectName, releaseList, ticketList, csv, git).execute();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "An error occurred during the Walk-Forward process", e);
        }
    }

    private static void labelAndExportFullDatasetSafe(String projectName, List<Release> releaseList,
                                                      List<Ticket> ticketList, List<Release> releaseToProcess,
                                                      FileCSVGenerator csv) {
        try {
            Git git = RepoFactory.getGit();
            Buggyness b = new Buggyness(git);

            // reset etichette
            releaseList.forEach(r -> r.getJavaClassList()
                    .forEach(jc -> jc.getMethods().forEach(m -> m.setBuggy(false))));

            b.calculate(releaseList, ticketList);
            csv.generateFullDataset(releaseToProcess);
            new FileARFFGenerator(projectName, 0).csvToARFFFull();
            LOG.info("Full dataset etichettato e rigenerato.");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Errore durante il labelling post-Weka", e);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Errore durante la creazione del file ARFF", e);
        }
    }
}
