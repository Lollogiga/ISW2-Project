package it.project.controllers;

import it.project.entities.JavaClass;
import it.project.entities.JavaMethod;
import it.project.entities.Release;
import it.project.entities.Ticket;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

//TODO: Probabilmente sto calcolando male i ticekt su testing che li vuole tutti io gli sto passando poche informaizoni!

public class Buggyness {
    private final Git git;

    public Buggyness(Git git) {
        this.git = git;
    }

    /**
     * Labelling methods as buggy using tickets.
     * Se un bug fix modifica un metodo, vuol dire che quel metodo era buggy (uso IV per sapere quanto indietro tornare)
     **/

    public void calculate(List<Release> releasesToLabel, List<Ticket> ticketsToUse) throws IOException {
        //1. Reset
        for (Release release : releasesToLabel) {
            for (JavaClass jc : release.getJavaClassList()) {
                for (JavaMethod jm : jc.getMethods()) {
                    jm.setBuggy(false);
                }
            }
        }

        //2. Ticket iteration:
        // 2. Itera sui ticket
        for (Ticket ticket : ticketsToUse) {
            Release injectedVersion = ticket.getInjectedVersion();
            if (injectedVersion == null || !releasesToLabel.contains(injectedVersion)) {
                continue;
            }

            List<RevCommit> fixCommits = ticket.getCommitList();
            if (fixCommits == null || fixCommits.isEmpty()) continue;

            for (RevCommit fixCommit : fixCommits) {
                setBuggyMethodsFromCommit(fixCommit, injectedVersion);
            }
        }
    }

    private void setBuggyMethodsFromCommit(RevCommit fixCommit, Release injectedVersion) throws IOException {
        try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            Repository repo = this.git.getRepository();
            df.setRepository(repo);
            df.setDetectRenames(true);

            if (fixCommit.getParentCount() == 0) return;
            RevCommit parentCommit = fixCommit.getParent(0);

            List<DiffEntry> diffs = df.scan(parentCommit.getTree(), fixCommit.getTree());

            for (DiffEntry diff : diffs) {
                String changedFilePath = diff.getNewPath();
                if (!changedFilePath.endsWith(".java")) continue;

                // Trova i metodi specifici modificati in questo file
                Set<String> buggyMethodNames = getBuggyMethodNames(df, diff, parentCommit);

                // Etichetta i metodi trovati nella Injected Version
                for (JavaClass jc : injectedVersion.getJavaClassList()) {
                    if (jc.getPath().equals(changedFilePath)) {
                        for (JavaMethod jm : jc.getMethods()) {
                            if (buggyMethodNames.contains(jm.getName())) {
                                jm.setBuggy(true);
                            }
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Could not compute diff for commit " + fixCommit.getId(), e);
        }
    }

    private Set<String> getBuggyMethodNames(DiffFormatter df, DiffEntry diff, RevCommit parentCommit) throws IOException {
        Set<String> methodNames = new HashSet<>();
        // Ottieni la lista delle modifiche (hunks) con i numeri di riga
        EditList editList = df.toFileHeader(diff).toEditList();

        // Carica il contenuto del file *PRIMA* della modifica (dal commit genitore)
        String oldFileContent = getFileContent(parentCommit, diff.getOldPath());
        if (oldFileContent == null) return methodNames;

        // Analizza il vecchio file con JavaParser
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(oldFileContent);
        if (!result.isSuccessful() || result.getResult().isEmpty()) return methodNames;
        CompilationUnit cu = result.getResult().get();

        // Per ogni modifica nel diff...
        for (Edit edit : editList) {
            int startLine = edit.getBeginA() + 1; // Le linee modificate nel vecchio file
            int endLine = edit.getEndA();

            // Trova tutti i metodi/costruttori che si sovrappongono a questo range di linee
            List<CallableDeclaration> callables = cu.findAll(CallableDeclaration.class);
            for (CallableDeclaration callable : callables) {
                if (isOverlapping(callable, startLine, endLine)) {
                    methodNames.add(callable.getNameAsString());
                }
            }
        }
        return methodNames;
    }

    // Controlla se il range di un metodo si sovrappone a un range di modifica
    private boolean isOverlapping(Node node, int startLine, int endLine) {
        Optional<Integer> nodeStartLineOpt = node.getBegin().map(p -> p.line);
        Optional<Integer> nodeEndLineOpt = node.getEnd().map(p -> p.line);

        if (nodeStartLineOpt.isEmpty() || nodeEndLineOpt.isEmpty()) {
            return false;
        }

        int nodeStart = nodeStartLineOpt.get();
        int nodeEnd = nodeEndLineOpt.get();

        // Logica di sovrapposizione di due intervalli [a,b] e [c,d]
        return Math.max(nodeStart, startLine) <= Math.min(nodeEnd, endLine);
    }

    // Helper per ottenere il contenuto di un file da un commit specifico
    private String getFileContent(RevCommit commit, String path) throws IOException {
        try (TreeWalk treeWalk = TreeWalk.forPath(git.getRepository(), path, commit.getTree())) {
            if (treeWalk != null) {
                ObjectId blobId = treeWalk.getObjectId(0);
                ObjectLoader loader = git.getRepository().open(blobId);
                return new String(loader.getBytes());
            }
        }
        return null;
    }


}
