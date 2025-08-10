package net.autominer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class MiningLogic {
    private final MinecraftClient client;
    private final Random random = new Random();
    private MiningArea miningArea;
    private final AutoMinerConfig config; // Hinzugefügt für Konfiguration

    // --- State Management ---
    private State currentState = State.IDLE;
    private enum State {
        IDLE,
        GO_TO_START,
        PATHFINDING,
        MOVING,
        MINING,
        REPOSITIONING,
        STUCK,
        FINISHED
    }

    // --- Pathing & Targeting ---
    private final Pathfinder pathfinder;
    private List<BlockPos> currentPath;
    private int pathIndex;
    private BlockPos targetBlock;
    private BlockPos standPos;
    private BlockPos currentlyBreaking = null;

    // --- Vorausschauende Pfadplanung ---
    private BlockPos nextTargetBlock;
    private List<BlockPos> nextPath;
    private BlockPos nextStandPos;


    // --- Block Management ---
    private List<BlockPos> blocksToMine;
    private final Set<BlockPos> skippedBlocks = new HashSet<>();

    // --- Timers & Delays ---
    private int actionDelay = 0;
    private int movementStuckTimer = 0;
    private static final int MAX_STUCK_TICKS = 80;
    private static final int MICRO_UNSTUCK_TICKS = 40;
    private boolean hasAttemptedMicroUnstuck = false;
    private static final double MAX_REACH_DISTANCE_SQUARED = 9.0;
    private static final int MAX_REWARD_CAP = 500;


    // --- Learning & Reward System ---
    private int totalRewards = 0;
    private long operationStartTime = 0;
    private static long lastOperationDuration = -1;
    private long layerStartTime = 0;
    private long lastLayerDuration = -1;
    private int previousLayerY = -1;
    private final TrainingData trainingData;
    private boolean isTrainingSession = false;
    private static int totalTrainingRewards = 0;
    private static String lastSessionFailReason = "None";
    private int currentTopLayerY = -1;
    private int miningStreak = 0;
    private int penaltyStreak = 0;


    // --- Logging ---
    private MiningLogger logger;

    // NEUES ENUM für das Ergebnis der Pfadfindung
    private enum PathFindResultType {
        SUCCESS,
        NO_PATH,
        SEARCH_LIMIT_REACHED
    }

    // NEUE Wrapper-Klasse für das Ergebnis
    private static class PathfinderResult {
        final PathFindResultType type;
        final List<BlockPos> path;
        final BlockPos standPos;

        PathfinderResult(PathFindResultType type, List<BlockPos> path, BlockPos standPos) {
            this.type = type;
            this.path = path;
            this.standPos = standPos;
        }
    }


    public MiningLogic(MinecraftClient client, TrainingData trainingData, AutoMinerConfig config) {
        this.client = client;
        this.pathfinder = new Pathfinder();
        this.trainingData = trainingData;
        this.config = config;
    }

    public void startTrainingSession() {
        if (client.getServer() == null || client.getServer().isRemote()) {
            client.player.sendMessage(Text.literal("§cTrainingsmodus ist nur in Einzelspielerwelten verfügbar."), false);
            return;
        }
        if (client.player == null || client.world == null) return;

        client.player.sendMessage(Text.literal("§bStarte Trainingseinheit... Generiere Testbereich."), false);
        client.player.sendMessage(Text.literal("§6Kumulative Trainingsbelohnungen: " + totalTrainingRewards), false);
        client.player.sendMessage(Text.literal("§6Grund für das Ende der letzten Sitzung: " + lastSessionFailReason), false);


        BlockPos playerPos = client.player.getBlockPos();
        Direction playerFacing = client.player.getHorizontalFacing();
        BlockPos areaStart = playerPos.offset(playerFacing, 5).add(-5, 0, -5);
        BlockPos areaEnd = areaStart.add(10, 5, 10);

        for (BlockPos pos : BlockPos.iterate(areaStart, areaEnd)) {
            if (random.nextDouble() < 0.2) {
                client.world.setBlockState(pos, Blocks.AIR.getDefaultState());
            } else if (random.nextDouble() < 0.05) {
                client.world.setBlockState(pos, Blocks.WATER.getDefaultState());
            } else {
                client.world.setBlockState(pos, Blocks.STONE.getDefaultState());
            }
        }
        
        MiningArea trainingArea = new MiningArea(areaStart, areaEnd);
        startMining(trainingArea, true);
    }

    public void startMining(MiningArea area) {
        startMining(area, false);
    }

    private void startMining(MiningArea area, boolean isTraining) {
        if (currentState != State.IDLE && currentState != State.FINISHED) {
            return;
        }
        this.miningArea = area;
        this.isTrainingSession = isTraining;
        resetPlanner();
        this.skippedBlocks.clear();
        
        this.logger = new MiningLogger();
        logger.log("Starting new mining operation. Training: " + isTraining);
        
        this.totalRewards = 0;
        this.miningStreak = 0;
        this.penaltyStreak = 0;
        this.operationStartTime = System.currentTimeMillis();
        this.layerStartTime = System.currentTimeMillis();
        this.lastLayerDuration = -1;
        this.previousLayerY = area.getEndPos().getY();
        this.currentTopLayerY = area.getEndPos().getY();

        this.currentState = State.GO_TO_START;
        area.setActive(true);
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§aAutoMiner gestartet. Berechne Startroute..."), false);
        }
    }

    public void stopMining() {
        if (currentState == State.FINISHED) return;
        
        concludeOperation("Stopped by user");

        if (miningArea != null) {
            miningArea.setActive(false);
        }
        if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
            client.interactionManager.cancelBlockBreaking();
        }
        currentState = State.FINISHED;
        resetPlanner();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§cAutoMiner gestoppt."), false);
        }
    }

    public void pause() {
        if (currentState != State.IDLE && currentState != State.FINISHED) {
            currentState = State.IDLE;
            logger.log("Mining paused.");
            if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) {
                client.interactionManager.cancelBlockBreaking();
            }
            resetPlanner();
            if(client.options.forwardKey.isPressed()){
                client.options.forwardKey.setPressed(false);
            }
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§eAutoMiner pausiert."), false);
            }
        }
    }

    public void resume() {
        if (currentState == State.IDLE && miningArea != null) {
            logger.log("Mining resumed.");
            if (targetBlock != null) {
                currentState = State.REPOSITIONING;
            } else {
                currentState = (blocksToMine == null || blocksToMine.isEmpty()) ? State.GO_TO_START : State.PATHFINDING;
            }
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§aAutoMiner fortgesetzt."), false);
            }
        }
    }
    
    private void resetPlanner() {
        targetBlock = null;
        currentPath = null;
        currentlyBreaking = null;
        nextTargetBlock = null;
        nextPath = null;
        nextStandPos = null;
    }

    public void tick() {
        if (client.player == null || client.world == null || miningArea == null || currentState == State.IDLE || currentState == State.FINISHED) {
            if (client.options.forwardKey.isPressed()) {
                client.options.forwardKey.setPressed(false);
            }
            return;
        }

        if (isTrainingSession && totalRewards < -100) {
            logger.log("Reward threshold of -100 reached. Restarting training.");
            restartTraining("Belohnungsschwelle erreicht");
            return;
        }

        if (actionDelay > 0) {
            actionDelay--;
            return;
        }

        if (currentState != State.MOVING) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }


        logger.log("Tick. Current state: " + currentState);
        switch (currentState) {
            case GO_TO_START:
                handleGoToStartState();
                break;
            case PATHFINDING:
                handlePathfindingState();
                break;
            case MOVING:
                handleMovingState();
                break;
            case MINING:
                handleMiningState();
                break;
            case REPOSITIONING:
                handleRepositioningState();
                break;
            case STUCK:
                handleStuckState();
                break;
            case IDLE:
            case FINISHED:
                 break;
        }
    }

    private void restartTraining(String reason) {
        lastSessionFailReason = reason;
        totalTrainingRewards += totalRewards;
        client.player.sendMessage(Text.literal("§cTraining fehlgeschlagen: " + reason + ". Neustart..."), false);
        
        if (miningArea != null) {
            for (BlockPos pos : BlockPos.iterate(miningArea.getStartPos(), miningArea.getEndPos())) {
                client.world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }
        }
        
        stopMining();
        startTrainingSession();
    }

    private void handleGoToStartState() {
        this.blocksToMine = generateCompleteMiningList(miningArea);
        if (blocksToMine.isEmpty()) {
            logger.log("No mineable blocks found in area. Finishing.");
            client.player.sendMessage(Text.literal("§cDer ausgewählte Bereich enthält keine abbaubaren Blöcke."), false);
            finishMining();
            return;
        }

        BlockPos firstBlock = findNextTarget();
        if (firstBlock == null) {
            logger.log("Could not find a valid starting block. Finishing.");
            client.player.sendMessage(Text.literal("§cKonnte keinen validen Startblock finden."), false);
            finishMining();
            return;
        }
        
        this.targetBlock = firstBlock;
        logger.log("New target acquired: " + firstBlock.toShortString());
        AutoMinerClient.sendActionBarMessage(Text.literal("§aStartziel: " + firstBlock.toShortString() + ". Positioniere..."));
        
        this.currentState = State.REPOSITIONING;
    }

    private void handlePathfindingState() {
        targetBlock = findNextTarget();

        if (targetBlock == null) {
            if (!skippedBlocks.isEmpty()) {
                logger.log("No primary targets left. Retrying " + skippedBlocks.size() + " skipped blocks.");
                client.player.sendMessage(Text.literal("§6Keine primären Ziele mehr. Versuche übersprungene Blöcke..."), false);
                blocksToMine = new ArrayList<>(skippedBlocks);
                skippedBlocks.clear();
                blocksToMine.sort(Comparator.comparingDouble(b -> b.getSquaredDistance(client.player.getPos())));
                targetBlock = findNextTarget();
                if (targetBlock == null) {
                    finishMining();
                    return;
                }
            } else {
                finishMining();
                return;
            }
        }
        logger.log("New target acquired: " + targetBlock.toShortString());
        currentState = State.REPOSITIONING;
    }
    
    private void handleMovingState() {
        if (currentPath == null || pathIndex >= currentPath.size()) {
            logger.log("Path complete. Switching to PATHFINDING.");
            currentState = State.PATHFINDING;
            return;
        }
    
        BlockPos nextPos = currentPath.get(pathIndex);
        Vec3d playerPosVec = client.player.getPos();
        Vec3d nextPosVec = Vec3d.ofCenter(nextPos);
    
        double dx = playerPosVec.x - nextPosVec.x;
        double dz = playerPosVec.z - nextPosVec.z;
        double horizontalDistanceSq = dx * dx + dz * dz;
    
        double dy = playerPosVec.y - nextPos.getY();
    
        if (horizontalDistanceSq < 0.25 && dy >= 0 && dy < 1.5) {
            logger.log("Reached path node " + pathIndex + " at " + nextPos.toShortString());
            pathIndex++;
            if (pathIndex >= currentPath.size()) {
                currentState = State.PATHFINDING;
                return;
            }
            nextPos = currentPath.get(pathIndex); 
        }

        BlockState nextState = client.world.getBlockState(nextPos);
        BlockState nextHeadState = client.world.getBlockState(nextPos.up());

        boolean feetBlocked = !nextState.getCollisionShape(client.world, nextPos).isEmpty();
        boolean headBlocked = !nextHeadState.getCollisionShape(client.world, nextPos.up()).isEmpty();

        if (feetBlocked || headBlocked) {
            BlockPos blockToBreak = feetBlocked ? nextPos : nextPos.up();
            logger.log("Next step requires breaking an obstacle at " + blockToBreak.toShortString());

            if (client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(blockToBreak)) > MAX_REACH_DISTANCE_SQUARED) {
                logger.log("Obstacle block out of reach. This should not happen. Stuck.");
                currentState = State.STUCK;
                return;
            }
            
            smoothLookAt(blockToBreak);
            mineBlock(blockToBreak);
            return; 
        }

        moveTowards(nextPos);
        
        movementStuckTimer++;
        if (movementStuckTimer > MAX_STUCK_TICKS) {
            currentState = State.STUCK;
        }
    }

    private void handleMiningState() {
        if (targetBlock == null) {
            currentState = State.PATHFINDING;
            return;
        }

        BlockState targetState = client.world.getBlockState(targetBlock);
        if (targetState.isAir() || targetState.getBlock() instanceof FluidBlock) {
            logger.log("Target block " + targetBlock.toShortString() + " is now air or fluid. Skipping without penalty.");
            blocksToMine.remove(targetBlock);
            resetPlanner();
            currentState = State.PATHFINDING;
            return;
        }
        
        if (!miningArea.isWithinArea(targetBlock)) {
            totalRewards--;
            trainingData.getRewardMemory().put(targetBlock, trainingData.getRewardMemory().getOrDefault(targetBlock, 0) - 5);
            logger.log("PENALTY: Mined outside area. Total rewards: " + totalRewards);
            AutoMinerClient.sendActionBarMessage(Text.literal("§c-1 Reward (Außerhalb des Bereichs) §7| §eGesamt: §f" + totalRewards));
        }
        
        BlockPos blockAbove = targetBlock.up();
        if (isFallingBlock(blockAbove)) {
            logger.log("SAFETY: Falling block detected above target. Prioritizing it.");
            client.player.sendMessage(Text.literal("§eGefahr! Baue zuerst den fallenden Block darüber ab."), false);
            targetBlock = blockAbove;
            if (!blocksToMine.contains(targetBlock)) {
                 blocksToMine.add(0, targetBlock);
            }
        }

        if (client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetBlock)) > MAX_REACH_DISTANCE_SQUARED) {
            logger.log("Target out of reach. Repositioning.");
            currentState = State.REPOSITIONING;
            return;
        }
        
        if (targetBlock.equals(client.player.getBlockPos().down()) && client.world.isAir(targetBlock.down())) {
             logger.log("SAFETY: Unsafe to mine block under feet. Skipping.");
             client.player.sendMessage(Text.literal("§eUnsicher, den Block unter den Füßen abzubauen. Überspringe."), false);
             skipCurrentTarget();
             currentState = State.PATHFINDING;
             return;
        }

        smoothLookAt(targetBlock);
        mineBlock(targetBlock);

        prepareForNextTarget();

        if (client.world.isAir(targetBlock)) {
            movementStuckTimer = 0;
            miningStreak++;
            long calculatedReward = (long) (5 * Math.pow(2, Math.min(miningStreak - 1, 30)));
            int reward = (int) Math.min(calculatedReward, MAX_REWARD_CAP);
            
            if (standPos != null) {
                reward++; 
            }
            
            totalRewards += reward;
            trainingData.getRewardMemory().put(targetBlock, trainingData.getRewardMemory().getOrDefault(targetBlock, 0) + reward);
            logger.log("REWARD: Mined block (Streak: " + miningStreak + "). +" + reward + " rewards. Total: " + totalRewards);
            AutoMinerClient.sendActionBarMessage(Text.literal("§d+" + reward + " §fRewards (Streak x" + miningStreak + ") §7| §eGesamt: §f" + totalRewards));
            
            penaltyStreak = 0;
            blocksToMine.remove(targetBlock);

            BlockPos reachableSkippedBlock = findReachableSkippedBlock();
            if (reachableSkippedBlock != null) {
                logger.log("Found a previously skipped block that is now reachable: " + reachableSkippedBlock.toShortString());
                skippedBlocks.remove(reachableSkippedBlock);
                blocksToMine.add(0, reachableSkippedBlock); 
            }

            BlockPos nextBlock = peekNextTarget();
            if (nextBlock != null && nextBlock.getY() < previousLayerY) {
                if (isLayerClear(previousLayerY)) {
                    long now = System.currentTimeMillis();
                    long currentLayerDuration = now - layerStartTime;
                    
                    if (lastLayerDuration != -1 && currentLayerDuration < lastLayerDuration) {
                        totalRewards += 2;
                        logger.log("REWARD: Layer completed faster. Total rewards: " + totalRewards);
                        AutoMinerClient.sendActionBarMessage(Text.literal("§b+2 Rewards (Ebene schneller!) §7| §eGesamt: §f" + totalRewards));
                    } else if (lastLayerDuration == -1) {
                        totalRewards += 2;
                        logger.log("REWARD: First layer complete. Total rewards: " + totalRewards);
                        AutoMinerClient.sendActionBarMessage(Text.literal("§b+2 Rewards (Ebene fertig) §7| §eGesamt: §f" + totalRewards));
                    } else {
                         logger.log("No layer bonus (slower than last).");
                    }
                    
                    lastLayerDuration = currentLayerDuration;
                    layerStartTime = now;
                    previousLayerY = nextBlock.getY();
                    updateCurrentTopLayer();
                } else {
                    logger.log("Layer " + previousLayerY + " not fully cleared. No layer bonus.");
                }
            }
            
            if (nextPath != null) {
                currentPath = nextPath;
                standPos = nextStandPos;
                pathIndex = 0;
                movementStuckTimer = 0;
                currentState = State.MOVING;
                
                targetBlock = nextTargetBlock;
                nextPath = null;
                nextStandPos = null;
                nextTargetBlock = null;

            } else {
                resetPlanner();
                currentState = State.PATHFINDING;
            }
            
            actionDelay = 0;
        }
    }

    private void handleRepositioningState() {
        if (targetBlock == null) {
            currentState = State.PATHFINDING;
            return;
        }
        if (client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetBlock)) <= MAX_REACH_DISTANCE_SQUARED && isSafeToStandOn(client.player.getBlockPos())) {
            currentState = State.MINING;
            return;
        }

        logger.log("Finding path to reachable position for target: " + targetBlock.toShortString());
        PathfinderResult result = findPathToReachablePosition(targetBlock);

        switch (result.type) {
            case SUCCESS:
                if (result.path.isEmpty()) { 
                    logger.log("Already at a reachable position. Switching to MINING.");
                    currentState = State.MINING;
                } else {
                    logger.log("Path found. Destination: " + result.standPos.toShortString() + ". Switching to MOVING.");
                    this.standPos = result.standPos;
                    this.currentPath = result.path;
                    this.pathIndex = 0;
                    this.movementStuckTimer = 0;
                    this.hasAttemptedMicroUnstuck = false;
                    this.currentState = State.MOVING;
                }
                break;
            
            case NO_PATH:
                logger.log("No path found to any reachable position for target " + targetBlock.toShortString() + ". Miner is likely trapped.");
                failOperation("Gefangen! Kein Pfad zum Ziel gefunden.");
                break;
                
            case SEARCH_LIMIT_REACHED:
                logger.log("Pathfinder reached search limit. The area might be too complex or the limit too low.");
                failOperation("Pfad zu komplex! Erhöhe das Pfadfinder-Limit in den Einstellungen.");
                break;
        }
    }
    
    private void handleStuckState() {
        logger.log("Movement failed. Recalculating path to current target: " + (targetBlock != null ? targetBlock.toShortString() : "null"));
        
        PathfinderResult result = findPathToReachablePosition(targetBlock);
        
        if (result.type == PathFindResultType.SUCCESS) {
            logger.log("New path found. Resuming movement.");
            this.standPos = result.standPos;
            this.currentPath = result.path;
            this.pathIndex = 0;
            this.movementStuckTimer = 0;
            this.hasAttemptedMicroUnstuck = false;
            this.currentState = State.MOVING;
        } else {
            logger.log("Recalculation failed. Miner is inescapably stuck.");
            failOperation("Gefangen! Kein Ausweg gefunden.");
        }
    }
    
    private void failOperation(String reason) {
        String feedback = "§cAutoMiner gestoppt: " + reason + " Bitte bewege den Spieler zurück in den Minenbereich und starte neu.";
        client.player.sendMessage(Text.literal(feedback), false);
        logger.log("OPERATION FAILED: " + reason);
        stopMining();
    }
    
    public void onFallDamage() {
        if (isMining()) {
            penaltyStreak++;
            long calculatedPenalty = (long) (2000 * Math.pow(2, Math.min(penaltyStreak - 1, 30)));
            int penalty = (int) Math.min(calculatedPenalty, MAX_REWARD_CAP);
            totalRewards -= penalty;
            logger.log("PENALTY: Took fall damage. Penalty: " + penalty + ". Total rewards: " + totalRewards);
            AutoMinerClient.sendActionBarMessage(Text.literal("§c-" + penalty + " §fRewards (Fallschaden!) §7| §eGesamt: §f" + totalRewards));
        }
    }
    
    private void prepareForNextTarget() {
        if (nextPath != null || nextTargetBlock != null) return; 
        BlockPos peekedTarget = peekNextTarget();
        if (peekedTarget == null) return;

        if (client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(peekedTarget)) <= MAX_REACH_DISTANCE_SQUARED && isSafeToStandOn(client.player.getBlockPos())) {
            logger.log("Next target is already in reach. Preparing instant transition.");
            this.nextTargetBlock = peekedTarget;
            this.nextPath = new ArrayList<>(); 
            this.nextStandPos = client.player.getBlockPos(); 
            return; 
        }

        this.nextTargetBlock = peekedTarget;
        PathfinderResult result = findPathToReachablePosition(nextTargetBlock);
        if (result.type == PathFindResultType.SUCCESS) {
            this.nextPath = result.path;
            this.nextStandPos = result.standPos;
        } else {
            this.nextTargetBlock = null;
        }
    }
    
    private BlockPos findReachableSkippedBlock() {
        if (skippedBlocks.isEmpty()) {
            return null;
        }
        for (BlockPos skippedPos : skippedBlocks) {
            if (client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(skippedPos)) <= MAX_REACH_DISTANCE_SQUARED) {
                return skippedPos;
            }
        }
        return null;
    }

    private BlockPos peekNextTarget() {
        if (blocksToMine == null) return null;
        for (BlockPos pos : blocksToMine) {
            if (targetBlock != null && pos.equals(targetBlock)) continue;
            if (!client.world.isAir(pos) && client.world.getBlockState(pos).getBlock() != Blocks.BEDROCK) {
                return pos;
            }
        }
        return null;
    }
    
    private void skipCurrentTarget() {
        if (targetBlock != null) {
            blocksToMine.remove(targetBlock);
            skippedBlocks.add(targetBlock);
            resetPlanner();
        }
    }
    
    private void finishMining() {
        concludeOperation("Complete");
        stopMining();
        AutoMinerClient.onMiningCompleted();
    }

    private void concludeOperation(String reason) {
        long now = System.currentTimeMillis();
        long currentOperationDuration = now - operationStartTime;

        if (reason.equals("Complete")) {
            if (lastOperationDuration != -1 && currentOperationDuration < lastOperationDuration) {
                totalRewards += 2;
                client.player.sendMessage(Text.literal("§a+2 Rewards (Operation Schneller!)."), false);
            } else if (lastOperationDuration == -1) {
                totalRewards += 2;
                client.player.sendMessage(Text.literal("§a+2 Rewards (Operation Abgeschlossen)."), false);
            } else {
                client.player.sendMessage(Text.literal("§6Kein finaler Bonus (Langsamer als die letzte Operation)."), false);
            }
        }
        
        lastOperationDuration = currentOperationDuration;
        if (isTrainingSession) {
            totalTrainingRewards += totalRewards;
        }
        client.player.sendMessage(Text.literal("§2Operation " + reason + "! Finale Belohnung: " + totalRewards), false);
        
        if (logger != null) {
            logger.log("Operation finished. Reason: " + reason + ". Final rewards: " + totalRewards);
            logger.close();
            logger = null;
        }
        trainingData.save();
    }

    private BlockPos findNextTarget() {
        if (blocksToMine == null || blocksToMine.isEmpty()) return null;
        return blocksToMine.get(0);
    }
    
    private boolean isIgnorableVegetation(BlockState state) {
        return state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS) || state.isOf(Blocks.FERN) || state.isOf(Blocks.LARGE_FERN);
    }
    
    private List<BlockPos> generateCompleteMiningList(MiningArea area) {
        List<BlockPos> allBlocks = new ArrayList<>();
        boolean reverse = false;
        for (int y = area.getEndPos().getY(); y >= area.getStartPos().getY(); y--) {
            allBlocks.addAll(generateSnakeLayer(area, y, reverse));
            reverse = !reverse;
        }

        if (client.world == null) return allBlocks;

        return allBlocks.stream().filter(pos -> {
            BlockState state = client.world.getBlockState(pos);
            if (state.isAir() || state.isOf(Blocks.BEDROCK)) {
                return false;
            }
            if (isIgnorableVegetation(state)) {
                BlockPos below = pos.down();
                if (area.isWithinArea(below) && !client.world.getBlockState(below).isAir()) {
                    logger.log("Skipping ignorable vegetation at " + pos.toShortString() + " as its support block will be mined.");
                    return false;
                }
            }
            return true; 
        }).collect(Collectors.toList());
    }

    private List<BlockPos> generateSnakeLayer(MiningArea area, int y, boolean reverse) {
        List<BlockPos> layerBlocks = new ArrayList<>();
        int minX = area.getStartPos().getX();
        int minZ = area.getStartPos().getZ();
        int maxX = area.getEndPos().getX();
        int maxZ = area.getEndPos().getZ();
        for (int x = minX; x <= maxX; x++) {
            List<BlockPos> row = new ArrayList<>();
            if ((x - minX) % 2 == (reverse ? 1 : 0)) {
                for (int z = minZ; z <= maxZ; z++) row.add(new BlockPos(x, y, z));
            } else {
                for (int z = maxZ; z >= minZ; z--) row.add(new BlockPos(x, y, z));
            }
            layerBlocks.addAll(row);
        }
        return layerBlocks;
    }

    private void moveTowards(BlockPos target) {
        PlayerEntity player = client.player;
        if (player == null) return;

        client.options.forwardKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.backKey.setPressed(false);

        Vec3d playerPos = player.getPos();
        Vec3d targetCenter = Vec3d.ofCenter(target);
        double dx = targetCenter.x - playerPos.x;
        double dz = targetCenter.z - playerPos.z;
        float targetYaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        
        float playerYaw = MathHelper.wrapDegrees(player.getYaw());
        float deltaYaw = MathHelper.wrapDegrees(targetYaw - playerYaw);

        smoothLookAt(target);

        if (target.getY() > player.getY() && player.isOnGround()) {
            player.jump();
        }

        if (Math.abs(deltaYaw) > 20.0F) { 
             if (Math.abs(deltaYaw) < 90.0F) {
                client.options.forwardKey.setPressed(true);
             }
            if (deltaYaw > 0) {
                client.options.rightKey.setPressed(true);
            } else {
                client.options.leftKey.setPressed(true);
            }
        } else {
            client.options.forwardKey.setPressed(true);
        }
    }
    
    private void smoothLookAt(BlockPos pos) {
        PlayerEntity player = client.player;
        Vec3d targetCenter = Vec3d.ofCenter(pos);
        double dx = targetCenter.x - player.getX(), dy = targetCenter.y - player.getEyeY(), dz = targetCenter.z - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDistance));
        player.setYaw(MathHelper.lerp(0.5F, player.getYaw(), targetYaw));
        player.setPitch(MathHelper.lerp(0.5F, player.getPitch(), targetPitch));
    }

    private void mineBlock(BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) {
            logger.log("WARN: mineBlock called on non-solid block " + pos.toShortString() + ". This should have been caught earlier. Skipping.");
            skipCurrentTarget();
            currentState = State.PATHFINDING;
            return;
        }
        if (!pos.equals(currentlyBreaking)) currentlyBreaking = pos;
        client.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
    }

    private PathfinderResult findPathToReachablePosition(BlockPos target) {
        return pathfinder.findPath(client.player.getBlockPos(), target, true, true);
    }

    private boolean isSafeToStandOn(BlockPos pos) {
        World world = client.world;
        if (world == null) return false;
        if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) return false;
        
        return pathfinder.isPassable(pos);
    }
    
    private boolean isFallingBlock(BlockPos pos) {
        if (client.world == null) {
            return false;
        }
        return client.world.getBlockState(pos).getBlock() instanceof FallingBlock;
    }
    
    private void updateCurrentTopLayer() {
        if (blocksToMine == null || blocksToMine.isEmpty()) {
            return;
        }
        int max_y = Integer.MIN_VALUE;
        for (BlockPos pos : blocksToMine) {
            if (pos.getY() > max_y) {
                max_y = pos.getY();
            }
        }
        if (max_y != Integer.MIN_VALUE && max_y != this.currentTopLayerY) {
            this.currentTopLayerY = max_y;
            logger.log("Top layer updated to Y=" + this.currentTopLayerY);
            client.player.sendMessage(Text.literal("§dTop layer updated to Y=" + this.currentTopLayerY), false);
        }
    }
    
    private boolean isLayerClear(int y) {
        for (BlockPos pos : blocksToMine) {
            if (pos.getY() == y) {
                return false;
            }
        }
        for (BlockPos pos : skippedBlocks) {
            if (pos.getY() == y) {
                return false;
            }
        }
        return true;
    }

    public boolean isMining() { return currentState != State.IDLE && currentState != State.FINISHED; }
    public boolean isPaused() { return currentState == State.IDLE && miningArea != null; }
    public MiningArea getCurrentArea() { return miningArea; }
    public BlockPos getTargetBlock() { return targetBlock; }
    public List<BlockPos> getCurrentPath() { return currentPath; }
    public BlockPos getCurrentlyBreaking() { return currentlyBreaking; }

    private class Pathfinder {
        
        public PathfinderResult findPath(BlockPos start, BlockPos goal, boolean canBreakBlocks, boolean findStandPos) {
            int maxSearchNodes = config.maxSearchNodes;
            
            PriorityQueue<Node> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
            Map<BlockPos, Node> allNodes = new HashMap<>();
            Node startNode = new Node(start, null, 0, getHeuristic(start, goal));
            openSet.add(startNode);
            allNodes.put(start, startNode);
            int iterations = 0;
            
            while (!openSet.isEmpty()) {
                if (iterations >= maxSearchNodes) {
                    return new PathfinderResult(PathFindResultType.SEARCH_LIMIT_REACHED, null, null);
                }
                iterations++;

                Node current = openSet.poll();

                if (findStandPos) {
                    if (isSafeToStandOn(current.pos) && Vec3d.ofCenter(current.pos).add(0, 1.62, 0).squaredDistanceTo(Vec3d.ofCenter(goal)) <= MAX_REACH_DISTANCE_SQUARED) {
                        return new PathfinderResult(PathFindResultType.SUCCESS, reconstructPath(current), current.pos);
                    }
                } else {
                    if (current.pos.equals(goal)) {
                        return new PathfinderResult(PathFindResultType.SUCCESS, reconstructPath(current), current.pos);
                    }
                }

                addNeighbors(current, goal, openSet, allNodes, canBreakBlocks);
            }
            
            return new PathfinderResult(PathFindResultType.NO_PATH, null, null);
        }

        private void addNeighbors(Node current, BlockPos end, PriorityQueue<Node> openSet, Map<BlockPos, Node> allNodes, boolean canBreakBlocks) {
            BlockPos currentPos = current.pos;
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = currentPos.offset(dir);
                
                int deltaY = neighborPos.getY() - currentPos.getY();
                if (deltaY > 1) { 
                    continue;
                }
                
                if (deltaY == 1) {
                    if (client.world.getBlockState(currentPos.up(2)).blocksMovement() || client.world.getBlockState(neighborPos.down()).getCollisionShape(client.world, neighborPos.down()).isEmpty()) {
                        continue;
                    }
                }
                
                if (deltaY <= 0) {
                    BlockPos groundPos = neighborPos.down();
                    if (client.world.getBlockState(groundPos).getCollisionShape(client.world, groundPos).isEmpty()) {
                        BlockPos deepGroundPos = groundPos.down();
                        if (client.world.getBlockState(deepGroundPos).getCollisionShape(client.world, deepGroundPos).isEmpty()) {
                            continue;
                        }
                    }
                }

                BlockState feetState = client.world.getBlockState(neighborPos);
                BlockState headState = client.world.getBlockState(neighborPos.up());

                boolean feetPassable = feetState.getCollisionShape(client.world, neighborPos).isEmpty();
                boolean headPassable = headState.getCollisionShape(client.world, neighborPos.up()).isEmpty();
                
                double cost = 1.0; 
                boolean isPossible = true;

                if (!feetPassable) { 
                    if (canBreakBlocks && miningArea.isWithinArea(neighborPos) && !isIgnorableVegetation(feetState)) {
                        cost += 10.0; 
                    } else if (!isIgnorableVegetation(feetState)) {
                        isPossible = false;
                    }
                }

                if (!headPassable) { 
                    if (canBreakBlocks && miningArea.isWithinArea(neighborPos.up()) && !isIgnorableVegetation(headState)) {
                        cost += 10.0; 
                        if(!blocksToMine.contains(neighborPos.up())) {
                           blocksToMine.add(neighborPos.up());
                        }
                    } else if (!isIgnorableVegetation(headState)) {
                        isPossible = false;
                    }
                }
                
                if(isPossible) {
                    addNode(current, neighborPos, cost, end, openSet, allNodes);
                }
            }
        }

        private boolean isReachableFrom(BlockPos from, BlockPos to) {
            return Vec3d.ofCenter(from).add(0, 1.62, 0).squaredDistanceTo(Vec3d.ofCenter(to)) <= MAX_REACH_DISTANCE_SQUARED;
        }

        private void addNode(Node parent, BlockPos pos, double cost, BlockPos end, PriorityQueue<Node> openSet, Map<BlockPos, Node> allNodes) {
            int rewardModifier = trainingData.getRewardMemory().getOrDefault(pos, 0);
            double modifiedCost = cost - (rewardModifier * 0.1);
            if(modifiedCost <= 0) modifiedCost = 0.1;

            double tentativeGCost = parent.gCost + modifiedCost;
            Node node = allNodes.get(pos);
            if (node == null || tentativeGCost < node.gCost) {
                if (node == null) {
                    node = new Node(pos);
                    allNodes.put(pos, node);
                }
                node.parent = parent;
                node.gCost = tentativeGCost;
                node.hCost = getHeuristic(pos, end);
                node.fCost = node.gCost + node.hCost;
                if (!openSet.contains(node)) openSet.add(node);
            }
        }
        
        public boolean isPassable(BlockPos pos) {
            World world = client.world;
            if (world == null) return false;
            return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() &&
                   world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
        }

        private double getHeuristic(BlockPos from, BlockPos to) {
            return Math.sqrt(from.getSquaredDistance(to));
        }
        private List<BlockPos> reconstructPath(Node endNode) {
            List<BlockPos> path = new ArrayList<>();
            Node current = endNode;
            while (current != null) {
                path.add(current.pos);
                current = current.parent;
            }
            Collections.reverse(path);
            return path;
        }

        private class Node {
            BlockPos pos; Node parent; double gCost, hCost, fCost;
            Node(BlockPos pos) { this.pos = pos; }
            Node(BlockPos pos, Node parent, double gCost, double hCost) {
                this.pos = pos; this.parent = parent; this.gCost = gCost; this.hCost = hCost; this.fCost = gCost + hCost;
            }
            @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; return Objects.equals(pos, ((Node) o).pos); }
            @Override public int hashCode() { return Objects.hash(pos); }
        }
    }
}