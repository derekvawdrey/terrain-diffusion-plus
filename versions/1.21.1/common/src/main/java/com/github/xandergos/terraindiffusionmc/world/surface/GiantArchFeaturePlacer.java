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
 * Monument-scale free-standing arch -- the Delicate Arch / Arches National Park landmark for
 * badlands country. Unlike {@link ArchFeaturePlacer} (which spans an <em>existing</em> gap and
 * therefore never fires on flat mesa tops), this placer erects the whole structure: two thick
 * tapered legs rising well above ground and a banded red-rock arc between them. It deliberately
 * works on flat and rolling ground -- the only terrain requirement is that both leg sites are
 * on land at roughly equal elevation.
 *
 * <p>Geometry is derived entirely from the diffusion raster + site seed (the
 * {@link ArchFeaturePlacer} pattern), so every chunk the footprint touches computes the same
 * shape and contributes only its own columns. Gated to badlands-family biomes, which the
 * region-based biome catalog concentrates into one province -- so these read as a coherent
 * "arch country" rather than scattered one-offs.</p>
 */
public final class GiantArchFeaturePlacer implements SurfaceFeaturePlacer {
    private static final long SALT = 0x61A47C11E5L;
    private static final int CELL_SIZE = 176;
    private static final float SPAWN_CHANCE = 0.4f;

    private static final int MIN_SPAN = 14;
    private static final int MAX_SPAN = 30;
    private static final int RING_SAMPLES = 8;
    private static final float ELEVATION_TOLERANCE_BLOCKS = 4f;

    private static final int LEG_RADIUS = 2;
    private static final int ARCH_HALF_WIDTH = 2;
    private static final int ARCH_THICKNESS = 3;

    @Override
    public String id() {
        return "giant_arch";
    }

    @Override
    public int cellSizeBlocks() {
        return CELL_SIZE;
    }

    @Override
    public int maxReachBlocks() {
        return MAX_SPAN + LEG_RADIUS + 4;
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
        if (biomeKey == null) return;
        // Arch country: the badlands family (one province in the region catalog) plus the
        // BoP dry-rock biomes that border it there.
        boolean archBiome = biomeKey.contains("badlands")
                || biomeKey.endsWith("wasteland_steppe") || biomeKey.endsWith("dryland");
        if (!archBiome) return;

        Candidate best = findOpposingAnchor(data, dataOriginX, dataOriginZ, site, elevA);
        if (best == null) return;

        buildArch(chunk, site.seed(), site.worldX(), site.worldZ(), elevA,
                best.worldX(), best.worldZ(), best.elevation());
    }

    private record Candidate(int worldX, int worldZ, float elevation) {}

    /**
     * Unlike the gap-spanning template there is no "must find a saddle" rejection -- we only
     * need a second on-land anchor at similar elevation whose midpoint isn't a hill that would
     * bury the arc. Most directions on rolling badlands qualify; the seed picks one.
     */
    private Candidate findOpposingAnchor(HeightmapData data, int dataOriginX, int dataOriginZ,
                                          SiteGrid.Site site, float elevA) {
        double ringOffset = SurfaceNoise.unitHash(site.seed(), 0, 0) * Math.PI * 2;
        for (int i = 0; i < RING_SAMPLES; i++) {
            double angle = ringOffset + (Math.PI * 2 * i) / RING_SAMPLES;
            int span = MIN_SPAN + (int) (SurfaceNoise.unitHash(site.seed(), i, 7) * (MAX_SPAN - MIN_SPAN));
            int bx = site.worldX() + Math.round((float) Math.cos(angle) * span);
            int bz = site.worldZ() + Math.round((float) Math.sin(angle) * span);
            int rowB = bz - dataOriginZ;
            int colB = bx - dataOriginX;
            if (!TerrainSampling.inBounds(data, rowB, colB)) continue;
            float elevB = TerrainSampling.elevationAt(data, rowB, colB);
            if (elevB <= 0f) continue;
            if (Math.abs(elevB - elevA) > SurfaceStamp.blocksToElevation(ELEVATION_TOLERANCE_BLOCKS)) continue;

            int mx = (site.worldX() + bx) / 2;
            int mz = (site.worldZ() + bz) / 2;
            int rowM = mz - dataOriginZ;
            int colM = mx - dataOriginX;
            if (!TerrainSampling.inBounds(data, rowM, colM)) continue;
            float elevM = TerrainSampling.elevationAt(data, rowM, colM);
            // A rise between the legs is fine (the arc clears it); a big hill is not.
            if (elevM - Math.max(elevA, elevB) > SurfaceStamp.blocksToElevation(3f)) continue;

            return new Candidate(bx, bz, elevB);
        }
        return null;
    }

    private void buildArch(ChunkAccess chunk, long siteSeed, int ax, int az, float elevA,
                            int bx, int bz, float elevB) {
        int span = (int) Math.round(Math.hypot(bx - ax, bz - az));
        int legHeight = Math.max(8, Math.min(16, Math.round(span * 0.45f)));
        int apexRise = Math.max(3, Math.min(8, Math.round(span * 0.25f)));

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

        int steps = Math.max(24, span * 3);
        for (int t = 0; t <= steps; t++) {
            float frac = t / (float) steps;
            double centerX = ax + dx * frac;
            double centerZ = az + dz * frac;
            float baseline = SurfaceNoise.lerp(springYA, springYB, frac);
            float arcY = baseline + apexRise * 4f * frac * (1f - frac);

            // Taper: full width near the springs, slimmer at the apex, like a real arch rib.
            int halfWidth = frac > 0.25f && frac < 0.75f ? ARCH_HALF_WIDTH - 1 : ARCH_HALF_WIDTH;
            for (int w = -halfWidth; w <= halfWidth; w++) {
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
        // Root the legs a few blocks below the raster ground so they meet real terrain even
        // where the raster over-estimates it.
        int loY = Math.max(groundY - 3, chunk.getMinBuildHeight());
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
                for (int wy = loY; wy <= hiY; wy++) {
                    // Taper the leg: full radius at ground, radius-1 above two-thirds height.
                    float rise = (wy - loY) / (float) Math.max(1, hiY - loY);
                    int radius = rise > 0.66f ? LEG_RADIUS - 1 : LEG_RADIUS;
                    if (dx * dx + dz * dz > radius * radius + 1) continue;
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

    /** Badlands strata: horizontal bands of red rock, keyed to absolute Y so the bands line up
     *  across both legs and the arc no matter which chunk places them. */
    private static BlockState blockFor(long siteSeed, int x, int y, int z) {
        int band = Math.floorDiv(y + (int) (SurfaceNoise.unitHash(siteSeed, 11, 13) * 4), 3);
        return switch (Math.floorMod(band, 5)) {
            case 0 -> Blocks.RED_SANDSTONE.defaultBlockState();
            case 1 -> Blocks.ORANGE_TERRACOTTA.defaultBlockState();
            case 2 -> Blocks.SMOOTH_RED_SANDSTONE.defaultBlockState();
            case 3 -> Blocks.TERRACOTTA.defaultBlockState();
            default -> Blocks.RED_TERRACOTTA.defaultBlockState();
        };
    }
}
