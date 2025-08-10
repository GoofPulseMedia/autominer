// filename: AutoMinerConfig.java
package net.autominer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class AutoMinerConfig {
    private static final File MOD_DIRECTORY = new File("autominer");
    private static final File CONFIG_FILE = new File(MOD_DIRECTORY, "autominer.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int maxSearchNodes = 80000;
    
    // NEU: Steuert, wie viele Pfadfinderknoten pro Tick verarbeitet werden, um Lags zu verhindern.
    public int nodesPerTick = 4000;
    
    // Maximale Anzahl von Ticks bevor ein "Stuck" Zustand erkannt wird
    public int maxStuckTicks = 60;

    public int getPathfindingLimit() {
        return maxSearchNodes;
    }

    public void setPathfindingLimit(int limit) {
        this.maxSearchNodes = limit;
    }

    public static AutoMinerConfig load() {
        if (CONFIG_FILE.exists()) {
            try (Reader reader = new FileReader(CONFIG_FILE)) {
                AutoMinerConfig config = GSON.fromJson(reader, AutoMinerConfig.class);
                return config != null ? config : new AutoMinerConfig();
            } catch (IOException e) {
                System.err.println("[AutoMiner] Could not read config file, using default values.");
                e.printStackTrace();
            }
        }
        AutoMinerConfig config = new AutoMinerConfig();
        config.save();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(MOD_DIRECTORY.toPath());
            try (Writer writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[AutoMiner] Could not save config file.");
            e.printStackTrace();
        }
    }
}