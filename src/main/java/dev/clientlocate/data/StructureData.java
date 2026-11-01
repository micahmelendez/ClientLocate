package dev.clientlocate.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Structure sets, structures and tags read straight from the data folders of the vanilla jar,
 * every installed mod and any extra datapack folders. Later sources override earlier ones,
 * the same way the game stacks datapacks.
 */
public final class StructureData {
    private static final Logger LOG = LoggerFactory.getLogger("clientlocate");

    public enum Kind { RANDOM_SPREAD, CONCENTRIC_RINGS, UNSUPPORTED }

    public static final class StructureDef {
        public final Identifier id;
        public final String type;
        public final JsonObject json;
        Set<Identifier> biomes;

        StructureDef(Identifier id, JsonObject json) {
            this.id = id;
            this.json = json;
            this.type = json.has("type") && json.get("type").isJsonPrimitive() ? json.get("type").getAsString() : "";
        }

        public Set<Identifier> biomes() {
            return biomes;
        }
    }

    public record SetEntry(Identifier structure, int weight) {}

    public static final class SetDef {
        public final Identifier id;
        public final Kind kind;
        public final String placementType;
        public final List<SetEntry> entries = new ArrayList<>();
        public RandomSpreadStructurePlacement randomSpread;
        public int ringDistance, ringSpread, ringCount;
        public Set<Identifier> ringBiomes = Set.of();
        public Identifier exclusionSet;
        public int exclusionChunks;

        SetDef(Identifier id, Kind kind, String placementType) {
            this.id = id;
            this.kind = kind;
            this.placementType = placementType;
        }
    }

    private record TagEntry(Identifier id, boolean isTag) {}

    private record TagFile(boolean replace, List<TagEntry> entries) {}

    public final Map<Identifier, StructureDef> structures = new LinkedHashMap<>();
    public final Map<Identifier, SetDef> sets = new LinkedHashMap<>();
    public final Set<Identifier> biomeIds = new TreeSet<>();
    public final List<String> sourceLog = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    private final Map<Identifier, JsonObject> rawStructures = new LinkedHashMap<>();
    private final Map<Identifier, JsonObject> rawSets = new LinkedHashMap<>();
    private final Map<Identifier, List<TagEntry>> biomeTags = new HashMap<>();
    private final Map<Identifier, List<TagEntry>> structureTags = new HashMap<>();

    private final List<FileSystem> openedZips = new ArrayList<>();
    /** Every data root that was read, in priority order (game first, datapacks last). */
    public final List<Path> dataRoots = new ArrayList<>();
    private boolean prependRoot;
    /** Terrain, biome and dimension data loaded the way a server loads it. */
    public dev.clientlocate.world.WorldgenData worldgen;
    private final Map<Identifier, JsonObject> rawPools = new HashMap<>();
    private final Map<Identifier, List<JsonObject>> poolCache = new HashMap<>();
    private final Map<Identifier, Path> templatePaths = new HashMap<>();
    private final Map<Identifier, StructureTemplate> templateCache = new HashMap<>();

    // ---------------------------------------------------------------- loading

    public static StructureData load(List<Path> extraDirs) {
        StructureData d = new StructureData();
        d.scanAll(extraDirs);
        d.build();
        try {
            d.worldgen = dev.clientlocate.world.WorldgenData.load(d.dataRoots);
        } catch (Throwable t) {
            d.worldgen = dev.clientlocate.world.WorldgenData.vanilla("worldgen loading failed: " + t);
        }
        d.sourceLog.add("terrain and biomes: " + d.worldgen.status);
        if (!d.worldgen.custom) d.warnings.add(d.worldgen.status);
        return d;
    }

    private void scanAll(List<Path> extraDirs) {
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort(Comparator.comparing((ModContainer m) -> !m.getMetadata().getId().equals("minecraft"))
                .thenComparing(m -> m.getMetadata().getId()));
        for (ModContainer mod : mods) {
            int before = rawSets.size() + rawStructures.size();
            for (Path root : mod.getRootPaths()) {
                try {
                    readDataRoot(root);
                } catch (Exception e) {
                    LOG.warn("Could not read data of mod {}", mod.getMetadata().getId(), e);
                }
            }
            int added = rawSets.size() + rawStructures.size() - before;
            if (added > 0 && !mod.getMetadata().getId().equals("minecraft")) {
                sourceLog.add("mod " + mod.getMetadata().getId() + " (" + added + " worldgen entries)");
            }
        }
        if (!rawSets.containsKey(Identifier.of("minecraft", "villages"))) scanVanillaFromClasspath();
        for (Path dir : extraDirs) {
            try {
                scanExtra(dir, 0);
            } catch (Exception e) {
                LOG.warn("Could not scan {}", dir, e);
            }
        }
    }

