package com.github.xandergos.terraindiffusionmc.world.surface;

import com.github.xandergos.terraindiffusionmc.biome.TerrainBiomeRegistry;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SiteGrid;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.SurfaceNoise;
import com.github.xandergos.terraindiffusionmc.worldgen.surface.TerrainSampling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Sea arch placer -- a low arch at a coastal cliff base, like a sea cave eroded through.
 * Based on {@link ArchFeaturePlacer} (dual-anchor pattern) but placed at low elevation
 * in coastal biomes with a wave-exposure check, using coastal blocks.
 *
 * <p>Uses the dual-anchor saddle search from ArchFeaturePlacer with raster-based geometry.
 * The arch is low and wide with short legs, simulating wave erosion at cliff base.</p>
 */
public final class SeaArchFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x5345414CL;
    private static final int CELL_SIZE = 72;
    private static final float SPAWN_CHANCE = 0.3f;

    private static final int SEARCH_MIN_SPAN = 5;
    private static final int SEARCH_MAX_SPAN = 10;
    private static final int RING_SAMPLES = 10;
    private static final float ELEVATION_TOLERANCE_BLOCKS = 1.5f;
    private static final float MIN_GAP_DEPTH_BLOCKS = 2.5f;
    private static final float MIN_ANCHOR_SLOPE_BLOCKS = 0.5f;

    private static final int LEG_RADIUS = 1;
    private static final int ARCH_HALF_WIDTH = 1;
    private static final int ARCH_THICKNESS = 2;

    private static final float WAVE_EXPOSURE_WAVELENGTH = 150.0f;
    private static final float WAVE_EXPOSURE_THRESHOLD = 0.2f;

    @Override
    public String id() {
        return "sea_arch";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return SEARCH_MAX_SPAN + LEG_RADIUS + 3;
    }

    @Override
    public long salt() {
        return SALT;
    }

    @Override
    public void place(ChunkAccess chunk, HeightmapData data, int dataOriginX, int dataOriginZ,
                       SiteGrid.Site site, long worldSeed) {
        if (SurfaceNoise.unitHash(worldSeed ^ SALT, site.worldX(), site.worldZ()) >= SPAWN_CHANCE) return;

        int rowA = site.worldZ() - dataOriginZ;
        int colA = site.worldX() - dataOriginX;
        if (!TerrainSampling.inBounds(data, rowA, colA)) return;
        float elevA = TerrainSampling.elevationAt(data, rowA, colA);
        if (elevA <= 0f) return;

        TerrainBiomeRegistry registry = TerrainBiomeRegistry.instance();
        short biomeIndex = TerrainSampling.biomeIndexAt(data, rowA, colA);
        if (registry.isRiver(biomeIndex) || registry.isFrozenRiver(biomeIndex)) return;
        String biomeKey = registry.keyForIndex(biomeIndex);
        if (biomeKey == null || !isCoastalBiome(biomeKey)) return;
        if (TerrainSampling.slopeAt(data, rowA, colA, 2) < SurfaceStamp.slopeFromBlocks(MIN_ANCHOR_SLOPE_BLOCKS)) return;

        // Wave exposure check: use value noise to simulate wave exposure - only place on
        // exposed coast, not sheltered coves.
        float waveExposure = SurfaceNoise.valueNoise(worldSeed ^ (SALT + 1),
                site.worldX() / WAVE_EXPOSURE_WAVELENGTH, site.worldZ() / WAVE_EXPOSURE_WAVELENGTH);
        if (waveExposure <= WAVE_EXPOSURE_THRESHOLD) return;

        Candidate best = findOpposingAnchor(data, dataOriginX, dataOriginZ, site, elevA);
        if (best == null) return;

        buildArch(chunk, site.seed(), site.worldX(), site.worldZ(), elevA, best.worldX, best.worldZ, best.elevation);
    }

    private boolean isCoastalBiome(String biomeKey) {
        return biomeKey.contains("beach") || biomeKey.contains("ocean") || biomeKey.contains("stony");
    }

    private record Candidate(int worldX, int worldZ, float elevation, float midpointDepth) {}

    private Candidate findOpposingAnchor(HeightmapData data, int dataOriginX, int dataOriginZ,
                                          SiteGrid.Site site, float elevA) {
        Candidate best = null;
        double ringOffset = SurfaceNoise.unitHash(site.seed(), 0, 0) * Math.PI * 2;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double angle = ringOffset + (Math.PI * 2 * i) / RING_SAMPLES;
            int span = SEARCH_MIN_SPAN
                    + (int) (SurfaceNoise.unitHash(site.seed(), i, 7) * (SEARCH_MAX_SPAN - SEARCH_MIN_SPAN));
            int bx = site.worldX() + Math.round((float) Math.cos(angle) * span);
            int bz = site.worldZ() + Math.round((float) Math.sin(angle) * span);
            int rowB = bz - dataOriginZ;
            int colB = bx - dataOriginX;
            if (!TerrainSampling.inBounds(data, rowB, colB)) continue;
            float elevB = TerrainSampling.elevationAt(data, rowB, colB);
            if (elevB <= 0f || Math.abs(elevB - elevA) > SurfaceStamp.blocksToElevation(ELEVATION_TOLERANCE_BLOCKS)) continue;

            int mx = (site.worldX() + bx) / 2;
            int mz = (site.worldZ() + bz) / 2;
            int rowM = mz - dataOriginZ;
            int colM = mx - dataOriginX;
            if (!TerrainSampling.inBounds(data, rowM, colM)) continue;
            float elevM = TerrainSampling.elevationAt(data, rowM, colM);
            float depth = (elevA + elevB) / 2f - elevM;
            if (depth < SurfaceStamp.blocksToElevation(MIN_GAP_DEPTH_BLOCKS)) continue;

            if (best == null || depth > best.midpointDepth()) {
                best = new Candidate(bx, bz, elevB, depth);
            }
        }
        return best;
    }

    private void buildArch(ChunkAccess chunk, long siteSeed, int ax, int az, float elevA,
                            int bx, int bz, float elevB) {
        int span = (int) Math.round(Math.hypot(bx - ax, bz - az));
        int legHeight = Math.max(2, Math.min(4, Math.round(span * 0.3f)));
        int apexRise = Math.max(1, Math.round(span * 0.15f));

        int groundYA = HeightConverter.convertToMinecraftHeight((short) elevA) - 1;
        int groundYB = HeightConverter.convertToMinecraftHeight((short) elevB) - 1;
        int springYA = groundYA + legHeight;
        int springYB = groundYB + legHeight;

        double dx = bx - ax, dz = bz - az;
        double length = Math.max(1e-3, Math.hypot(dx, dz));
        double perpX = -dz / length, perpZ = dx / length;

        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        Heightmap motionBlocking = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        placeLeg(chunk, worldSurface, motionBlocking, siteSeed, ax, az, groundYA, springYA);
        placeLeg(chunk, worldSurface, motionBlocking, siteSeed, bx, bz, groundYB, springYB);

        int steps = Math.max(12, span * 2);
        for (int t = 0; t <= steps; t++) {
            float frac = t / (float) steps;
            double centerX = ax + dx * frac;
            double centerZ = az + dz * frac;
            float baseline = SurfaceNoise.lerp(springYA, springYB, frac);
            float arcY = baseline + apexRise * 4f * frac * (1f - frac);

            for (int w = -ARCH_HALF_WIDTH; w <= ARCH_HALF_WIDTH; w++) {
                int wx = (int) Math.round(centerX + perpX * w);
                int wz = (int) Math.round(centerZ + perpZ * w);
                for (int thick = 0; thick < ARCH_THICKNESS; thick++) {
                    int wy = Math.round(arcY) - thick;
                    placeIfAir(chunk, worldSurface, motionBlocking, siteSeed, wx, wy, wz);
                }
            }
        }
    }

    private void placeLeg(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long siteSeed,
                           int worldX, int worldZ, int groundY, int springY) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int loY = Math.max(groundY + 1, chunk.getMinBuildHeight());
        int hiY = Math.min(springY, chunk.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = -LEG_RADIUS; dz <= LEG_RADIUS; dz++) {
            int wz = worldZ + dz;
            int lz = wz - minZ;
            if (lz < 0 || lz > 15) continue;
            for (int dx = -LEG_RADIUS; dx <= LEG_RADIUS; dx++) {
                int wx = worldX + dx;
                int lx = wx - minX;
                if (lx < 0 || lx > 15) continue;
                if (dx * dx + dz * dz > LEG_RADIUS * LEG_RADIUS + 1) continue;

                for (int wy = loY; wy <= hiY; wy++) {
                    pos.set(wx, wy, wz);
                    BlockState block = blockFor(siteSeed, wx, wy, wz);
                    chunk.setBlockState(pos, block, false);
                    worldSurface.update(lx, wy, lz, block);
                    motionBlocking.update(lx, wy, lz, block);
                }
            }
        }
    }

    private void placeIfAir(ChunkAccess chunk, Heightmap worldSurface, Heightmap motionBlocking, long siteSeed,
                             int worldX, int worldY, int worldZ) {
        if (worldY < chunk.getMinBuildHeight() || worldY > chunk.getMaxBuildHeight() - 1) return;
        int localX = worldX - chunk.getPos().getMinBlockX();
        int localZ = worldZ - chunk.getPos().getMinBlockZ();
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        if (!chunk.getBlockState(pos).isAir()) return;

        BlockState block = blockFor(siteSeed, worldX, worldY, worldZ);
        chunk.setBlockState(pos, block, false);
        worldSurface.update(localX, worldY, localZ, block);
        motionBlocking.update(localX, worldY, localZ, block);
    }

    private static BlockState blockFor(long siteSeed, int x, int y, int z) {
        float roll = SurfaceNoise.unitHash(siteSeed ^ 0x5DEECE66DL, x + y, z - y);
        if (roll < 0.15f) return Blocks.PRISMARINE.defaultBlockState();
        if (roll < 0.35f) return Blocks.DEEPSLATE.defaultBlockState();
        if (roll < 0.55f) return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
        if (roll < 0.75f) return Blocks.COBBLESTONE.defaultBlockState();
        return Blocks.STONE.defaultBlockState();
    }
}
