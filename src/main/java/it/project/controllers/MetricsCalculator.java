package it.project.controllers;

import it.project.entities.JavaClass;
import it.project.entities.JavaMethod;
import it.project.entities.Release;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MetricsCalculator {

    private final Git git;

    public MetricsCalculator(Git git) {
        this.git = git;
    }

    public void calculateHistoricalMetrics(List<Release> releases) throws IOException {
        // Mappa per tenere traccia degli autori della release precedente per il calcolo del NewcomerRisk
        Map<String, Set<String>> previousAuthors = new HashMap<>();

        for (Release release : releases) {
            Map<String, Set<String>> currentAuthors = new HashMap<>();
            Logger.getAnonymousLogger().log(Level.INFO, "Calcolo metriche per release {0}...", release.getName());
            for (JavaClass javaClass : release.getJavaClassList()) {
                for (JavaMethod javaMethod : javaClass.getMethods()) {
                    calculateMetricsForMethod(javaMethod, release.getCommitList(), previousAuthors, currentAuthors);
                }
            }
            // Aggiorna gli autori per la prossima iterazione
            previousAuthors = currentAuthors;
        }
    }

    private void calculateMetricsForMethod(JavaMethod method, List<RevCommit> commits,
                                           Map<String, Set<String>> previousAuthors,
                                           Map<String, Set<String>> currentAuthors) throws IOException {

        int totalChurn = 0;
        int locAdded = 0;
        Set<String> authors = new HashSet<>();
        int weekendCommits = 0;
        int totalCommitsForMethod = 0;

        String filePath = method.getRelease().getJavaClassList().stream()
                .filter(c -> c.getMethods().contains(method))
                .findFirst().get().getPath();

        for (RevCommit commit : commits) {
            if (commit.getParentCount() == 0) continue; // Salta il primo commit del repo

            RevCommit parent = commit.getParent(0);
            boolean methodTouched = wasMethodTouched(commit, parent, filePath, method.getStartLine(), method.getEndLine());

            if (methodTouched) {
                totalCommitsForMethod++;
                authors.add(commit.getAuthorIdent().getEmailAddress());

                // Calcolo Churn e LOC Added
                int[] churnAndAdded = calculateChurnAndLocAdded(commit, parent, filePath, method.getStartLine(), method.getEndLine());
                totalChurn += churnAndAdded[0];
                locAdded += churnAndAdded[1];

                // Calcolo Weekend Commit Ratio
                LocalDateTime commitDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(commit.getCommitTime()), ZoneId.systemDefault());
                if (commitDate.getDayOfWeek() == DayOfWeek.SATURDAY || commitDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    weekendCommits++;
                }
            }
        }

        method.setChurn(totalChurn);
        method.setLocAdded(locAdded);
        method.setnAuth(authors.size());
        method.setWeekendCommitRatio(totalCommitsForMethod > 0 ? (double) weekendCommits / totalCommitsForMethod : 0.0);

        // Calcolo Newcomer Risk
        String methodId = filePath + "::" + method.getName();
        Set<String> prevAuthorsSet = previousAuthors.getOrDefault(methodId, Collections.emptySet());
        long newAuthorsCount = authors.stream().filter(author -> !prevAuthorsSet.contains(author)).count();
        method.setNewcomerRisk(newAuthorsCount > 0 ? 1.0 : 0.0);

        // Memorizza gli autori di questo metodo per la prossima release
        currentAuthors.put(methodId, authors);
    }

    // Metodo helper per controllare se un metodo è stato toccato in un commit
    private boolean wasMethodTouched(RevCommit commit, RevCommit parent, String filePath, int startLine, int endLine) throws IOException {
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(git.getRepository());
            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());

            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(filePath)) {
                    for (Edit edit : diffFormatter.toFileHeader(diff).toEditList()) {
                        int changeStart = edit.getBeginB();
                        int changeEnd = edit.getEndB();
                        // Controlla se il range di modifica si sovrappone al range del metodo
                        if (Math.max(startLine, changeStart) <= Math.min(endLine, changeEnd)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // Metodo helper per calcolare Churn e LOC Added
    private int[] calculateChurnAndLocAdded(RevCommit commit, RevCommit parent, String filePath, int startLine, int endLine) throws IOException {
        int churn = 0;
        int locAdded = 0;
        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            diffFormatter.setRepository(git.getRepository());
            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());
            for (DiffEntry diff : diffs) {
                if (diff.getNewPath().equals(filePath)) {
                    for (Edit edit : diffFormatter.toFileHeader(diff).toEditList()) {
                        // Considera solo le modifiche che toccano il metodo
                        if (Math.max(startLine, edit.getBeginB()) <= Math.min(endLine, edit.getEndB())) {
                            churn += edit.getLengthA(); // Linee cancellate
                            churn += edit.getLengthB(); // Linee aggiunte
                            locAdded += edit.getLengthB();
                        }
                    }
                }
            }
        }
        return new int[]{churn, locAdded};
    }
}