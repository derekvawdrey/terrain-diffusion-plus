import com.github.xandergos.terraindiffusionmc.biome.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Standalone probe: prints what /api/biomes/candidate_filters would return for given biomes,
 *  without needing the mod's platform/registry bootstrap. */
public class CandProbe {
    static final String[] CH = {"Elev", "p5", "Temp", "T std", "Precip", "P CV"};

    public static void main(String[] args) throws Exception {
        String catalog = args[0];
        boolean asJson = args.length > 1 && args[1].equals("--json");
        Gson gson = new Gson();
        if (asJson) { emitJson(catalog, gson); return; }
        JsonArray entries = JsonParser.parseString(Files.readString(Path.of(catalog))).getAsJsonArray();
        List<String> want = new ArrayList<>(Arrays.asList(args).subList(1, args.length));

        for (JsonElement el : entries) {
            JsonObject o = el.getAsJsonObject();
            String key = o.get("key").getAsString();
            if (!want.isEmpty() && !want.contains(key)) continue;
            if (!o.has("rules") || o.getAsJsonArray("rules").isEmpty()) continue;

            TerrainBiomeSettlement s = gson.fromJson(o, TerrainBiomeSettlement.class);
            // Gson leaves the transient resolvedVariable null; resolvedVariable() self-heals.
            for (TerrainBiomeRule r : s.rules()) {
                for (TerrainBiomeCondition c : r.conditions()) c.resolvedVariable();
                for (TerrainBiomeCondition c : r.noiseConditions()) c.resolvedVariable();
            }

            System.out.println("=== " + key);
            for (BiomeCandidateFilterCalculator.ZoneCandidate zc
                    : BiomeCandidateFilterCalculator.compute(s)) {
                StringBuilder sb = new StringBuilder();
                sb.append("  zone=").append(zc.zone()).append(" rules=").append(zc.ruleCount())
                  .append("  filters: ");
                if (zc.channelFilters().isEmpty()) sb.append("(none)");
                for (Map.Entry<Integer, float[]> e : zc.channelFilters().entrySet()) {
                    float lo = e.getValue()[0], hi = e.getValue()[1];
                    sb.append(CH[e.getKey()]).append(' ')
                      .append(Float.isInfinite(lo) ? "*" : String.format("%.0f", lo))
                      .append("..")
                      .append(Float.isInfinite(hi) ? "*" : String.format("%.0f", hi))
                      .append("   ");
                }
                System.out.println(sb);
                if (!zc.caveats().isEmpty()) {
                    System.out.println("    caveats: " + String.join("; ", zc.caveats()));
                }
            }
        }
    }

    /** {biomeKey: {zone: {channel: [min, max]}}} with nulls for open ends, for the Python checker. */
    static void emitJson(String catalog, Gson gson) throws Exception {
        JsonArray entries = JsonParser.parseString(Files.readString(Path.of(catalog))).getAsJsonArray();
        JsonObject root = new JsonObject();
        for (JsonElement el : entries) {
            JsonObject o = el.getAsJsonObject();
            if (!o.has("rules") || o.getAsJsonArray("rules").isEmpty()) continue;
            TerrainBiomeSettlement s = gson.fromJson(o, TerrainBiomeSettlement.class);
            for (TerrainBiomeRule r : s.rules()) {
                for (TerrainBiomeCondition c : r.conditions()) c.resolvedVariable();
                for (TerrainBiomeCondition c : r.noiseConditions()) c.resolvedVariable();
            }
            JsonObject zones = new JsonObject();
            for (BiomeCandidateFilterCalculator.ZoneCandidate zc
                    : BiomeCandidateFilterCalculator.compute(s)) {
                JsonObject chans = new JsonObject();
                for (Map.Entry<Integer, float[]> e : zc.channelFilters().entrySet()) {
                    JsonArray pair = new JsonArray();
                    float lo = e.getValue()[0], hi = e.getValue()[1];
                    pair.add(Float.isInfinite(lo) ? null : Float.valueOf(lo));
                    pair.add(Float.isInfinite(hi) ? null : Float.valueOf(hi));
                    chans.add(String.valueOf(e.getKey()), pair);
                }
                zones.add(zc.zone(), chans);
            }
            root.add(o.get("key").getAsString(), zones);
        }
        System.out.println(gson.toJson(root));
    }
}
