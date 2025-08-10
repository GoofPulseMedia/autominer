package net.autominer;

import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MiningArea {
    private final BlockPos startPos;
    private final BlockPos endPos;
    private int currentLayer;
    private boolean isActive;

    public MiningArea(BlockPos start, BlockPos end) {
        // Normalize coordinates to ensure startPos has smaller coordinates
        this.startPos = new BlockPos(
            Math.min(start.getX(), end.getX()),
            Math.min(start.getY(), end.getY()),
            Math.min(start.getZ(), end.getZ())
        );
        this.endPos = new BlockPos(
            Math.max(start.getX(), end.getX()),
            Math.max(start.getY(), end.getY()),
            Math.max(start.getZ(), end.getZ())
        );
        
        this.currentLayer = this.endPos.getY(); // Start from top
        this.isActive = false;
    }

    /**
     * Generates the list of blocks for the current layer in an efficient snake pattern.
     * This minimizes travel time between rows.
     */
    public List<BlockPos> getCurrentLayerBlocks() {
        List<BlockPos> layerBlocks = new ArrayList<>();
        boolean reverseZ = false; // To alternate the direction of mining for each row

        for (int x = startPos.getX(); x <= endPos.getX(); x++) {
            List<BlockPos> row = new ArrayList<>();
            for (int z = startPos.getZ(); z <= endPos.getZ(); z++) {
                row.add(new BlockPos(x, currentLayer, z));
            }
            if (reverseZ) {
                Collections.reverse(row);
            }
            layerBlocks.addAll(row);
            reverseZ = !reverseZ; // Flip direction for the next row
        }
        return layerBlocks;
    }

    public boolean moveToNextLayer() {
        if (currentLayer > startPos.getY()) {
            currentLayer--;
            return true;
        }
        return false;
    }

    public boolean isWithinArea(BlockPos pos) {
        return pos.getX() >= startPos.getX() && pos.getX() <= endPos.getX() &&
               pos.getY() >= startPos.getY() && pos.getY() <= endPos.getY() &&
               pos.getZ() >= startPos.getZ() && pos.getZ() <= endPos.getZ();
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public BlockPos getStartPos() {
        return startPos;
    }

    public BlockPos getEndPos() {
        return endPos;
    }

    public int getCurrentLayer() {
        return currentLayer;
    }
    
    public int getLayerCount() {
        return endPos.getY() - startPos.getY() + 1;
    }

    public int getCurrentLayerIndex() {
        return endPos.getY() - currentLayer;
    }
}
