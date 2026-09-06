package com.github.xandergos.terraindiffusionmc.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Adds a mountain cavern layer to a serialized YUNG's Better Caves carver.
 *
 * <p>Better Caves puts its large caverns in bands near the world floor (authored {@code -63..-18})
 * and its winding caves up to {@code y=80}. Lifted into a tall world that leaves every mountain
 * above the band solid apart from what the cave layer cuts. This clones the carver's first cavern
 * layer -- so the caverns are the ones the mod (or the pack) already tuned, with every noise
 * parameter intact -- and moves the clone's bands into the mountains: from a little above the
 * lowlands up to the summit. Lowland chunks never reach the band, so nothing changes there.</p>
 *
 * <p>Pure JSON: the clone is fed back through the mod's own codec by the caller, so a layout this
 * code does not understand simply fails to parse there and the carver runs without the layer.</p>
 */
public final class MountainCaverns {

    /** Better Caves' carver type id, the only configuration layout this understands. */
    public static final String BETTER_CAVES_CARVER = "bettercaves:better_cave";

    /**
     * Authored (vanilla-height) altitude the mountain cavern band starts at. Lifted by the world
     * scale like every other altitude, so it lands at the same height above the lowland surface
     * at every scale: at scale 2 that is y=177, which lowland terrain around y=100 never reaches.
     */
    public static final int AUTHORED_BOTTOM_Y = 120;

    private MountainCaverns() {}

    /**
     * Appends the mountain cavern layer to {@code carver}, a serialized configured carver
     * ({@code {"type": ..., "config": {...}}}).
     *
     * @param bottomY  lowest y of the band, in world blocks
     * @param topY     highest y of the band, in world blocks (the summit)
     * @param chancePercent share of cavern regions that get caverns, 0..100, Better Caves'
     *                 {@code cavern_spawn_chance}
     * @return whether a layer was added; false when the carver has no cavern layer to clone or
     *         does not look like a Better Caves carver
     */
    public static boolean addLayer(JsonElement carver, int bottomY, int topY, float chancePercent) {
        if (!(carver instanceof JsonObject root) || topY <= bottomY) return false;
        JsonElement config = root.get("config");
        if (!(config instanceof JsonObject configObject)) return false;
        JsonElement layers = configObject.get("cavern_layers");
        if (!(layers instanceof JsonArray layerArray) || layerArray.isEmpty()) return false;
        JsonElement template = layerArray.get(0);
        if (!(template instanceof JsonObject)) return false;

        JsonObject layer = template.deepCopy().getAsJsonObject();
        JsonElement carvers = layer.get("carvers");
        if (!(carvers instanceof JsonArray subCarvers) || subCarvers.isEmpty()) return false;
        for (JsonElement sub : subCarvers) {
            if (!(sub instanceof JsonObject subObject)) return false;
            subObject.addProperty("bottom_y", bottomY);
            subObject.addProperty("top_y", topY);
            // Floored caverns are the mod's lava-lake floor near bedrock; a mountain has no lava
            // level to floor against, and open caverns are what makes a system feel large.
            subObject.addProperty("is_floored", false);
        }
        layer.addProperty("cavern_spawn_chance", Math.max(0f, Math.min(100f, chancePercent)));
        layerArray.add(layer);
        return true;
    }
}
