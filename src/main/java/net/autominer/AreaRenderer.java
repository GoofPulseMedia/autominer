package net.autominer;

import java.util.List;

import org.joml.Matrix4f;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class AreaRenderer {

    public static void render(MatrixStack matrices, VertexConsumerProvider consumers, Camera camera, MiningLogic miningLogic, BlockPos startPos, BlockPos endPos, boolean isSelectingArea) {
        Vec3d cameraPos = camera.getPos();

        // Modern rendering approach for MC 1.21.8
        VertexConsumer vertexConsumer = consumers.getBuffer(RenderLayer.getLines());
        
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // --- Logik für das Rendern der verschiedenen Elemente ---

        if (isSelectingArea) {
            // --- AUSWAHLPHASE ---
            if (startPos != null) {
                drawBoundingBox(vertexConsumer, matrix, new Box(startPos), 1.0f, 1.0f, 0.0f, 1.0f); // Hellgelb
            }
            if (endPos != null) {
                drawBoundingBox(vertexConsumer, matrix, new Box(endPos), 1.0f, 1.0f, 0.0f, 1.0f); // Hellgelb
            }
            if (startPos != null && endPos != null) {
                Box selectionBox = Box.enclosing(startPos, endPos); // expand(1.0) ist hier korrekt, um den ganzen Block einzuschließen
                drawBoundingBox(vertexConsumer, matrix, selectionBox, 0.2f, 0.8f, 1.0f, 1.0f); // Hellblau
            }
        } else if (miningLogic.isMining() || miningLogic.isPaused()) {
            // --- BESTÄTIGTE/AKTIVE PHASE ---
            MiningArea area = miningLogic.getCurrentArea();
            if (area != null) {
                Box miningBox = Box.enclosing(area.getStartPos(), area.getEndPos());
                drawBoundingBox(vertexConsumer, matrix, miningBox, 0.1f, 1.0f, 0.2f, 0.8f); // Grün
            }
        }

        if (miningLogic.isMining()) {
            List<BlockPos> path = miningLogic.getCurrentPath();
            if (path != null && path.size() > 1) {
                drawPath(vertexConsumer, matrix, path, 1.0f, 1.0f, 0.0f, 1.0f); // Gelb für den Pfad
            }
            BlockPos targetBlock = miningLogic.getTargetBlock();
            if (targetBlock != null) {
                drawBoundingBox(vertexConsumer, matrix, new Box(targetBlock), 1.0f, 0.1f, 0.1f, 1.0f); // Rot
            }
        }

        matrices.pop();
    }

    /**
     * Zeichnet die Umrandung einer Box mit dem VertexConsumer.
     */
    private static void drawBoundingBox(VertexConsumer vertexConsumer, Matrix4f matrix, Box box, float r, float g, float b, float a) {
        // Unten
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.minY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.minY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.minY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.minY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.minY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.minY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.minY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.minY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        // Oben
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.maxY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.maxY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.maxY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.maxY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.maxY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.maxY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.maxY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.maxY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        // Vertikale Linien
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.minY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.maxY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.minY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.maxY, (float)box.minZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.minY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.maxX, (float)box.maxY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.minY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
        vertexConsumer.vertex(matrix, (float)box.minX, (float)box.maxY, (float)box.maxZ).color(r, g, b, a).normal(0, 1, 0);
    }

    private static void drawPath(VertexConsumer vertexConsumer, Matrix4f matrix, List<BlockPos> path, float r, float g, float b, float a) {
        for (int i = 0; i < path.size() - 1; i++) {
            BlockPos pos1 = path.get(i);
            BlockPos pos2 = path.get(i + 1);

            vertexConsumer.vertex(matrix, (float)pos1.getX() + 0.5f, (float)pos1.getY() + 0.5f, (float)pos1.getZ() + 0.5f).color(r, g, b, a).normal(0, 1, 0);
            vertexConsumer.vertex(matrix, (float)pos2.getX() + 0.5f, (float)pos2.getY() + 0.5f, (float)pos2.getZ() + 0.5f).color(r, g, b, a).normal(0, 1, 0);
        }
    }
}