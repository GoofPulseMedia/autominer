package net.autominer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class AutoMinerClient implements ClientModInitializer {
    private static boolean isSelectingArea = false;
    private static BlockPos startPos = null;
    private static BlockPos endPos = null;
    private static MiningArea currentArea = null;
    private static MiningLogic miningLogic;
    private static boolean rightClickPressed = false;
    private static boolean isMiningCompleted = true;

    private static TrainingData trainingData;

    public static KeyBinding confirmBinding, selectBinding, startBinding, stopBinding, pauseBinding, resumeBinding, cancelBinding, trainBinding;

    private static AutoMinerConfig config;

    // NEU: Variable zur Verfolgung von Schaden
    private int lastHurtTime = 0;

    @Override
    public void onInitializeClient() {
        config = AutoMinerConfig.load(); // Load config first
        trainingData = new TrainingData();
        miningLogic = new MiningLogic(MinecraftClient.getInstance(), trainingData, config);
        registerKeyBindings();

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    
    WorldRenderEvents.END.register(context -> {
        AreaRenderer.render(context.matrixStack(), context.consumers(), context.camera(), miningLogic, startPos, endPos, isSelectingArea);
    });
}

    private void registerKeyBindings() {
        selectBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F1, "category.autominer.general"));
        startBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.start", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F2, "category.autominer.general"));
        stopBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F3, "category.autominer.general"));
        pauseBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F4, "category.autominer.general"));
        resumeBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.resume", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F5, "category.autominer.general"));
        cancelBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.cancel", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "category.autominer.general"));
        trainBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.train", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.autominer.general"));
        confirmBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autominer.confirm", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, "category.autominer.general"));
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null) return;
        
        // --- ANFANG: NEUER CODE FÜR FALLSCHADEN-ERKENNUNG ---
        // hurtTime wird auf 10 gesetzt, wenn Schaden erlitten wird, und zählt dann herunter.
        // Wir prüfen, ob der Wert gerade erst gesetzt wurde.
        if (client.player.hurtTime > this.lastHurtTime) {
            // Eine Fallstrecke von mehr als 3 Blöcken verursacht Schaden.
            // Wir prüfen, ob der Miner aktiv war, um versehentliche Auslösungen zu vermeiden.
            if (miningLogic.isMining() && client.player.fallDistance > 3.0F) {
                miningLogic.onFallDamage();
            }
        }
        // Speichere den aktuellen hurtTime-Wert für den nächsten Tick-Vergleich.
        this.lastHurtTime = client.player.hurtTime;
        // --- ENDE: NEUER CODE FÜR FALLSCHADEN-ERKENNUNG ---

        while (selectBinding.wasPressed()) selectArea();
        while (startBinding.wasPressed()) startMining();
        while (stopBinding.wasPressed()) stopMining();
        while (pauseBinding.wasPressed()) pauseMining();
        while (resumeBinding.wasPressed()) resumeMining();
        while (cancelBinding.wasPressed()) cancelSelection();
        while (trainBinding.wasPressed()) startTraining();
        
        while (confirmBinding.wasPressed()) {
            if (isSelectingArea && startPos != null && endPos != null) {
                confirmSelection(client);
            }
        }

        if (isSelectingArea) {
            handleAreaSelectionInput(client);
        }

        if (miningLogic != null && miningLogic.isMining()) {
            miningLogic.tick();
        }
    }

    private void handleAreaSelectionInput(MinecraftClient client) {
        boolean rightClickCurrentlyPressed = client.options.useKey.isPressed();
        if (rightClickCurrentlyPressed && !rightClickPressed && client.crosshairTarget != null && client.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            handleAreaSelection(client, ((BlockHitResult) client.crosshairTarget).getBlockPos());
        }
        rightClickPressed = rightClickCurrentlyPressed;
    }

    private void handleAreaSelection(MinecraftClient client, BlockPos pos) {
        if (startPos == null || !startPos.equals(pos) && endPos != null) {
            startPos = pos;
            endPos = null;
            client.player.sendMessage(Text.literal("§aStartposition gesetzt: " + pos.toShortString() + ". Rechtsklicke für Endposition."), false);
        } else if (endPos == null && !pos.equals(startPos)) {
            endPos = pos;
            client.player.sendMessage(Text.literal("§6Endposition gesetzt: " + pos.toShortString() + ". Drücke Enter zur Bestätigung."), false);
        }
    }

    private void confirmSelection(MinecraftClient client) {
        currentArea = new MiningArea(startPos, endPos);
        client.player.sendMessage(Text.literal("§2Bereich bestätigt! Drücke 'Start', um zu beginnen."), false);
        isSelectingArea = false;
        isMiningCompleted = false;
    }

    public static void selectArea() {
        startPos = null;
        endPos = null;
        isSelectingArea = true;
        MinecraftClient.getInstance().player.sendMessage(Text.literal("§aBereichsauswahl aktiv. Rechtsklicke auf Blöcke."), false);
    }

    public static void startMining() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (currentArea == null) {
            client.player.sendMessage(Text.literal("§cKein Bereich ausgewählt. Benutze 'Select'."), false);
            return;
        }
        if (miningLogic.isMining()) {
            client.player.sendMessage(Text.literal("§cMiner läuft bereits."), false);
        } else if (miningLogic.isPaused()) {
            client.player.sendMessage(Text.literal("§eMiner ist pausiert. Benutze 'Resume'."), false);
        } else {
            miningLogic.startMining(currentArea);
        }
    }

    public static void startTraining() {
        if (miningLogic.isMining() || miningLogic.isPaused()) {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§cStoppe den aktuellen Miner, bevor du das Training startest."), false);
            return;
        }
        miningLogic.startTrainingSession();
    }

    public static void stopMining() {
        if (miningLogic.isMining() || miningLogic.isPaused()) {
            miningLogic.stopMining();
            isMiningCompleted = true;
            currentArea = null;
        } else {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§cKein Miner aktiv."), false);
        }
    }

    public static void pauseMining() {
        if (miningLogic.isMining() && !miningLogic.isPaused()) {
            miningLogic.pause();
        } else {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§cMiner läuft nicht oder ist bereits pausiert."), false);
        }
    }

    public static void resumeMining() {
        if (miningLogic.isPaused()) {
            miningLogic.resume();
        } else {
            MinecraftClient.getInstance().player.sendMessage(Text.literal("§cMiner ist nicht pausiert."), false);
        }
    }

    public static void cancelSelection() {
        isSelectingArea = false;
        startPos = null;
        endPos = null;
        if (!miningLogic.isMining() && !miningLogic.isPaused()) {
            currentArea = null;
        }
        isMiningCompleted = true;
        MinecraftClient.getInstance().player.sendMessage(Text.literal("§cAuswahl abgebrochen."), false);
    }

    public static void onMiningCompleted() {
        isMiningCompleted = true;
        currentArea = null;
    }
    
    /**
     * NEU: Sendet eine formatierte Textnachricht an die Action Bar des Spielers.
     * @param message Die zu sendende Textnachricht.
     */
    public static void sendActionBarMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            // Der zweite Parameter 'true' leitet die Nachricht an die Action Bar.
            client.player.sendMessage(message, true);
        }
    }

    public static void handleAutomineCommand(String message) {
        String[] parts = message.split(" ");
        if (parts.length < 2) return;
        String command = parts[1].toLowerCase();
        
        switch (command) {
            case "select": selectArea(); break;
            case "start": startMining(); break;
            case "stop": stopMining(); break;
            case "pause": pauseMining(); break;
            case "resume": resumeMining(); break;
            case "cancel": cancelSelection(); break;
            case "train": startTraining(); break;
            case "config": 
                if (parts.length >= 3) {
                    handleConfigCommand(parts);
                } else {
                    showConfigHelp();
                }
                break;
        }
    }

    private static void handleConfigCommand(String[] parts) {
        String subCommand = parts[2].toLowerCase();
        switch (subCommand) {
            case "show":
                showCurrentConfig();
                break;
            case "set":
                if (parts.length >= 5) {
                    setConfigValue(parts[3], parts[4]);
                } else {
                    sendPrivateMessage("§cUsage: +automine config set <setting> <value>");
                    sendPrivateMessage("§7Available settings: pathfindingLimit");
                }
                break;
            case "reset":
                resetConfig();
                break;
            default:
                showConfigHelp();
                break;
        }
    }

    private static void showConfigHelp() {
        sendPrivateMessage("§6AutoMiner Configuration Commands:");
        sendPrivateMessage("§e+automine config show §7- Show current settings");
        sendPrivateMessage("§e+automine config set <setting> <value> §7- Change a setting");
        sendPrivateMessage("§e+automine config reset §7- Reset to defaults");
        sendPrivateMessage("§7Available settings: §epathfindingLimit §7(number)");
    }

    private static void showCurrentConfig() {
        sendPrivateMessage("§6Current AutoMiner Configuration:");
        sendPrivateMessage("§ePathfinding Limit: §f" + config.getPathfindingLimit());
        sendPrivateMessage("§7Use §e+automine config set pathfindingLimit <number> §7to change");
    }

    private static void setConfigValue(String setting, String value) {
        switch (setting.toLowerCase()) {
            case "pathfindinglimit":
                try {
                    int limit = Integer.parseInt(value);
                    if (limit < 10 || limit > 10000) {
                        sendPrivateMessage("§cPathfinding limit must be between 10 and 10000");
                        return;
                    }
                    config.setPathfindingLimit(limit);
                    config.save();
                    sendPrivateMessage("§aPathfinding limit set to: §f" + limit);
                } catch (NumberFormatException e) {
                    sendPrivateMessage("§cInvalid number: " + value);
                }
                break;
            default:
                sendPrivateMessage("§cUnknown setting: " + setting);
                sendPrivateMessage("§7Available settings: §epathfindingLimit");
                break;
        }
    }

    private static void resetConfig() {
        config.setPathfindingLimit(1000); // Default value
        config.save();
        sendPrivateMessage("§aConfiguration reset to defaults!");
        sendPrivateMessage("§ePathfinding Limit: §f" + config.getPathfindingLimit());
    }

    private static void sendPrivateMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    public static MiningLogic getMiningLogic() {
        return miningLogic;
    }
        public static AutoMinerConfig getConfig() {
        return config;
    }
}