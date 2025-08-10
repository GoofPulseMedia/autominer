// filename: TrainingData.java
package net.autominer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class TrainingData {
    private static final File MOD_DIRECTORY = new File("autominer");
    private static final File TRAINING_DATA_FILE = new File(MOD_DIRECTORY, "training_data.json");
    // GEÄNDERT: Wir entfernen enableComplexMapKeySerialization, da wir es manuell handhaben.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Map<BlockPos, Integer> rewardMemory;

    public TrainingData() {
        this.rewardMemory = load();
    }

    public Map<BlockPos, Integer> getRewardMemory() {
        return rewardMemory;
    }

    public void addReward(BlockPos pos, int reward) {
        rewardMemory.merge(pos, reward, Integer::sum);
    }

    // GEÄNDERT: Die save()-Methode konvertiert BlockPos zu Strings.
    public void save() {
        try {
            Files.createDirectories(MOD_DIRECTORY.toPath());

            // 1. Erstelle eine temporäre Map mit String-Keys.
            Map<String, Integer> stringKeyMap = new HashMap<>();
            for (Map.Entry<BlockPos, Integer> entry : this.rewardMemory.entrySet()) {
                // 2. Konvertiere jeden BlockPos in einen einfachen String.
                BlockPos pos = entry.getKey();
                String key = pos.getX() + "," + pos.getY() + "," + pos.getZ();
                stringKeyMap.put(key, entry.getValue());
            }

            try (Writer writer = new FileWriter(TRAINING_DATA_FILE)) {
                // 3. Speichere die String-Map.
                GSON.toJson(stringKeyMap, writer);
                System.out.println("[AutoMiner] Training data saved successfully.");
            }
        } catch (IOException e) {
            System.err.println("[AutoMiner] Error saving training data.");
            e.printStackTrace();
        }
    }

    // GEÄNDERT: Die load()-Methode konvertiert Strings zurück zu BlockPos.
    private Map<BlockPos, Integer> load() {
        Map<BlockPos, Integer> loadedMemory = new HashMap<>();
        if (TRAINING_DATA_FILE.exists()) {
            try (Reader reader = new FileReader(TRAINING_DATA_FILE)) {
                // 1. Definiere den Typ als Map<String, Integer>.
                Type type = new TypeToken<Map<String, Integer>>() {}.getType();
                Map<String, Integer> stringKeyMap = GSON.fromJson(reader, type);

                if (stringKeyMap != null) {
                    // 2. Konvertiere die geladene Map zurück.
                    for (Map.Entry<String, Integer> entry : stringKeyMap.entrySet()) {
                        BlockPos pos = blockPosFromString(entry.getKey());
                        if (pos != null) {
                            loadedMemory.put(pos, entry.getValue());
                        }
                    }
                    System.out.println("[AutoMiner] Training data loaded successfully.");
                }
            } catch (Exception e) { // Catche alle Fehler (IOException, JsonSyntaxException, etc.)
                System.err.println("[AutoMiner] Error reading training data, creating new data.");
                e.printStackTrace();
            }
        }
        return loadedMemory;
    }

    /**
     * NEU: Eine Hilfsmethode, um einen String zurück in ein BlockPos-Objekt zu parsen.
     * @param key Der String im Format "x,y,z"
     * @return ein BlockPos-Objekt oder null bei einem Fehler.
     */
    private BlockPos blockPosFromString(String key) {
        try {
            String[] parts = key.split(",");
            if (parts.length == 3) {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                return new BlockPos(x, y, z);
            }
        } catch (NumberFormatException e) {
            System.err.println("[AutoMiner] Could not parse BlockPos from key: " + key);
        }
        return null;
    }
}