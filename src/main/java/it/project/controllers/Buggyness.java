// Sostituisci completamente la tua classe Buggyness con questa.
package it.project.controllers;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import it.project.entities.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Buggyness {

    private final Git git;
    private final Repository repository;

    public Buggyness(Git git) {
        this.git = git;
        this.repository = git.getRepository();
    }

    /**
     * NUOVA LOGICA: Etichetta i metodi basandosi sulla lista di "Affected Versions" di ogni ticket.
     * @param releasesToLabel Il sottoinsieme di release i cui metodi devono essere etichettati (per training o testing).
     * @param ticketsToUse Il sottoinsieme di ticket da usare per l'etichettatura.
     */
    public void calculate(List<Release> releasesToLabel, List<Ticket> ticketsToUse) throws IOException {
        // 1. Reset della bugginess per le release che stiamo per etichettare.
        // Fondamentale per non contaminare le iterazioni del Walk-Forward.
        for (Release release : releasesToLabel) {
            for (JavaClass jc : release.getJavaClassList()) {
                for (JavaMethod jm : jc.getMethods()) {
                    jm.setBuggy(false);
                }
            }
        }

        for (Ticket ticket : ticketsToUse) {
            List<Release> affectedReleases = ticket.getAffectedVersionsList();
            if (affectedReleases == null || affectedReleases.isEmpty()) {
                continue;
            }

            // Per ogni release affetta dal bug...
            for (Release affectedRelease : affectedReleases) {
                // ...etichetta i metodi SOLO se quella release è nel nostro set corrente (training o testing).
                // Questo è il legame cruciale con la logica del Walk-Forward.
                if (releasesToLabel.contains(affectedRelease)) {
                    labelMethodsInRelease(affectedRelease, ticket);
                }
            }
        }
    }

    /**
     * Helper che etichetta i metodi in una specifica release basandosi sui commit di fix di un ticket.
     * @param release La release da etichettare.
     * @param ticket Il ticket che contiene i commit di fix.
     */
    private void labelMethodsInRelease(Release release, Ticket ticket) throws IOException {
        List<RevCommit> fixCommits = ticket.getCommitList();
        if (fixCommits == null || fixCommits.isEmpty()) return;

        for (RevCommit fixCommit : fixCommits) {
            if (fixCommit.getParentCount() == 0) continue;
            RevCommit parentCommit = fixCommit.getParent(0);

            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(this.repository);
                df.setDetectRenames(true);
                List<DiffEntry> diffs = df.scan(parentCommit.getTree(), fixCommit.getTree());

                for (DiffEntry diff : diffs) {
                    String changedFilePath = diff.getNewPath();
                    if (!changedFilePath.endsWith(".java")) continue;

                    // Usiamo la nostra logica precisa per trovare i NOMI dei metodi modificati.
                    Set<String> buggyMethodNames = getBuggyMethodNames(df, diff, parentCommit);

                    // Etichettiamo i metodi corrispondenti nella release affetta.
                    for (JavaClass javaClass : release.getJavaClassList()) {
                        if (javaClass.getPath().equals(changedFilePath)) {
                            for (JavaMethod javaMethod : javaClass.getMethods()) {
                                if (buggyMethodNames.contains(javaMethod.getName())) {
                                    javaMethod.setBuggy(true);
                                }
                            }
                            break; // Ottimizzazione: trovata la classe, passiamo al prossimo file del diff.
                        }
                    }
                }
            } catch (Exception e) {
                Logger.getAnonymousLogger().log(Level.SEVERE, "Could not process commit " + fixCommit.getId(), e);
            }
        }
    }

    private Set<String> getBuggyMethodNames(DiffFormatter df, DiffEntry diff, RevCommit parentCommit) throws IOException {
        Set<String> methodNames = new HashSet<>();
        EditList editList = df.toFileHeader(diff).toEditList();
        String oldFileContent = getFileContent(parentCommit, diff.getOldPath());
        if (oldFileContent == null) return methodNames;

        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(oldFileContent);
        if (!result.isSuccessful() || result.getResult().isEmpty()) return methodNames;
        CompilationUnit cu = result.getResult().get();

        for (Edit edit : editList) {
            int startLine = edit.getBeginA() + 1;
            int endLine = edit.getEndA();
            List<CallableDeclaration> callables = cu.findAll(CallableDeclaration.class);
            for (CallableDeclaration callable : callables) {
                if (isOverlapping(callable, startLine, endLine)) {
                    methodNames.add(callable.getNameAsString());
                }
            }
        }
        return methodNames;
    }

    private boolean isOverlapping(Node node, int startLine, int endLine) {
        Optional<Integer> nodeStartLineOpt = node.getBegin().map(p -> p.line);
        Optional<Integer> nodeEndLineOpt = node.getEnd().map(p -> p.line);
        if (nodeStartLineOpt.isEmpty() || nodeEndLineOpt.isEmpty()) return false;
        int nodeStart = nodeStartLineOpt.get();
        int nodeEnd = nodeEndLineOpt.get();
        return Math.max(nodeStart, startLine) <= Math.min(nodeEnd, endLine);
    }

    private String getFileContent(RevCommit commit, String path) throws IOException {
        try (TreeWalk treeWalk = TreeWalk.forPath(repository, path, commit.getTree())) {
            if (treeWalk != null) {
                ObjectId blobId = treeWalk.getObjectId(0);
                ObjectLoader loader = repository.open(blobId);
                return new String(loader.getBytes());
            }
        }
        return null;
    }
}