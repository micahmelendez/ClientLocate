package dev.clientlocate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clientlocate.data.StructureData;
import dev.clientlocate.locate.Locator;
import dev.clientlocate.world.DimensionContext;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.InputStreamReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares this mod's results with what a real vanilla 1.21.1 server printed for /locate,
 * recorded in vanilla_locate.json (origin 0,64,0, default search radii).
 */
class ParityRunner {
    static void boot() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static RegistryKey<World> dim(String s) {
        return switch (s) {
            case "minecraft:the_nether" -> World.NETHER;
            case "minecraft:the_end" -> World.END;
            default -> World.OVERWORLD;
        };
    }

    static void run(String label, String resourceOrFile, List<Path> extra, int minPercent) throws Exception {
        boot();
        StructureData data = StructureData.load(extra);
        System.out.println(label + " loaded " + data.sets.size() + " sets, " + data.structures.size() + " structures");
        System.out.println(label + " sources " + data.sourceLog);
        System.out.println(label + " warnings " + data.warnings);
        JsonArray arr;
        try (Reader r = resourceOrFile.startsWith("/tmp/") ? new FileReader(resourceOrFile) : new InputStreamReader(ParityRunner.class.getResourceAsStream(resourceOrFile))) {
            arr = JsonParser.parseReader(r).getAsJsonArray();
        }
        Map<String, DimensionContext> ctxs = new HashMap<>();
        int ok = 0;
        int total = 0;
        List<String> bad = new ArrayList<>();
        BlockPos origin = new BlockPos(0, 64, 0);
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            String status = o.get("status").getAsString();
            if (!status.equals("found") && !status.equals("none")) continue;
            long seed = o.get("seed").getAsLong();
            String dimName = o.get("dim").getAsString();
            String kind = o.get("kind").getAsString();
            Identifier target = Identifier.tryParse(o.get("target").getAsString());
            DimensionContext ctx = ctxs.computeIfAbsent(seed + "|" + dimName, k -> DimensionContext.create(dim(dimName), seed, data.worldgen.lookup));
            Locator loc = new Locator(data, ctx, () -> false);
            long t0 = System.currentTimeMillis();
            String got;
            if (kind.equals("structure")) {
                Locator.StructureHit h = loc.locateStructure(Set.of(target), origin, 100);
                got = h == null ? "none" : h.pos().getX() + "," + h.pos().getZ();
            } else {
                Locator.BiomeHit h = loc.locateBiome(Set.of(target), origin, 6400, 32, 64);
                got = h == null ? "none" : h.pos().getX() + "," + h.pos().getZ();
            }
            String want = status.equals("none") ? "none" : o.get("x").getAsInt() + "," + o.get("z").getAsInt();
            total++;
            boolean same = got.equals(want);
            if (same) ok++;
            else bad.add(String.format("seed=%d %-9s %-40s want=%-14s got=%-14s", seed, kind, target, want, got));
            System.out.printf("%s %s seed=%d %s %s want=%s got=%s (%d ms)%n", label, same ? "OK  " : "DIFF", seed, kind, target, want, got, System.currentTimeMillis() - t0);
        }
        System.out.println(label + " SUMMARY " + ok + "/" + total + " match the real server");
        for (String b : bad) System.out.println(label + " MISMATCH " + b);
        org.junit.jupiter.api.Assertions.assertTrue(total > 0);
        org.junit.jupiter.api.Assertions.assertTrue(ok * 100 >= total * minPercent, label + " parity below " + minPercent + "%: " + ok + "/" + total);
    }
}
