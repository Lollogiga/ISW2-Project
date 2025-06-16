package it.project.controllers;

import it.project.entities.Release;
import it.project.utils.RepoFactory;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GitExtraction {

    private final Git git;

    public GitExtraction() throws IOException {
        this.git = RepoFactory.getGit();
    }

    /**
     * Associa i commit a ciascuna release basandosi sugli intervalli di date.
     * La lista di release DEVE essere già ordinata per data.
     * @param releases Lista di release ordinate cronologicamente.
     */
    public void associateCommitsToReleases(List<Release> releases) throws GitAPIException, IOException {
        Logger.getAnonymousLogger().log(Level.INFO, "Inizio associazione commit alle release.");

        // Recupera tutti i commit dal repository una sola volta per efficienza
        List<RevCommit> allCommits = new ArrayList<>();
        git.log().all().call().forEach(allCommits::add);
        // Ordina i commit per data, dal più vecchio al più recente
        allCommits.sort(Comparator.comparingInt(RevCommit::getCommitTime));

        LocalDateTime previousReleaseDate = null; // Per la prima release, non c'è una data precedente

        for (Release release : releases) {
            final LocalDateTime currentReleaseDate = release.getDate();

            for (RevCommit commit : allCommits) {
                // La data del commit in JGit è in secondi (epoch), la convertiamo in LocalDateTime
                LocalDateTime commitDate = LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        ZoneId.systemDefault()
                );

                // Caso della prima release: prendi tutti i commit fino alla data della release
                if (previousReleaseDate == null) {
                    if (!commitDate.isAfter(currentReleaseDate)) {
                        release.getCommitList().add(commit);
                    }
                } else {
                    // Release successive: prendi i commit nell'intervallo (previousReleaseDate, currentReleaseDate]
                    if (commitDate.isAfter(previousReleaseDate) && !commitDate.isAfter(currentReleaseDate)) {
                        release.getCommitList().add(commit);
                    }
                }
            }
            // Aggiorna la data precedente per la prossima iterazione
            previousReleaseDate = currentReleaseDate;
            Logger.getAnonymousLogger().log(Level.INFO, "Release {0}: trovati {1} commit.", new Object[]{release.getName(), release.getCommitList().size()});
        }
    }
}