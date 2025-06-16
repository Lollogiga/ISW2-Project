package it.project.utils;

import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigManager {
    private static final Properties properties = new Properties();

    static {
        // Carica il file di configurazione una sola volta all'avvio
        try (InputStream input = ConfigManager.class.getClassLoader().getResourceAsStream("configuration.properties")) {
            if (input == null) {
                Logger.getAnonymousLogger().log(Level.SEVERE, "Impossibile trovare il file config.properties in src/main/utils");
            } else {
                properties.load(input);
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Errore nel caricamento del file di configurazione", e);
        }
    }

    private ConfigManager() {}

    public static String getProjectName() {
        return properties.getProperty("PROJECT_NAME");
    }

    public static String getRepositoryUrl() {
        return properties.getProperty("REPOSITORY_PATH");
    }
}
