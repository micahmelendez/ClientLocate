package dev.clientlocate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saved seeds (per server address) and extra folders to scan for datapacks. */
public final class ClientLocateConfig {
    private static final Logger LOG = LoggerFactory.getLogger("clientlocate");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** server address (or "singleplayer:<world>") to world seed */
    public Map<String, Long> seeds = new LinkedHashMap<>();
    /** extra folders or zips holding datapacks, relative paths are resolved from the game folder */
    public List<String> extraDataPaths = new ArrayList<>();
    public int structureRadius = 100;
    public int biomeRadius = 6400;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("clientlocate.json");
    }

    public static Path dropFolder() {
        return FabricLoader.getInstance().getConfigDir().resolve("clientlocate").resolve("datapacks");
    }

    public static ClientLocateConfig load() {
        ClientLocateConfig cfg = null;
        Path f = file();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f)) {
                cfg = GSON.fromJson(r, ClientLocateConfig.class);
            } catch (Exception e) {
                LOG.warn("Could not read {}", f, e);
            }
        }
        if (cfg == null) cfg = new ClientLocateConfig();
        if (cfg.seeds == null) cfg.seeds = new LinkedHashMap<>();
        if (cfg.extraDataPaths == null) cfg.extraDataPaths = new ArrayList<>();
        try {
            Files.createDirectories(dropFolder());
        } catch (IOException ignored) {
        }
        return cfg;
    }

    public synchronized void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(this, w);
        } catch (IOException e) {
            LOG.warn("Could not save config", e);
        }
    }

    /** Every folder that is scanned for datapacks, in load order. */
    public List<Path> dataFolders() {
        Path game = FabricLoader.getInstance().getGameDir();
        List<Path> out = new ArrayList<>();
        out.add(game.resolve("datapacks"));
        out.add(game.resolve("config").resolve("openloader").resolve("data"));
        out.add(game.resolve("config").resolve("paxi").resolve("datapacks"));
        out.add(dropFolder());
        for (String s : extraDataPaths) {
            Path p = Path.of(s);
            out.add(p.isAbsolute() ? p : game.resolve(p));
        }
        return out;
    }
}