    /** Releases zip files opened while scanning datapacks. */
    public void close() {
        for (FileSystem fs : openedZips) {
            try {
                fs.close();
            } catch (IOException ignored) {
            }
        }
        openedZips.clear();
    }

    /** Fallback if the loader did not expose the game jar as a mod: find vanilla data through the class loader. */
    private void scanVanillaFromClasspath() {
        try {
            java.net.URL url = StructureData.class.getClassLoader().getResource("data/minecraft/worldgen/structure_set/villages.json");
            if (url == null) {
                warnings.add("Vanilla worldgen data was not found, only mod data is available");
                return;
            }
            String u = url.toString();
            Path root;
            if (u.startsWith("jar:")) {
                java.net.URI jarUri = java.net.URI.create(u.substring(0, u.indexOf("!/")));
                FileSystem fs = FileSystems.newFileSystem(Path.of(java.net.URI.create(jarUri.getSchemeSpecificPart())));
                openedZips.add(fs);
                root = fs.getPath("/");
            } else {
                root = Path.of(url.toURI()).getParent().getParent().getParent().getParent().getParent();
            }
            prependRoot = true;
            readDataRoot(root);
            prependRoot = false;
            sourceLog.add("vanilla data via class path");
        } catch (Exception e) {
            warnings.add("Could not read vanilla data from the class path: " + e);
        }
    }

    private void scanExtra(Path path, int depth) throws IOException {
        if (!Files.exists(path) || depth > 3) return;
        String name = clean(path.getFileName() == null ? path.toString() : path.getFileName().toString());
        if (Files.isRegularFile(path)) {
            if (name.endsWith(".zip") || name.endsWith(".jar")) {
                FileSystem fs = FileSystems.newFileSystem(path);
                openedZips.add(fs);
                int before = rawSets.size() + rawStructures.size();
                for (Path root : fs.getRootDirectories()) readDataRoot(root);
                sourceLog.add("datapack " + name + " (" + (rawSets.size() + rawStructures.size() - before) + " worldgen entries)");
            }
            return;
        }
        if (Files.isDirectory(path.resolve("data"))) {
            int before = rawSets.size() + rawStructures.size();
            readDataRoot(path);
            sourceLog.add("datapack folder " + name + " (" + (rawSets.size() + rawStructures.size() - before) + " worldgen entries)");
            return;
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
            for (Path p : ds) children.add(p);
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path child : children) scanExtra(child, depth + 1);
    }

    private static String clean(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void readDataRoot(Path root) throws IOException {
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return;
        if (prependRoot) dataRoots.add(0, root);
        else dataRoots.add(root);
        List<Path> namespaces = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(data)) {
            for (Path p : ds) if (Files.isDirectory(p)) namespaces.add(p);
        }
        namespaces.sort(Comparator.comparing(p -> clean(p.getFileName().toString())));
        for (Path nsDir : namespaces) {
            String ns = clean(nsDir.getFileName().toString());
            readDir(nsDir.resolve("worldgen").resolve("structure_set"), ns, (id, json) -> rawSets.put(id, json));
            readDir(nsDir.resolve("worldgen").resolve("structure"), ns, (id, json) -> rawStructures.put(id, json));
            readDir(nsDir.resolve("worldgen").resolve("biome"), ns, (id, json) -> biomeIds.add(id));
            readDir(nsDir.resolve("worldgen").resolve("template_pool"), ns, (id, json) -> rawPools.put(id, json));
            indexTemplates(nsDir.resolve("structure"), ns);
            readDir(nsDir.resolve("tags").resolve("worldgen").resolve("biome"), ns, (id, json) -> mergeTag(biomeTags, id, json));
            readDir(nsDir.resolve("tags").resolve("worldgen").resolve("structure"), ns, (id, json) -> mergeTag(structureTags, id, json));
        }
    }

