// filename: MiningLogic.java
package net.autominer;

// Notwendige Importe
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import java.lang.reflect.Field;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class MiningLogic {
    private final MinecraftClient client;
    private final Random random = new Random();
    private MiningArea miningArea;
    private final AutoMinerConfig config;
    private int currentMiningY;

    private enum State {
        IDLE,
        PATHFINDING,
        CALCULATING_PATH,
        MOVING,
        MINING,
        REPOSITIONING,
        STUCK,
        FINISHED
    }
    private State currentState = State.IDLE;

    private enum PathFindResultType {
        SUCCESS,
        NO_PATH,
        SEARCH_LIMIT_REACHED
    }

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

    private final Pathfinder pathfinder;
    private List<BlockPos> currentPath;
    private int pathIndex;
    private BlockPos targetBlock;
    private BlockPos standPos;
    private BlockPos currentlyBreaking = null;
    private List<BlockPos> blocksToMine;
    private final Set<BlockPos> skippedBlocks = new HashSet<>();
    private int actionDelay = 0;
    private int movementStuckTimer = 0;
    private int repositioningStuckTimer = 0; // New timer for repositioning cycles
    private static final int MAX_REPOSITIONING_CYCLES = 10; // Max cycles before declaring stuck
    private static final double MAX_REACH_DISTANCE_SQUARED = 25.0; // 5 Blöcke Reichweite
    
    // Helper methods for safe inventory slot access
    private static Field selectedSlotField = null;
    
    private int getSelectedSlot(PlayerInventory inventory) {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            return selectedSlotField.getInt(inventory);
        } catch (Exception e) {
            // Fallback to reflection with obfuscated name if needed
            try {
                Field field = PlayerInventory.class.getDeclaredField("field_7545");
                field.setAccessible(true);
                return field.getInt(inventory);
            } catch (Exception e2) {
                logger.log("WARNUNG: Konnte selectedSlot nicht lesen, verwende 0 als Fallback");
                return 0;
            }
        }
    }
    
    private void setSelectedSlot(PlayerInventory inventory, int slot) {
        try {
            if (selectedSlotField == null) {
                selectedSlotField = PlayerInventory.class.getDeclaredField("selectedSlot");
                selectedSlotField.setAccessible(true);
            }
            selectedSlotField.setInt(inventory, slot);
        } catch (Exception e) {
            // Fallback to reflection with obfuscated name if needed
            try {
                Field field = PlayerInventory.class.getDeclaredField("field_7545");
                field.setAccessible(true);
                field.setInt(inventory, slot);
            } catch (Exception e2) {
                logger.log("WARNUNG: Konnte selectedSlot nicht setzen");
            }
        }
    }
    private static final int MAX_REWARD_CAP = 500;
    private static final int MAX_PENALTY_CAP = -500;
    private int totalRewards = 0;
    private final TrainingData trainingData;
    private boolean isTrainingSession = false;
    private int miningStreak = 0;
    private int penaltyStreak = 0;
    private MiningLogger logger;

    public MiningLogic(MinecraftClient client, TrainingData trainingData, AutoMinerConfig config) {
        this.client = client;
        this.pathfinder = new Pathfinder();
        this.trainingData = trainingData;
        this.config = config;
    }

    public void startMining(MiningArea area) {
        startMining(area, false);
    }
    
    public void startTrainingSession() {
        if (client.getServer() == null || client.getServer().isRemote()) {
            client.player.sendMessage(Text.literal("§cTraining mode is only available in single-player worlds."), false);
            return;
        }
        if (client.player == null || client.world == null) return;

        client.player.sendMessage(Text.literal("§bStarting training session... Generating test area."), false);

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

    private void startMining(MiningArea area, boolean isTraining) {
        if (currentState != State.IDLE && currentState != State.FINISHED) return;
        this.miningArea = area;
        this.isTrainingSession = isTraining;
        resetPlanner();
        this.skippedBlocks.clear();
        this.blocksToMine = new ArrayList<>();
        this.logger = new MiningLogger();
        logger.log("Starting new mining operation. Training: " + isTraining);
        this.totalRewards = 0;
        this.miningStreak = 0;
        this.penaltyStreak = 0;
        this.currentMiningY = area.getEndPos().getY();
        this.repositioningStuckTimer = 0; // Reset stuck timer
        this.currentState = State.PATHFINDING;
        area.setActive(true);
        if (client.player != null) client.player.sendMessage(Text.literal("§aAutoMiner started. Planning first layer..."), false);
    }

    public void stopMining() {
        if (currentState == State.FINISHED) return;
        concludeOperation("Stopped by user");
        if (miningArea != null) miningArea.setActive(false);
        if (client.interactionManager != null && client.interactionManager.isBreakingBlock()) client.interactionManager.cancelBlockBreaking();
        currentState = State.FINISHED;
        resetPlanner();
        if (client.player != null) client.player.sendMessage(Text.literal("§cAutoMiner stopped."), false);
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
                client.player.sendMessage(Text.literal("§eAutoMiner paused."), false);
            }
        }
    }

    public void resume() {
        if (currentState == State.IDLE && miningArea != null) {
            logger.log("Mining resumed.");
            currentState = State.PATHFINDING;
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§aAutoMiner resumed."), false);
            }
        }
    }

    public void tick() {
        if (client.player == null || client.world == null || miningArea == null || currentState == State.IDLE || currentState == State.FINISHED) {
            if (client.options.forwardKey.isPressed()) client.options.forwardKey.setPressed(false);
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
            case PATHFINDING: handlePathfindingState(); break;
            case CALCULATING_PATH: handleCalculatingPathState(); break;
            case MOVING: handleMovingState(); break;
            case MINING: handleMiningState(); break;
            case REPOSITIONING: handleRepositioningState(); break;
            case STUCK: handleStuckState(); break;
            default: break;
        }
    }

    private void handlePathfindingState() {
        if (blocksToMine.isEmpty()) {
            if (!generateNextLayerBasedOnPosition()) {
                if(skippedBlocks.isEmpty()) {
                    finishMining();
                    return;
                }
                blocksToMine.addAll(skippedBlocks);
                skippedBlocks.clear();
            }
        }
        
        targetBlock = findNextTarget();

        if (targetBlock == null) {
            logger.log("No primary targets left. Retrying " + skippedBlocks.size() + " skipped blocks.");
            if (skippedBlocks.isEmpty()) {
                finishMining();
            } else {
                blocksToMine.addAll(skippedBlocks);
                skippedBlocks.clear();
                targetBlock = findNextTarget();
                if (targetBlock == null) {
                    finishMining();
                } else {
                     currentState = State.REPOSITIONING;
                }
            }
            return;
        }
        logger.log("New target acquired: " + targetBlock.toShortString());
        currentState = State.REPOSITIONING;
    }
    
    private void handleCalculatingPathState() {
        PathfinderResult result = pathfinder.continuePath(targetBlock, true);
        
        if (result == null) return;

        switch (result.type) {
            case SUCCESS:
                if (result.path.isEmpty()) {
                    currentState = State.MINING;
                } else {
                    this.standPos = result.standPos;
                    this.currentPath = result.path;
                    this.pathIndex = 0;
                    this.movementStuckTimer = 0;
                    this.currentState = State.MOVING;
                }
                break;
            case NO_PATH:
            case SEARCH_LIMIT_REACHED:
                String reason = result.type == PathFindResultType.NO_PATH ? "No path found" : "Pathfinder limit reached";
                logger.log(reason + " to target " + targetBlock.toShortString() + ". Skipping it.");
                client.player.sendMessage(Text.literal("§e" + reason + ", skipping block."), false);
                applyPenalty(-5, "Unreachable target");
                skipCurrentTarget();
                currentState = State.PATHFINDING;
                break;
        }
    }

    private void handleRepositioningState() {
        if (targetBlock == null) {
            currentState = State.PATHFINDING;
            return;
        }
        
        // Check if current position is good enough for mining
        double distanceSquared = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetBlock));
        boolean hasLineOfSight = hasLineOfSight(targetBlock);
        boolean isSafePosition = isSafeToStandOn(client.player.getBlockPos());
        
        if (distanceSquared <= MAX_REACH_DISTANCE_SQUARED && isSafePosition && hasLineOfSight) {
            repositioningStuckTimer = 0; // Reset timer when we can mine
            currentState = State.MINING;
            return;
        }

        // Try to find a simple, close position first
        BlockPos currentPos = client.player.getBlockPos();
        List<BlockPos> simplePositions = new ArrayList<>();
        
        // Check positions in a small radius around current position
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue; // Skip current position
                
                BlockPos candidate = currentPos.add(dx, 0, dz);
                Vec3d candidateEye = Vec3d.ofCenter(candidate).add(0, 1.62, 0);
                
                // Check if this position can reach the target
                double candidateDistance = candidateEye.squaredDistanceTo(Vec3d.ofCenter(targetBlock));
                if (candidateDistance <= MAX_REACH_DISTANCE_SQUARED && isSafeToStandOn(candidate)) {
                    // Test line of sight from this position
                    HitResult hitResult = client.world.raycast(new RaycastContext(
                        candidateEye, Vec3d.ofCenter(targetBlock),
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        client.player
                    ));
                    
                    if (hitResult.getType() == HitResult.Type.MISS || 
                        (hitResult.getType() == HitResult.Type.BLOCK && 
                         ((BlockHitResult) hitResult).getBlockPos().equals(targetBlock))) {
                        simplePositions.add(candidate);
                    }
                }
            }
        }
        
        // Try simple positions first (just move to adjacent blocks)
        if (!simplePositions.isEmpty()) {
            // Sort by distance to current position
            simplePositions.sort((a, b) -> Double.compare(
                currentPos.getSquaredDistance(a), 
                currentPos.getSquaredDistance(b)
            ));
            
            BlockPos target = simplePositions.get(0);
            pathfinder.startPath(currentPos, target, false);
            currentState = State.CALCULATING_PATH;
            return;
        }
        
        // If simple positions don't work, try the more complex pathfinding
        pathfinder.startPath(currentPos, targetBlock, true);
        currentState = State.CALCULATING_PATH;
    }
    
    private void handleMovingState() {
        if (currentPath == null || pathIndex >= currentPath.size()) {
            logger.log("Path complete. Switching to MINING.");
            currentState = State.MINING; 
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
            movementStuckTimer = 0; 
            if (pathIndex >= currentPath.size()) {
                currentState = State.MINING;
                return;
            }
        }

        BlockState nextState = client.world.getBlockState(nextPos);
        BlockState nextHeadState = client.world.getBlockState(nextPos.up());

        boolean feetBlocked = !nextState.getCollisionShape(client.world, nextPos).isEmpty();
        boolean headBlocked = !nextHeadState.getCollisionShape(client.world, nextPos.up()).isEmpty();

        if ((feetBlocked && !isIgnorableVegetation(nextState)) || (headBlocked && !isIgnorableVegetation(nextHeadState))) {
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
        if (movementStuckTimer > config.maxStuckTicks) {
            currentState = State.STUCK;
        }
    }

    private void handleMiningState() {
        if (targetBlock == null) {
            currentState = State.PATHFINDING;
            return;
        }

        BlockState targetState = client.world.getBlockState(targetBlock);
        if (targetState.isAir() || targetState.getBlock() instanceof FluidBlock || shouldIgnoreVegetationAt(targetBlock)) {
            blocksToMine.remove(targetBlock);
            resetPlanner();
            currentState = State.PATHFINDING;
            return;
        }

        double distanceSquared = client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetBlock));
        boolean hasLineOfSight = hasLineOfSight(targetBlock);
        
        if (distanceSquared > MAX_REACH_DISTANCE_SQUARED || !hasLineOfSight) {
            repositioningStuckTimer++;
            logger.log("Need repositioning. Distance check: " + (distanceSquared <= MAX_REACH_DISTANCE_SQUARED) + 
                      ", Line of sight: " + hasLineOfSight + ", Cycle: " + repositioningStuckTimer);
            
            if (repositioningStuckTimer > MAX_REPOSITIONING_CYCLES) {
                logger.log("Stuck in repositioning cycle. Declaring stuck state.");
                currentState = State.STUCK;
                return;
            }
            currentState = State.REPOSITIONING;
            return;
        }
        
        // Reset repositioning timer if we can mine
        repositioningStuckTimer = 0;
        
        if (!selectAndSwitchToBestTool(targetState)) {
            return;
        }

        smoothLookAt(targetBlock);
        mineBlock(targetBlock);

        if (client.world.isAir(targetBlock)) {
            miningStreak++;
            penaltyStreak = 0; // Reset penalty streak on successful mining
            int reward = (int) Math.min(5 * Math.pow(2, Math.min(miningStreak - 1, 30)), MAX_REWARD_CAP);
            totalRewards += reward;
            trainingData.getRewardMemory().put(targetBlock, trainingData.getRewardMemory().getOrDefault(targetBlock, 0) + reward);
            AutoMinerClient.sendActionBarMessage(Text.literal("§d+" + reward + " §fRewards (Streak x" + miningStreak + ") §7| §eTotal: §f" + totalRewards));
            
            blocksToMine.remove(targetBlock);

            BlockPos reachableSkippedBlock = findReachableSkippedBlock();
            if (reachableSkippedBlock != null) {
                skippedBlocks.remove(reachableSkippedBlock);
                blocksToMine.add(0, reachableSkippedBlock); 
            }
            
            resetPlanner();
            currentState = State.PATHFINDING;
        }
    }
    
    private void resetPlanner() {
        targetBlock = null;
        currentPath = null;
        currentlyBreaking = null;
    }

    private boolean generateNextLayerBasedOnPosition() {
        if (currentMiningY < miningArea.getStartPos().getY()) {
            logger.log("All layers mined. Finishing operation.");
            return false;
        }
        logger.log("Generating plan for layer Y=" + currentMiningY);
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos start = miningArea.getStartPos();
        BlockPos end = miningArea.getEndPos();
        BlockPos corner1 = new BlockPos(start.getX(), currentMiningY, start.getZ());
        BlockPos corner2 = new BlockPos(end.getX(), currentMiningY, start.getZ());
        BlockPos corner3 = new BlockPos(start.getX(), currentMiningY, end.getZ());
        BlockPos corner4 = new BlockPos(end.getX(), currentMiningY, end.getZ());
        BlockPos closestCorner = corner1;
        double minDistanceSq = playerPos.getSquaredDistance(closestCorner);
        for (BlockPos corner : new BlockPos[]{corner2, corner3, corner4}) {
            double distSq = playerPos.getSquaredDistance(corner);
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq;
                closestCorner = corner;
            }
        }
        logger.log("Closest corner for the new layer is: " + closestCorner.toShortString());
        boolean reverseX = closestCorner.getX() == end.getX();
        boolean reverseZ = closestCorner.getZ() == end.getZ();
        List<BlockPos> newLayer = generateSnakeLayer(miningArea, currentMiningY, reverseX, reverseZ);

        this.blocksToMine.addAll(newLayer.stream().filter(pos -> {
            BlockState state = client.world.getBlockState(pos);
            return !state.isAir() && !state.isOf(Blocks.BEDROCK) && !shouldIgnoreVegetationAt(pos);
        }).collect(Collectors.toList()));
        
        currentMiningY--;
        return !this.blocksToMine.isEmpty();
    }
    
    private void handleStuckState() {
        logger.log("Movement failed. Skipping unreachable target: " + (targetBlock != null ? targetBlock.toShortString() : "null"));
        
        // Apply penalty using the new penalty system
        applyPenalty(-10, "Stuck");
        
        // Skip current target and find a more accessible one
        skipCurrentTarget();
        
        // Reset all timers
        repositioningStuckTimer = 0;
        movementStuckTimer = 0;
        
        // Try to find an easily accessible target to rebuild momentum
        BlockPos accessibleTarget = findMostAccessibleTarget();
        if (accessibleTarget != null && !accessibleTarget.equals(targetBlock)) {
            // Prioritize the accessible target by moving it to the front
            blocksToMine.remove(accessibleTarget);
            blocksToMine.add(0, accessibleTarget);
            logger.log("Prioritizing accessible target: " + accessibleTarget.toShortString());
        }
        
        currentState = State.PATHFINDING;
    }
    
    private BlockPos findMostAccessibleTarget() {
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos bestTarget = null;
        double bestScore = Double.MAX_VALUE;
        
        // Look through nearby blocks to mine
        for (BlockPos block : blocksToMine.subList(0, Math.min(blocksToMine.size(), 10))) {
            double distance = playerPos.getSquaredDistance(block);
            if (distance > 100) continue; // Skip very distant blocks
            
            // Check if it's easily accessible (good line of sight and close)
            Vec3d playerEye = client.player.getEyePos();
            Vec3d blockCenter = Vec3d.ofCenter(block);
            
            if (distance <= MAX_REACH_DISTANCE_SQUARED) {
                HitResult hitResult = client.world.raycast(new RaycastContext(
                    playerEye, blockCenter,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    client.player
                ));
                
                boolean goodLineOfSight = false;
                if (hitResult.getType() == HitResult.Type.MISS) {
                    goodLineOfSight = true;
                } else if (hitResult.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult blockHit = (BlockHitResult) hitResult;
                    if (blockHit.getBlockPos().equals(block)) {
                        goodLineOfSight = true;
                    }
                }
                
                if (goodLineOfSight) {
                    double score = distance;
                    if (score < bestScore) {
                        bestScore = score;
                        bestTarget = block;
                    }
                }
            }
        }
        
        return bestTarget;
    }
    
    private void applyPenalty(int basePenalty, String reason) {
        // Reset mining streak when penalty occurs
        miningStreak = 0;
        
        // Increase penalty streak
        penaltyStreak++;
        
        // Calculate penalty with streak multiplier (capped at -500)
        int penalty = (int) Math.max(basePenalty * Math.min(penaltyStreak, 10), MAX_PENALTY_CAP);
        totalRewards -= Math.abs(penalty); // Ensure we subtract the absolute value
        
        // Apply penalty to training data for current target
        if (targetBlock != null) {
            trainingData.getRewardMemory().put(targetBlock, 
                trainingData.getRewardMemory().getOrDefault(targetBlock, 0) + penalty);
        }
        
        // Show penalty message with streak info
        String streakInfo = penaltyStreak > 1 ? " (Penalty Streak x" + penaltyStreak + ")" : "";
        AutoMinerClient.sendActionBarMessage(Text.literal("§c" + penalty + " §fReward (" + reason + ")" + streakInfo + " §7| §eTotal: §f" + totalRewards));
        
        logger.log("PENALTY: " + reason + ". Penalty: " + penalty + ", Streak: " + penaltyStreak + ", Total rewards: " + totalRewards);
    }

    public void onFallDamage() {
        if (isMining()) {
            applyPenalty(-20, "Fall damage");
        }
    }

    private boolean selectAndSwitchToBestTool(BlockState blockState) {
        if (client.player == null || client.world == null) return false;

        PlayerInventory inventory = client.player.getInventory();
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        int fallbackEmptySlot = -1;
        int fallbackNoDurabilitySlot = -1;
        int originalSlot = getSelectedSlot(inventory);

        // --- NEUE KORREKTUR FÜR KOMPILIERUNGSFEHLER ---
        RegistryEntry<Enchantment> efficiencyEnchantment = null;
        Optional<Registry<Enchantment>> enchantmentRegistryOpt = client.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);

        if (enchantmentRegistryOpt.isPresent()) {
            Registry<Enchantment> enchantmentRegistry = enchantmentRegistryOpt.get();
            // Hole das Enchantment direkt
            Enchantment efficiencyObject = enchantmentRegistry.get(Enchantments.EFFICIENCY);
            if (efficiencyObject != null) {
                // Verwende getEntry mit dem Enchantment-Objekt
                efficiencyEnchantment = enchantmentRegistry.getEntry(efficiencyObject);
            }
        } else {
            logger.log("WARNUNG: Verzauberungs-Registry konnte nicht gefunden werden.");
        }
        // --- ENDE KORREKTUR ---

        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getStack(i);

            if (stack.isEmpty()) {
                if (fallbackEmptySlot == -1) {
                    fallbackEmptySlot = i;
                }
                continue;
            }

            if (!stack.isDamageable()) {
                 if (fallbackNoDurabilitySlot == -1) {
                    fallbackNoDurabilitySlot = i;
                }
            }

            if (!stack.isDamageable() && !stack.isSuitableFor(blockState)) {
                continue;
            }

            if (stack.isDamageable() && (stack.getMaxDamage() - stack.getDamage()) < 100) {
                 continue;
            }

            float speed = stack.getMiningSpeedMultiplier(blockState);
            
            // Füge den Effizienz-Bonus nur hinzu, wenn die Verzauberung gefunden wurde.
            if (speed > 1.0f && efficiencyEnchantment != null) {
                int efficiencyLevel = EnchantmentHelper.getLevel(efficiencyEnchantment, stack);
                if (efficiencyLevel > 0) {
                    speed += efficiencyLevel * efficiencyLevel + 1;
                }
            }

            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        int slotToSwitchTo = -1;

        if (bestSlot != -1) {
            slotToSwitchTo = bestSlot;
        } else {
            ItemStack currentStack = inventory.getStack(originalSlot);
            if (currentStack.isDamageable() && (currentStack.getMaxDamage() - currentStack.getDamage()) < 100) {
                 client.player.sendMessage(Text.literal("§cAutoMiner pausiert. Das Werkzeug '").append(currentStack.getName()).append("§c' hat eine geringe Haltbarkeit."), false);
                 pause();
                 return false;
            }

            logger.log("No suitable tool found. Using fallback.");
            if (fallbackEmptySlot != -1) {
                slotToSwitchTo = fallbackEmptySlot;
            } else if (fallbackNoDurabilitySlot != -1) {
                slotToSwitchTo = fallbackNoDurabilitySlot;
            }
        }

        if (slotToSwitchTo != -1 && getSelectedSlot(inventory) != slotToSwitchTo) {
            setSelectedSlot(inventory, slotToSwitchTo);
            logger.log("Switched to hotbar slot " + slotToSwitchTo + " for block " + targetBlock.toShortString());
        }

        return true;
    }

    private BlockPos findReachableSkippedBlock() {
        return skippedBlocks.stream()
                .filter(skippedPos -> client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(skippedPos)) <= MAX_REACH_DISTANCE_SQUARED)
                .findFirst()
                .orElse(null);
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
        if (logger != null) {
            logger.log("Operation finished. Reason: " + reason + ". Final rewards: " + totalRewards);
            logger.close();
            logger = null;
        }
        trainingData.save();
    }

    private BlockPos findNextTarget() {
        if (blocksToMine.isEmpty()) return null;
        
        BlockPos playerPos = client.player.getBlockPos();
        Vec3d playerEye = client.player.getEyePos();
        
        // First, try to find a directly accessible target
        for (int i = 0; i < Math.min(blocksToMine.size(), 5); i++) {
            BlockPos candidate = blocksToMine.get(i);
            
            // Skip if block is already air or should be ignored
            BlockState state = client.world.getBlockState(candidate);
            if (state.isAir() || state.getBlock() instanceof FluidBlock || shouldIgnoreVegetationAt(candidate)) {
                continue;
            }
            
            // Check if we can reach it directly from current position
            double distance = playerEye.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance <= MAX_REACH_DISTANCE_SQUARED && hasLineOfSight(candidate)) {
                return candidate;
            }
            
            // Check if we can find a valid mining position for this target
            if (canFindValidMiningPosition(candidate)) {
                return candidate;
            }
        }
        
        // If no easily accessible targets, clean up the list and return first valid one
        blocksToMine.removeIf(pos -> {
            BlockState state = client.world.getBlockState(pos);
            return state.isAir() || shouldIgnoreVegetationAt(pos);
        });
        return blocksToMine.isEmpty() ? null : blocksToMine.get(0);
    }
    
    private boolean canFindValidMiningPosition(BlockPos target) {
        // Quick check for potential mining positions around the target
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    
                    BlockPos candidate = target.add(dx, dy, dz);
                    Vec3d candidateEye = Vec3d.ofCenter(candidate).add(0, 1.62, 0);
                    
                    // Check distance
                    if (candidateEye.squaredDistanceTo(Vec3d.ofCenter(target)) > MAX_REACH_DISTANCE_SQUARED) {
                        continue;
                    }
                    
                    // Check if position is safe
                    if (!isSafeToStandOn(candidate)) continue;
                    
                    // Check line of sight from this position
                    HitResult hitResult = client.world.raycast(new RaycastContext(
                        candidateEye, Vec3d.ofCenter(target),
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        client.player
                    ));
                    
                    if (hitResult.getType() == HitResult.Type.MISS) {
                        return true;
                    } else if (hitResult.getType() == HitResult.Type.BLOCK) {
                        BlockHitResult blockHit = (BlockHitResult) hitResult;
                        if (blockHit.getBlockPos().equals(target)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isIgnorableVegetation(BlockState state) {
        // This method is used for pathfinding - check if vegetation can be moved through
        return state.isIn(BlockTags.FLOWERS) || state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS) || 
               state.isOf(Blocks.FERN) || state.isOf(Blocks.LARGE_FERN) ||
               state.isIn(BlockTags.REPLACEABLE) || state.isIn(BlockTags.SMALL_FLOWERS) || 
               state.isOf(Blocks.DANDELION) || state.isOf(Blocks.POPPY) || state.isOf(Blocks.BLUE_ORCHID) ||
               state.isOf(Blocks.ALLIUM) || state.isOf(Blocks.AZURE_BLUET) || state.isOf(Blocks.RED_TULIP) ||
               state.isOf(Blocks.ORANGE_TULIP) || state.isOf(Blocks.WHITE_TULIP) || state.isOf(Blocks.PINK_TULIP) ||
               state.isOf(Blocks.OXEYE_DAISY) || state.isOf(Blocks.CORNFLOWER) || state.isOf(Blocks.LILY_OF_THE_VALLEY) ||
               state.isOf(Blocks.DEAD_BUSH) || state.isOf(Blocks.SEAGRASS) || state.isOf(Blocks.TALL_SEAGRASS) ||
               state.isOf(Blocks.SEA_PICKLE) || state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT) ||
               state.isOf(Blocks.CRIMSON_ROOTS) || state.isOf(Blocks.WARPED_ROOTS) || 
               state.isOf(Blocks.NETHER_SPROUTS) || state.isOf(Blocks.CRIMSON_FUNGUS) || state.isOf(Blocks.WARPED_FUNGUS) ||
               state.isOf(Blocks.TWISTING_VINES) || state.isOf(Blocks.TWISTING_VINES_PLANT) ||
               state.isOf(Blocks.WEEPING_VINES) || state.isOf(Blocks.WEEPING_VINES_PLANT) ||
               state.isReplaceable();
    }
    
    private boolean shouldIgnoreVegetationAt(BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        
        // If it's not vegetation, don't ignore it
        if (!isIgnorableVegetation(state)) {
            return false;
        }
        
        // Check if there's a solid block below this vegetation that's within the mining area
        BlockPos belowPos = pos.down();
        BlockState belowState = client.world.getBlockState(belowPos);
        
        // If the block below is solid and within the mining area, we can ignore this vegetation
        // because the solid block will be mined and the vegetation will break automatically
        if (belowState.isSolidBlock(client.world, belowPos) && miningArea != null && miningArea.isWithinArea(belowPos)) {
            return true; // Ignore this vegetation
        }
        
        // If the block below is not solid or not in the mining area, we need to mine the vegetation
        return false;
    }

    private List<BlockPos> generateSnakeLayer(MiningArea area, int y, boolean reverseX, boolean reverseZ) {
        List<BlockPos> layerBlocks = new ArrayList<>();
        int minX = area.getStartPos().getX(), minZ = area.getStartPos().getZ();
        int maxX = area.getEndPos().getX(), maxZ = area.getEndPos().getZ();
        for (int i = 0; i <= (maxX - minX); i++) {
            int x = reverseX ? maxX - i : minX + i;
            List<BlockPos> row = new ArrayList<>();
            boolean currentReverseZ = ((i % 2) == 0) ? reverseZ : !reverseZ;
            if (currentReverseZ) {
                for (int z = maxZ; z >= minZ; z--) row.add(new BlockPos(x, y, z));
            } else {
                for (int z = minZ; z <= maxZ; z++) row.add(new BlockPos(x, y, z));
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
        if (target.getY() > player.getY() && player.isOnGround()) player.jump();
        if (Math.abs(deltaYaw) > 20.0F) {
            if (Math.abs(deltaYaw) < 90.0F) client.options.forwardKey.setPressed(true);
            if (deltaYaw > 0) client.options.rightKey.setPressed(true);
            else client.options.leftKey.setPressed(true);
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
        if (client.world.getBlockState(pos).isAir()) return;
        if (!pos.equals(currentlyBreaking)) currentlyBreaking = pos;
        client.interactionManager.updateBlockBreakingProgress(pos, Direction.UP);
    }
    
    private boolean hasLineOfSight(BlockPos target) {
        Vec3d playerEye = client.player.getEyePos();
        Vec3d targetCenter = Vec3d.ofCenter(target);
        
        // Use raycast to check if there are any blocks blocking the path
        HitResult hitResult = client.world.raycast(new RaycastContext(
            playerEye,
            targetCenter,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        
        // If we hit the target block or miss entirely, we have line of sight
        if (hitResult.getType() == HitResult.Type.MISS) {
            return true;
        }
        
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            BlockHitResult blockHit = (BlockHitResult) hitResult;
            BlockPos hitPos = blockHit.getBlockPos();
            
            // Check if we hit the target or if it's ignorable vegetation
            if (hitPos.equals(target)) {
                return true;
            }
            
            // Allow line of sight through vegetation that can be broken
            BlockState hitState = client.world.getBlockState(hitPos);
            if (isIgnorableVegetation(hitState)) {
                return true;
            }
        }
        
        return false;
    }
    
    private List<BlockPos> findGoodMiningPositions(BlockPos target) {
        List<BlockPos> positions = new ArrayList<>();
        Vec3d targetCenter = Vec3d.ofCenter(target);
        
        // Generate positions in a 3D radius around the target block
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // Skip the target block itself
                    
                    BlockPos candidate = target.add(dx, dy, dz);
                    Vec3d candidateEye = Vec3d.ofCenter(candidate).add(0, 1.62, 0); // Eye height
                    
                    // Check distance constraint
                    double distanceSquared = candidateEye.squaredDistanceTo(targetCenter);
                    if (distanceSquared > MAX_REACH_DISTANCE_SQUARED) continue;
                    
                    // Check if position is safe to stand on
                    if (!isSafeToStandOn(candidate)) continue;
                    
                    // Check if we would have line of sight from this position
                    HitResult hitResult = client.world.raycast(new RaycastContext(
                        candidateEye,
                        targetCenter,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        client.player
                    ));
                    
                    boolean hasLineOfSight = false;
                    if (hitResult.getType() == HitResult.Type.MISS) {
                        hasLineOfSight = true;
                    } else if (hitResult.getType() == HitResult.Type.BLOCK) {
                        BlockHitResult blockHit = (BlockHitResult) hitResult;
                        BlockPos hitPos = blockHit.getBlockPos();
                        if (hitPos.equals(target) || isIgnorableVegetation(client.world.getBlockState(hitPos))) {
                            hasLineOfSight = true;
                        }
                    }
                    
                    if (hasLineOfSight) {
                        positions.add(candidate);
                    }
                }
            }
        }
        
        return positions;
    }
    
    private boolean isSafeToStandOn(BlockPos pos) {
        World world = client.world;
        if (world == null) return false;
        
        // Check if there's solid ground below
        BlockPos groundPos = pos.down();
        if (!world.getBlockState(groundPos).isSolidBlock(world, groundPos)) {
            return false;
        }
        
        // Check if the position itself is passable (player feet)
        BlockState feetState = world.getBlockState(pos);
        if (!feetState.getCollisionShape(world, pos).isEmpty() && !isIgnorableVegetation(feetState)) {
            return false;
        }
        
        // Check if the position above is passable (player head)
        BlockState headState = world.getBlockState(pos.up());
        if (!headState.getCollisionShape(world, pos.up()).isEmpty() && !isIgnorableVegetation(headState)) {
            return false;
        }
        
        return true;
    }

    public boolean isMining() { return currentState != State.IDLE && currentState != State.FINISHED; }
    public boolean isPaused() { return currentState == State.IDLE && miningArea != null; }
    public MiningArea getCurrentArea() { return miningArea; }
    public List<BlockPos> getCurrentPath() { return currentPath; }
    public BlockPos getTargetBlock() { return targetBlock; }

    private class Pathfinder {
        private PriorityQueue<Node> openSet;
        private Map<BlockPos, Node> allNodes;
        private int iterations;

        public void startPath(BlockPos start, BlockPos goal, boolean findStandPos) {
            this.openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
            this.allNodes = new HashMap<>();
            Node startNode = new Node(start, null, 0, getHeuristic(start, goal));
            this.openSet.add(startNode);
            this.allNodes.put(start, startNode);
            this.iterations = 0;
        }

        public PathfinderResult continuePath(BlockPos goal, boolean findStandPos) {
            int nodesThisTick = 0;
            while (!openSet.isEmpty() && nodesThisTick < config.nodesPerTick) {
                if (iterations >= config.maxSearchNodes) {
                    return new PathfinderResult(PathFindResultType.SEARCH_LIMIT_REACHED, null, null);
                }
                iterations++;
                nodesThisTick++;

                Node current = openSet.poll();

                if (findStandPos) {
                    Vec3d currentEyePos = Vec3d.ofCenter(current.pos).add(0, 1.62, 0);
                    double distanceToTarget = currentEyePos.squaredDistanceTo(Vec3d.ofCenter(goal));
                    
                    if (isSafeToStandOn(current.pos) && distanceToTarget <= MAX_REACH_DISTANCE_SQUARED - 0.5) {
                        // Validate line of sight from this position to the target
                        HitResult hitResult = client.world.raycast(new RaycastContext(
                            currentEyePos,
                            Vec3d.ofCenter(goal),
                            RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE,
                            client.player
                        ));
                        
                        boolean hasValidLineOfSight = false;
                        if (hitResult.getType() == HitResult.Type.MISS) {
                            hasValidLineOfSight = true;
                        } else if (hitResult.getType() == HitResult.Type.BLOCK) {
                            BlockHitResult blockHit = (BlockHitResult) hitResult;
                            BlockPos hitPos = blockHit.getBlockPos();
                            if (hitPos.equals(goal) || isIgnorableVegetation(client.world.getBlockState(hitPos))) {
                                hasValidLineOfSight = true;
                            }
                        }
                        
                        if (hasValidLineOfSight) {
                            return new PathfinderResult(PathFindResultType.SUCCESS, reconstructPath(current), current.pos);
                        }
                    }
                } else {
                    if (current.pos.equals(goal)) {
                        return new PathfinderResult(PathFindResultType.SUCCESS, reconstructPath(current), current.pos);
                    }
                }
                addNeighbors(current, goal);
            }

            if (openSet.isEmpty()) {
                return new PathfinderResult(PathFindResultType.NO_PATH, null, null);
            }

            return null; 
        }

        private void addNeighbors(Node current, BlockPos end) {
            BlockPos currentPos = current.pos;
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = currentPos.offset(dir);
                if (Math.abs(neighborPos.getY() - client.player.getBlockY()) > 50) continue;

                int deltaY = neighborPos.getY() - currentPos.getY();
                if (deltaY > 1) continue;
                if (deltaY == 1 && (!client.world.getBlockState(currentPos.up(2)).getCollisionShape(client.world, currentPos.up(2)).isEmpty() || client.world.getBlockState(neighborPos.down()).getCollisionShape(client.world, neighborPos.down()).isEmpty())) continue;
                if (deltaY <= 0) {
                    BlockPos groundPos = neighborPos.down();
                    if (client.world.getBlockState(groundPos).getCollisionShape(client.world, groundPos).isEmpty()) {
                        if (client.world.getBlockState(groundPos.down()).getCollisionShape(client.world, groundPos.down()).isEmpty()) continue;
                    }
                }
                BlockState feetState = client.world.getBlockState(neighborPos);
                BlockState headState = client.world.getBlockState(neighborPos.up());
                boolean feetPassable = feetState.getCollisionShape(client.world, neighborPos).isEmpty();
                boolean headPassable = headState.getCollisionShape(client.world, neighborPos.up()).isEmpty();
                double cost = 1.0;
                boolean isPossible = true;
                if (!feetPassable && !isIgnorableVegetation(feetState)) {
                    if (miningArea.isWithinArea(neighborPos)) cost += 10.0;
                    else isPossible = false;
                }
                if (!headPassable && !isIgnorableVegetation(headState)) {
                    if (miningArea.isWithinArea(neighborPos.up())) cost += 10.0;
                    else isPossible = false;
                }
                if(isPossible) addNode(current, neighborPos, cost, end);
            }
        }

        private void addNode(Node parent, BlockPos pos, double cost, BlockPos end) {
            int rewardModifier = trainingData.getRewardMemory().getOrDefault(pos, 0);
            double modifiedCost = cost - (rewardModifier * 0.1);
            if (modifiedCost <= 0) modifiedCost = 0.1;

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
            return world != null && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty() && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty();
        }

        private double getHeuristic(BlockPos from, BlockPos to) {
            return Math.sqrt(from.getSquaredDistance(to));
        }

        private List<BlockPos> reconstructPath(Node endNode) {
            List<BlockPos> path = new ArrayList<>();
            Node current = endNode;
            while (current != null && current.parent != null) {
                path.add(current.pos);
                current = current.parent;
            }
            Collections.reverse(path);
            return path;
        }

        private class Node {
            BlockPos pos; Node parent; double gCost, hCost, fCost;
            Node(BlockPos pos) { this.pos = pos; }
            Node(BlockPos pos, Node parent, double gCost, double hCost) { this.pos = pos; this.parent = parent; this.gCost = gCost; this.hCost = hCost; this.fCost = gCost + hCost; }
            @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; return Objects.equals(pos, ((Node) o).pos); }
            @Override public int hashCode() { return Objects.hash(pos); }
        }
    }
}
