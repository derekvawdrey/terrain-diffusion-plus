package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Fallen log placer -- a horizontal log bridging a small gap or ravine.
 * Simplified dual-anchor pattern: picks one random direction, checks if ground
 * drops by 2+ blocks over a 4-8 block span, and places a log spanning the gap.
 *
 * <p>Based on {@link ArchFeaturePlacer} (dual-anchor concept) but simplified to
 * a single-direction check rather than a full ring search. Also based on {@link
 * DriftwoodFeaturePlacer} for log orientation using {@link RotatedPillarBlock}.</p>
 */
public final class FallenLogFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x46414C4CL;
    private static final int CELL_SIZE = 48;
    private static final float SPAWN_CHANCE = 0.3f;
    private static final int MIN_SPAN = 4;
    private static final int MAX_SPAN = 8;
    private static final int MIN_GAP_DEPTH = 2;

    private static final BlockState OAK_LOG_X = Blocks.OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
    private static final BlockState OAK_LOG_Z = Blocks.OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
    private static final BlockState DARK_OAK_LOG_X = Blocks.DARK_OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
    private static final BlockState DARK_OAK_LOG_Z = Blocks.DARK_OAK_LOG.defaultBlockState()
            .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);

    @Override
    public String id() {
        return "fallen_log";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return 10;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int row = site.worldZ() - dataOriginZ;
        int col = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, row, col)) return;
        if (TerrainSampling.elevationAt(data, row, col) <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, row, col);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null) return;
        boolean validBiome = biomeKey.contains("forest") || biomeKey.contains("taiga")
                || biomeKey.contains("grove") || biomeKey.contains("old_growth")
                || biomeKey.contains("plains");
        if (!validBiome) return;

        int span = MIN_SPAN + (int) (SurfaceNoise.unitHash(site.seed(), 0, 0) * (MAX_SPAN - MIN_SPAN));
        double angle = SurfaceNoise.unitHash(site.seed(), 1, 0) * Math.PI * 2;
        int dx = Math.round((float) (Math.cos(angle) * span));
        int dz = Math.round((float) (Math.sin(angle) * span));

        int endX = site.worldX() + dx;
        int endZ = site.worldZ() + dz;
        int rowEnd = endZ - dataOriginZ;
        int colEnd = endX - dataOriginX;
        if (!TerrainSampling.inBounds(data, rowEnd, colEnd)) return;

        float elevA = TerrainSampling.elevationAt(data, row, col);
        float elevB = TerrainSampling.elevationAt(data, rowEnd, colEnd);
        float gapDepth = elevA - elevB;

        if (Math.abs(gapDepth) < MIN_GAP_DEPTH) return;

        boolean isXAxis = Math.abs(dx) > Math.abs(dz);
        stamp(chunk, site, site.worldX(), site.worldZ(), endX, endZ, elevA, elevB, isXAxis);
    }

    private void stamp(ChunkAccess chunk, SiteGrid.Site site, int startX, int startZ,
                        int endX, int endZ, float elevA, float elevB, boolean isXAxis) {
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();

        boolean isDarkOak = SurfaceNoise.unitHash(site.seed(), 2, 0) < 0.3f;
        BlockState logStateX = isDarkOak ? DARK_OAK_LOG_X : OAK_LOG_X;
        BlockState logStateZ = isDarkOak ? DARK_OAK_LOG_Z : OAK_LOG_Z;
        BlockState logState = isXAxis ? logStateX : logStateZ;

        int span = Math.max(Math.abs(endX - startX), Math.abs(endZ - startZ));
        int steps = Math.max(span, 4);

        for (int t = 0; t <= steps; t++) {
            float frac = t / (float) steps;
            int wx = Math.round(startX + (endX - startX) * frac);
            int wz = Math.round(startZ + (endZ - startZ) * frac);
            int lx = wx - minX;
            int lz = wz - minZ;
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15) continue;

            float elevAtT = SurfaceNoise.lerp(elevA, elevB, frac);
            int groundY = HeightConverter.convertToMinecraftHeight((short) elevAtT) - 1;
            int logY = groundY + 1;

            if (logY < chunk.getMinBuildHeight() || logY > chunk.getMaxBuildHeight() - 1) continue;

            pos.set(wx, logY, wz);
            if (!chunk.getBlockState(pos).isAir()) continue;
            chunk.setBlockState(pos, logState, false);
            worldSurface.update(lx, logY, lz, logState);
            motionBlocking.update(lx, logY, lz, logState);
        }
    }
}