    private void indexTemplates(Path dir, String ns) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".nbt")).toList()) {
                String rel = clean(dir.relativize(file).toString()).replace('\\', '/');
                Identifier id = Identifier.tryParse(ns + ":" + rel.substring(0, rel.length() - ".nbt".length()));
                if (id != null) templatePaths.put(id, file);
            }
        }
    }

    /** Elements of a template pool, repeated by weight like StructurePool does, or null if the pool is unknown. */
    public List<JsonObject> poolElements(Identifier pool) {
        List<JsonObject> cached = poolCache.get(pool);
        if (cached != null) return cached;
        JsonObject raw = rawPools.get(pool);
        if (raw == null || !raw.has("elements")) return null;
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : raw.getAsJsonArray("elements")) {
            JsonObject o = e.getAsJsonObject();
            int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
            for (int i = 0; i < weight; i++) out.add(o.getAsJsonObject("element"));
        }
        poolCache.put(pool, out);
        return out;
    }

    /** A structure template (.nbt) from any loaded mod or datapack, or null if it is missing or unreadable. */
    public StructureTemplate template(Identifier id) {
        StructureTemplate cached = templateCache.get(id);
        if (cached != null) return cached;
        Path p = templatePaths.get(id);
        if (p == null) return null;
        try (InputStream in = Files.newInputStream(p)) {
            NbtCompound nbt = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
            StructureTemplate t = new StructureTemplate();
            t.readNbt(Registries.BLOCK.getReadOnlyWrapper(), nbt);
            templateCache.put(id, t);
            return t;
        } catch (Exception e) {
            LOG.warn("Could not read template {}: {}", id, e.toString());
            return null;
        }
    }

    private interface JsonSink {
        void accept(Identifier id, JsonObject json);
    }

    private void readDir(Path dir, String ns, JsonSink sink) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        for (Path file : files) {
            String rel = clean(dir.relativize(file).toString()).replace('\\', '/');
            rel = rel.substring(0, rel.length() - ".json".length());
            Identifier id = Identifier.tryParse(ns + ":" + rel);
            if (id == null) continue;
            try (Reader r = Files.newBufferedReader(file)) {
                JsonElement el = JsonParser.parseReader(r);
                if (el.isJsonObject()) sink.accept(id, el.getAsJsonObject());
            } catch (Exception e) {
                warnings.add("Could not parse " + id + ": " + e.getMessage());
            }
        }
    }

    private static void mergeTag(Map<Identifier, List<TagEntry>> target, Identifier id, JsonObject json) {
        boolean replace = json.has("replace") && json.get("replace").getAsBoolean();
        List<TagEntry> list = replace ? new ArrayList<>() : target.computeIfAbsent(id, k -> new ArrayList<>());
        if (replace) target.put(id, list);
        JsonArray values = json.has("values") && json.get("values").isJsonArray() ? json.getAsJsonArray("values") : new JsonArray();
        for (JsonElement v : values) {
            String raw = null;
            if (v.isJsonPrimitive()) raw = v.getAsString();
            else if (v.isJsonObject() && v.getAsJsonObject().has("id")) raw = v.getAsJsonObject().get("id").getAsString();
            if (raw == null) continue;
            boolean isTag = raw.startsWith("#");
            Identifier eid = Identifier.tryParse(isTag ? raw.substring(1) : raw);
            if (eid != null) list.add(new TagEntry(eid, isTag));
        }
    }

    // ---------------------------------------------------------------- building the model

    private void build() {
        for (Map.Entry<Identifier, JsonObject> e : rawStructures.entrySet()) {
            StructureDef def = new StructureDef(e.getKey(), e.getValue());
            def.biomes = resolveBiomes(e.getValue().get("biomes"));
            structures.put(e.getKey(), def);
        }
        for (Map.Entry<Identifier, JsonObject> e : rawSets.entrySet()) {
            try {
                SetDef set = parseSet(e.getKey(), e.getValue());
                if (set != null) sets.put(e.getKey(), set);
            } catch (Exception ex) {
                warnings.add("Structure set " + e.getKey() + " skipped: " + ex.getMessage());
            }
        }
    }

    private SetDef parseSet(Identifier id, JsonObject json) {
        JsonObject placement = json.getAsJsonObject("placement");
        if (placement == null) return null;
        String type = placement.has("type") ? placement.get("type").getAsString() : "";
        String normType = type.contains(":") ? type : "minecraft:" + type;
        Kind kind = switch (normType) {
            case "minecraft:random_spread" -> Kind.RANDOM_SPREAD;
            case "minecraft:concentric_rings" -> Kind.CONCENTRIC_RINGS;
            default -> Kind.UNSUPPORTED;
        };
        SetDef set = new SetDef(id, kind, normType);
        JsonArray structs = json.has("structures") ? json.getAsJsonArray("structures") : new JsonArray();
        for (JsonElement el : structs) {
            JsonObject o = el.getAsJsonObject();
            Identifier sid = Identifier.tryParse(o.get("structure").getAsString());
            int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
            if (sid != null) set.entries.add(new SetEntry(sid, Math.max(1, weight)));
        }
        if (kind == Kind.UNSUPPORTED) {
            warnings.add("Structure set " + id + " uses placement type " + normType + " which is not supported");
            return set;
        }
        JsonObject copy = placement.deepCopy();
        if (copy.has("exclusion_zone")) {
            JsonObject ez = copy.getAsJsonObject("exclusion_zone");
            copy.remove("exclusion_zone");
            set.exclusionSet = Identifier.tryParse(ez.get("other_set").getAsString());
            set.exclusionChunks = ez.get("chunk_count").getAsInt();
        }
        if (kind == Kind.RANDOM_SPREAD) {
            StructurePlacement p = StructurePlacement.TYPE_CODEC.parse(JsonOps.INSTANCE, copy)
                    .getOrThrow(msg -> new IllegalStateException(msg));
            if (!(p instanceof RandomSpreadStructurePlacement rs)) throw new IllegalStateException("not random_spread");
            set.randomSpread = rs;
        } else {
            set.ringDistance = copy.get("distance").getAsInt();
            set.ringSpread = copy.get("spread").getAsInt();
            set.ringCount = copy.get("count").getAsInt();
            set.ringBiomes = resolveBiomes(copy.get("preferred_biomes"));
        }
        return set;
    }

    // ---------------------------------------------------------------- tags

    public Set<Identifier> resolveBiomes(JsonElement el) {
        Set<Identifier> out = new HashSet<>();
        if (el == null || el.isJsonNull()) return out;
        if (el.isJsonPrimitive()) {
            addBiomeRef(el.getAsString(), out);
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive()) addBiomeRef(e.getAsString(), out);
            }
        }
        return out;
    }

    private void addBiomeRef(String raw, Set<Identifier> out) {
        if (raw.startsWith("#")) {
            Identifier tag = Identifier.tryParse(raw.substring(1));
            if (tag != null) expandTag(biomeTags, tag, out, new HashSet<>());
        } else {
            Identifier id = Identifier.tryParse(raw);
            if (id != null) out.add(id);
        }
    }

    private static void expandTag(Map<Identifier, List<TagEntry>> tags, Identifier tag, Set<Identifier> out, Set<Identifier> visiting) {
        if (!visiting.add(tag)) return;
        List<TagEntry> list = tags.get(tag);
        if (list != null) {
            for (TagEntry e : list) {
                if (e.isTag()) expandTag(tags, e.id(), out, visiting);
                else out.add(e.id());
            }
        }
        visiting.remove(tag);
    }

    /** Accepts "ns:id" or "#ns:tag" for structures. Returns an empty set if nothing matches. */
    public Set<Identifier> resolveStructureTarget(String input) {
        Set<Identifier> out = new HashSet<>();
        if (input.startsWith("#")) {
            Identifier tag = Identifier.tryParse(input.substring(1));
            if (tag != null) expandTag(structureTags, tag, out, new HashSet<>());
        } else {
            Identifier id = Identifier.tryParse(input);
            if (id != null) out.add(id);
        }
        return out;
    }

    public Set<Identifier> resolveBiomeTarget(String input) {
        Set<Identifier> out = new HashSet<>();
        addBiomeRef(input, out);
        return out;
    }

    // ---------------------------------------------------------------- queries

    /** Structure ids that appear in at least one structure set, for suggestions. */
    public Set<Identifier> locatableStructures() {
        Set<Identifier> out = new TreeSet<>();
        for (SetDef s : sets.values()) {
            for (SetEntry e : s.entries) out.add(e.structure());
        }
        return out;
    }

    public Set<Identifier> structureTagIds() {
        return new TreeSet<>(structureTags.keySet());
    }

    public Set<Identifier> biomeTagIds() {
        return new TreeSet<>(biomeTags.keySet());
    }

    public List<SetDef> setsContaining(Set<Identifier> targets) {
        List<SetDef> out = new ArrayList<>();
        for (SetDef s : sets.values()) {
            for (SetEntry e : s.entries) {
                if (targets.contains(e.structure())) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }
}
