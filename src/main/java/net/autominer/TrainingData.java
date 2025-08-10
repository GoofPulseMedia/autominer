package net.autominer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class TrainingData {
    private static final File TRAINING_FILE = new File("training_data.json");
    private Map<BlockPos, Integer> rewardMemory = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public TrainingData() {
        load();
    }

    public void load() {
        if (TRAINING_FILE.exists()) {
            try (Reader reader = new FileReader(TRAINING_FILE)) {
                Type type = new TypeToken<Map<String, Integer>>() {}.getType();
                Map<String, Integer> stringMap = gson.fromJson(reader, type);
                if (stringMap != null) {
                    rewardMemory = stringMap.entrySet().stream()
                        .collect(Collectors.toMap(
                            // FIX: Replaced removed fromShortString method with manual parsing
                            entry -> blockPosFromString(entry.getKey()),
                            Map.Entry::getValue
                        ));
                }
            } catch (IOException | NumberFormatException e) {
                System.err.println("[AutoMiner] Failed to load or parse training data.");
                e.printStackTrace();
            }
        }
    }

    public void save() {
        try (Writer writer = new FileWriter(TRAINING_FILE)) {
            Map<String, Integer> stringMap = rewardMemory.entrySet().stream()
                .collect(Collectors.toMap(
                    entry -> entry.getKey().toShortString(),
                    Map.Entry::getValue
                ));
            gson.toJson(stringMap, writer);
        } catch (IOException e) {
            System.err.println("[AutoMiner] Failed to save training data.");
            e.printStackTrace();
        }
    }

    // --- NEW: Helper method to parse BlockPos from string ---
    private BlockPos blockPosFromString(String s) {
        String[] parts = s.split(", ");
        int x = Integer.parseInt(parts[0]);
        int y = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        return new BlockPos(x, y, z);
    }

    public Map<BlockPos, Integer> getRewardMemory() {
        return this.rewardMemory;
    }
}
