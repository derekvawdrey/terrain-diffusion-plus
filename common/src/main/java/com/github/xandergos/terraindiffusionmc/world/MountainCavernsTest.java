package com.github.xandergos.terraindiffusionmc.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Standalone check for {@link MountainCaverns} against Better Caves' shipped carver layout. */
public class MountainCavernsTest {
    private static int failures = 0;

    public static void main(String[] args) {
        String shipped = "{\"type\":\"bettercaves:better_cave\",\"config\":{\"cave_layers\":[{\"carvers\":[{\"spawn_weight\":10,"
                + "\"bottom_y\":-63,\"top_y\":80,\"surface_cutoff_distance\":15,\"y_compression\":5.0,\"xz_compression\":1.6,"
                + "\"advanced\":{\"noise_threshold\":0.95}}],\"cave_spawn_chance\":100.0,\"cave_region_size_frequency\":0.008}],"
                + "\"cavern_layers\":[{\"carvers\":[{\"spawn_weight\":10,\"bottom_y\":-63,\"top_y\":-18,\"y_compression\":1.1,"
                + "\"xz_compression\":0.6,\"is_floored\":false,\"advanced\":{\"noise_threshold\":0.6,\"noise_type\":\"SimplexFractal\"}},"
                + "{\"spawn_weight\":10,\"bottom_y\":-63,\"top_y\":-28,\"y_compression\":1.3,\"xz_compression\":0.7,\"is_floored\":true,"
                + "\"advanced\":{\"noise_threshold\":0.6}}],\"cavern_spawn_chance\":23.0,\"cavern_region_size_frequency\":0.005}],"
                + "\"misc\":{\"override_surface_detection\":false}}}";
        JsonObject carver = JsonParser.parseString(shipped).getAsJsonObject();
        boolean added = MountainCaverns.addLayer(carver, 177, 735, 30f);
        check("layer added to the shipped layout", added);
        JsonArray layers = carver.getAsJsonObject("config").getAsJsonArray("cavern_layers");
        check("original layer untouched", layers.get(0).getAsJsonObject().getAsJsonArray("carvers").get(0)
                .getAsJsonObject().get("top_y").getAsInt() == -18);
        check("one layer appended", layers.size() == 2);
        JsonObject added1 = layers.get(1).getAsJsonObject();
        check("spawn chance applied", added1.get("cavern_spawn_chance").getAsFloat() == 30f);
        check("region frequency inherited", added1.get("cavern_region_size_frequency").getAsFloat() == 0.005f);
        JsonArray subs = added1.getAsJsonArray("carvers");
        check("both sub-carvers cloned", subs.size() == 2);
        for (int i = 0; i < subs.size(); i++) {
            JsonObject sub = subs.get(i).getAsJsonObject();
            check("sub " + i + " band is 177..735", sub.get("bottom_y").getAsInt() == 177 && sub.get("top_y").getAsInt() == 735);
            check("sub " + i + " is not floored", !sub.get("is_floored").getAsBoolean());
            check("sub " + i + " keeps its noise settings", sub.getAsJsonObject("advanced").get("noise_threshold").getAsFloat() == 0.6f);
        }
        check("carver without cavern layers is refused",
                !MountainCaverns.addLayer(JsonParser.parseString("{\"type\":\"x\",\"config\":{\"cavern_layers\":[]}}"), 177, 735, 30f));
        check("vanilla-shaped carver is refused",
                !MountainCaverns.addLayer(JsonParser.parseString("{\"type\":\"minecraft:cave\",\"config\":{\"probability\":0.15}}"), 177, 735, 30f));
        check("inverted band is refused", !MountainCaverns.addLayer(carver.deepCopy(), 735, 177, 30f));
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) System.exit(1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
