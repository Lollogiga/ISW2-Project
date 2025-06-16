package it.project.utils;

import org.eclipse.jgit.api.Git;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RepoFactory {
    private static Git gitInstance = null;
    private static final Object lock = new Object();

    private RepoFactory() {}

    /**
     * Metodo Factory che usa ConfigManager per ottenere le informazioni
     * sul progetto e sul repository. Implementa un Singleton thread-safe.
     */
    public static Git getGit() throws IOException {
        synchronized (lock) {
            if (gitInstance == null) {
                String projectName = ConfigManager.getProjectName();
                String repoUrl = ConfigManager.getRepositoryUrl();

                if (projectName == null || repoUrl == null) {
                    throw new IOException("PROJECT_NAME o REPOSITORY_URL non trovati in config.properties");
                }

                // Usa il nome del progetto per creare una cartella locale unica
                String localPath = "tmp/" + projectName.toLowerCase();
                File localRepoDir = new File(localPath);

                if (localRepoDir.exists() && localRepoDir.list() != null && localRepoDir.list().length > 0) {
                    Logger.getAnonymousLogger().log(Level.INFO, "Apertura repository locale esistente: {0}", localPath);
                    gitInstance = Git.open(localRepoDir);
                } else {
                    Logger.getAnonymousLogger().log(Level.INFO, "Clonazione repository da {0} a {1}", new Object[]{repoUrl, localPath});
                    try {
                        gitInstance = Git.cloneRepository()
                                .setURI(repoUrl)
                                .setDirectory(localRepoDir)
                                .call();
                    } catch (Exception e) {
                        throw new IOException("Errore durante la clonazione del repository", e);
                    }
                }
            }
        }
        return gitInstance;
    }
}