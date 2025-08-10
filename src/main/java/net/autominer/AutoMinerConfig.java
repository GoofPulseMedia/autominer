// filename: AutoMinerConfig.java
package net.autominer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AutoMinerConfig {
    private static final File CONFIG_FILE = new File("config/autominer.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Standardwert, falls keine Konfigurationsdatei vorhanden ist.
    public int maxSearchNodes = 80000;

    public int getPathfindingLimit() {
        return maxSearchNodes;
    }

    public void setPathfindingLimit(int limit) {
        this.maxSearchNodes = limit;
    }

    public static AutoMinerConfig load() {
        if (CONFIG_FILE.exists()) {
            try (Reader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, AutoMinerConfig.class);
            } catch (IOException e) {
                System.err.println("[AutoMiner] Konnte die Konfigurationsdatei nicht lesen, verwende Standardwerte.");
                e.printStackTrace();
            }
        }
        // Wenn die Datei nicht existiert, erstelle eine neue Konfiguration mit Standardwerten und speichere sie.
        AutoMinerConfig config = new AutoMinerConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            // Stelle sicher, dass das "config"-Verzeichnis existiert.
            Files.createDirectories(Paths.get("config"));
            try (Writer writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[AutoMiner] Konnte die Konfigurationsdatei nicht speichern.");
            e.printStackTrace();
        }
    }
}