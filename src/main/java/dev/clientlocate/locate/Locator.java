package dev.clientlocate.locate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import dev.clientlocate.data.StructureData;
import dev.clientlocate.data.StructureData.SetDef;
import dev.clientlocate.data.StructureData.SetEntry;
import dev.clientlocate.data.StructureData.StructureDef;
import dev.clientlocate.data.StructureData.Kind;
import dev.clientlocate.world.DimensionContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.HeightContext;
import net.minecraft.world.gen.heightprovider.HeightProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Re-implementation of the server's /locate for structures and biomes. Search order and
 * tie-breaking follow ChunkGenerator.locateStructure so results line up with vanilla.
 */
public final class Locator {
    public record StructureHit(BlockPos pos, Identifier structure, Identifier set) {}

    public record BiomeHit(BlockPos pos, Identifier biome) {}

    private final StructureData data;
    private final DimensionContext ctx;
    private final BooleanSupplier cancelled;
    private final Map<String, HeightProvider> startHeights = new HashMap<>();
    private final Map<Identifier, List<ChunkPos>> ringCache = new HashMap<>();
    private final Map<Identifier, Boolean> applies = new HashMap<>();

    public Locator(StructureData data, DimensionContext ctx, BooleanSupplier cancelled) {
        this.data = data;
        this.ctx = ctx;
        this.cancelled = cancelled;
    }

    // ------------------------------------------------------------------ structures

    public StructureHit locateStructure(Set<Identifier> targets, BlockPos origin, int radius) {
        List<SetDef> spread = new ArrayList<>();
        StructureHit best = null;
        double bestD = Double.MAX_VALUE;
        for (SetDef s : data.setsContaining(targets)) {
            if (s.kind == Kind.UNSUPPORTED || !appliesHere(s)) continue;
            if (s.kind == Kind.CONCENTRIC_RINGS) {
                StructureHit h = locateRings(s, targets, origin);
                if (h != null) {
                    double d = origin.getSquaredDistance(h.pos());
                    if (d < bestD) {
                        bestD = d;
                        best = h;
                    }
                }
            } else {
                spread.add(s);
            }
        }
        if (spread.isEmpty()) return best;
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        for (int k = 0; k <= radius; k++) {
            if (cancelled.getAsBoolean()) return null;
            boolean found = false;
            for (SetDef s : spread) {
                StructureHit h = ring(s, targets, cx, cz, k);
                if (h != null) {
                    found = true;
                    double d = origin.getSquaredDistance(h.pos());
                    if (d < bestD) {
                        bestD = d;
                        best = h;
                    }
                }
            }
            if (found) return best;
        }
        return best;
    }

    private StructureHit ring(SetDef s, Set<Identifier> targets, int cx, int cz, int radius) {
        int spacing = s.randomSpread.getSpacing();
        for (int j = -radius; j <= radius; j++) {
            boolean edgeJ = j == -radius || j == radius;
            for (int k = -radius; k <= radius; k++) {
                boolean edgeK = k == -radius || k == radius;
                if (!edgeJ && !edgeK) continue;
                ChunkPos cp = s.randomSpread.getStartChunk(ctx.seed, cx + spacing * j, cz + spacing * k);
                if (!placementPasses(s, cp.x, cp.z, 0)) continue;
                Identifier started = startedStructure(s, cp);
                if (started != null && targets.contains(started)) {
                    return new StructureHit(s.randomSpread.getLocatePos(cp), started, s.id);
                }
            }
        }
        return null;
    }

    private StructureHit locateRings(SetDef s, Set<Identifier> targets, BlockPos origin) {
        StructureHit best = null;
        double d = Double.MAX_VALUE;
        for (ChunkPos cp : ringPositions(s)) {
            if (cancelled.getAsBoolean()) return null;
            double e = new BlockPos(cp.x * 16 + 8, 32, cp.z * 16 + 8).getSquaredDistance(origin);
            if (best == null || e < d) {
                Identifier started = startedStructure(s, cp);
                if (started != null && targets.contains(started)) {
                    best = new StructureHit(new BlockPos(cp.getStartX(), 0, cp.getStartZ()), started, s.id);
                    d = e;
                }
            }
        }
        return best;
    }

    /** Does this set generate in the current dimension, i.e. can any of its structures use a biome found here. */
    private boolean appliesHere(SetDef s) {
        return applies.computeIfAbsent(s.id, id -> {
            for (SetEntry e : s.entries) {
                StructureDef def = data.structures.get(e.structure());
                if (def == null) continue;
                for (Identifier b : def.biomes()) if (ctx.biomeIds.contains(b)) return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ placement

    private boolean placementPasses(SetDef s, int x, int z, int depth) {
        if (s.kind == Kind.RANDOM_SPREAD) {
            ChunkPos start = s.randomSpread.getStartChunk(ctx.seed, x, z);
            if (start.x != x || start.z != z) return false;
            if (!s.randomSpread.applyFrequencyReduction(x, z, ctx.seed)) return false;
        } else if (s.kind == Kind.CONCENTRIC_RINGS) {
            boolean in = false;
            for (ChunkPos p : ringPositions(s)) {
                if (p.x == x && p.z == z) {
                    in = true;
                    break;
                }
            }
            if (!in) return false;
        } else {
            return false;
        }
        if (s.exclusionSet != null && depth < 4) {
            SetDef other = data.sets.get(s.exclusionSet);
            if (other != null && other.kind != Kind.UNSUPPORTED) {
                int n = s.exclusionChunks;
                for (int i = x - n; i <= x + n; i++) {
                    for (int j = z - n; j <= z + n; j++) {
                        if (placementPasses(other, i, j, depth + 1)) return false;
                    }
                }
            }
        }
        return true;
    }

    private List<ChunkPos> ringPositions(SetDef s) {
        return ringCache.computeIfAbsent(s.id, id -> computeRings(s));
    }

    /** Port of StructurePlacementCalculator.calculateConcentricsRingPlacementPos. */
    private List<ChunkPos> computeRings(SetDef s) {
        int distance = s.ringDistance;
        int count = s.ringCount;
        int spread = s.ringSpread;
        List<ChunkPos> out = new ArrayList<>();
        if (count == 0) return out;
        Random random = Random.create();
        random.setSeed(ctx.seed);
        double angle = random.nextDouble() * Math.PI * 2.0;
        int inRing = 0;
        int ring = 0;
        for (int n = 0; n < count; n++) {
            double dist = 4 * distance + distance * ring * 6 + (random.nextDouble() - 0.5) * (distance * 2.5);
            int px = (int) Math.round(Math.cos(angle) * dist);
            int pz = (int) Math.round(Math.sin(angle) * dist);
            Random random2 = random.split();
            Pair<BlockPos, RegistryEntry<Biome>> found = ctx.biomeSource.locateBiome(
                    (px << 4) + 8, 0, (pz << 4) + 8, 112,
                    e -> e.getKey().map(k -> s.ringBiomes.contains(k.getValue())).orElse(false),
                    random2, ctx.sampler());
            if (found != null) {
                BlockPos bp = found.getFirst();
                px = bp.getX() >> 4;
                pz = bp.getZ() >> 4;
            }
            out.add(new ChunkPos(px, pz));
            angle += (Math.PI * 2) / spread;
            if (++inRing == spread) {
                ring++;
                inRing = 0;
                spread += 2 * spread / (ring + 1);
                spread = Math.min(spread, count - n);
                angle += random.nextDouble() * Math.PI * 2.0;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ which structure starts in a chunk

    /** Port of ChunkGenerator.setStructureStarts: weighted pick, falling back when a variant is invalid. */
    private Identifier startedStructure(SetDef s, ChunkPos cp) {
        List<SetEntry> list = s.entries;
        if (list.isEmpty()) return null;
        if (list.size() == 1) return structureValid(list.get(0).structure(), cp) ? list.get(0).structure() : null;
        List<SetEntry> pool = new ArrayList<>(list);
        ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
        random.setCarverSeed(ctx.seed, cp.x, cp.z);
        int total = 0;
        for (SetEntry e : pool) total += e.weight();
        while (!pool.isEmpty()) {
            int roll = random.nextInt(total);
            int idx = 0;
            for (SetEntry e : pool) {
                roll -= e.weight();
                if (roll < 0) break;
                idx++;
            }
            SetEntry pick = pool.get(idx);
            if (structureValid(pick.structure(), cp)) return pick.structure();
            pool.remove(idx);
            total -= pick.weight();
        }
        return null;
    }

    // ------------------------------------------------------------------ structure validity (biome and terrain)

    private boolean structureValid(Identifier sid, ChunkPos cp) {
        StructureDef def = data.structures.get(sid);
        if (def == null) return false;
        ChunkRandom random = new ChunkRandom(new CheckedRandom(0L));
        random.setCarverSeed(ctx.seed, cp.x, cp.z);
        int[] p = samplePosition(def, cp, random);
        if (p == null) return false;
        Identifier biome = ctx.biomeAt(p[0], p[1], p[2]);
        return biome != null && def.biomes().contains(biome);
    }

    /** Where vanilla checks the biome for this structure start, or null when the start would be rejected outright. */
    private int[] samplePosition(StructureDef def, ChunkPos cp, ChunkRandom random) {
        int cx = cp.getCenterX();
        int cz = cp.getCenterZ();
        switch (def.type) {
            case "minecraft:jigsaw" -> {
                return jigsaw(def, cp, random);
            }
            case "minecraft:ocean_monument" -> {
                Set<Identifier> allowed = data.resolveBiomeTarget("#minecraft:required_ocean_monument_surrounding");
                for (RegistryEntry<Biome> b : ctx.biomeSource.getBiomesInArea(cp.getOffsetX(9), ctx.seaLevel, cp.getOffsetZ(9), 29, ctx.sampler())) {
                    Identifier id = b.getKey().map(k -> k.getValue()).orElse(null);
                    if (id == null || !allowed.contains(id)) return null;
                }
                return new int[]{cx, ctx.heightAt(cx, cz, Heightmap.Type.OCEAN_FLOOR_WG) - 1, cz};
            }
            case "minecraft:woodland_mansion", "minecraft:end_city" -> {
                BlockRotation rot = BlockRotation.random(random);
                int w = 5;
                int h = 5;
                if (rot == BlockRotation.CLOCKWISE_90) {
                    w = -5;
                } else if (rot == BlockRotation.CLOCKWISE_180) {
                    w = -5;
                    h = -5;
                } else if (rot == BlockRotation.COUNTERCLOCKWISE_90) {
                    h = -5;
                }
                int x = cp.getOffsetX(7);
                int z = cp.getOffsetZ(7);
                int y = minCorner(x, z, w, h);
                return y < 60 ? null : new int[]{x, y, z};
            }
            case "minecraft:desert_pyramid", "minecraft:jungle_temple" -> {
                int w = def.type.equals("minecraft:desert_pyramid") ? 21 : 12;
                int h = def.type.equals("minecraft:desert_pyramid") ? 21 : 15;
                if (minCorner(cp.getStartX(), cp.getStartZ(), w, h) < ctx.seaLevel) return null;
                return new int[]{cx, ctx.heightAt(cx, cz, Heightmap.Type.WORLD_SURFACE_WG) - 1, cz};
            }
            case "minecraft:mineshaft" -> {
                return new int[]{cx, 50, cp.getStartZ()};
            }
            case "minecraft:ruined_portal" -> {
                String setups = def.json.has("setups") ? def.json.get("setups").toString() : "";
                if (setups.contains("in_nether")) {
                    return new int[]{cx, ctx.heightAt(cx, cz, Heightmap.Type.WORLD_SURFACE_WG) - 1, cz};
                }
                Heightmap.Type t = setups.contains("on_ocean_floor") ? Heightmap.Type.OCEAN_FLOOR_WG : Heightmap.Type.WORLD_SURFACE_WG;
                int x = cp.getStartX();
                int z = cp.getStartZ();
                return new int[]{x, ctx.heightAt(x, z, t) - 1, z};
            }
            case "minecraft:nether_fossil" -> {
                return netherFossil(def, cp, random);
            }
            default -> {
                return new int[]{cx, ctx.heightAt(cx, cz, genericHeightmap(def)) - 1, cz};
            }
        }
    }

    private int minCorner(int x, int z, int w, int h) {
        int a = ctx.heightAt(x, z, Heightmap.Type.WORLD_SURFACE_WG) - 1;
        int b = ctx.heightAt(x, z + h, Heightmap.Type.WORLD_SURFACE_WG) - 1;
        int c = ctx.heightAt(x + w, z, Heightmap.Type.WORLD_SURFACE_WG) - 1;
        int d = ctx.heightAt(x + w, z + h, Heightmap.Type.WORLD_SURFACE_WG) - 1;
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private int[] netherFossil(StructureDef def, ChunkPos cp, ChunkRandom random) {
        int x = cp.getStartX() + random.nextInt(16);
        int z = cp.getStartZ() + random.nextInt(16);
        int sea = ctx.seaLevel;
        HeightProvider hp = heightProvider(def, "height");
        if (hp == null) return new int[]{x, sea + 10, z};
        int l = hp.get(random, new HeightContext(ctx.generator, ctx.heightView));
        VerticalBlockSample column = ctx.generator.getColumnSample(x, z, ctx.heightView, ctx.noise);
        BlockPos.Mutable m = new BlockPos.Mutable(x, l, z);
        while (l > sea) {
            BlockState above = column.getState(l);
            BlockState below = column.getState(--l);
            if (above.isAir() && (below.isOf(Blocks.SOUL_SAND) || below.isSideSolidFullSquare(EmptyBlockView.INSTANCE, m.setY(l), Direction.UP))) {
                break;
            }
        }
        return l <= sea ? null : new int[]{x, l, z};
    }

    private HeightProvider heightProvider(StructureDef def, String field) {
        return startHeights.computeIfAbsent(def.id + "#" + field, key -> {
            JsonElement el = def.json.get(field);
            if (el == null) return null;
            return HeightProvider.CODEC.parse(JsonOps.INSTANCE, el).result().orElse(null);
        });
    }

    /**
     * Port of JigsawStructure.getStructurePosition and the start of StructurePoolBasedGenerator.generate:
     * the biome is read at the centre of the first piece, which depends on the template that gets picked.
     */
    private int[] jigsaw(StructureDef def, ChunkPos cp, ChunkRandom random) {
        HeightProvider hp = heightProvider(def, "start_height");
        int startY = hp != null ? hp.get(random, new HeightContext(ctx.generator, ctx.heightView)) : 0;
        Heightmap.Type proj = null;
        JsonElement pe = def.json.get("project_start_to_heightmap");
        if (pe != null && pe.isJsonPrimitive()) proj = parseHeightmap(pe.getAsString(), Heightmap.Type.WORLD_SURFACE_WG);
        BlockPos pos = new BlockPos(cp.getStartX(), startY, cp.getStartZ());
        int fx = cp.getCenterX();
        int fz = cp.getCenterZ();
        int[] fallback = {fx, proj == null ? startY : startY + ctx.heightAt(fx, fz, proj), fz};
        try {
            BlockRotation rot = BlockRotation.random(random);
            JsonElement sp = def.json.get("start_pool");
            Identifier poolId = sp == null ? null : Identifier.tryParse(sp.getAsString());
            List<JsonObject> elements = poolId == null ? null : data.poolElements(poolId);
            if (elements == null) return fallback;
            if (elements.isEmpty()) return null;
            JsonObject element = elements.get(random.nextInt(elements.size()));
            String type = element.has("element_type") ? element.get("element_type").getAsString() : "";
            if (type.endsWith("empty_pool_element")) return null;
            if (!type.endsWith("single_pool_element") || !element.has("location")) return fallback;
            StructureTemplate template = data.template(Identifier.tryParse(element.get("location").getAsString()));
            if (template == null) return fallback;
            StructurePlacementData placement = new StructurePlacementData().setRotation(rot);
            BlockPos jigsawPos = pos;
            JsonElement sj = def.json.get("start_jigsaw_name");
            if (sj != null && sj.isJsonPrimitive()) {
                Identifier want = Identifier.tryParse(sj.getAsString());
                List<StructureTemplate.StructureBlockInfo> infos = new ArrayList<>(template.getInfosForBlock(pos, placement, Blocks.JIGSAW, true));
                Util.shuffle(infos, random);
                infos.sort(Comparator.<StructureTemplate.StructureBlockInfo>comparingInt(i -> i.nbt() == null ? 0 : i.nbt().getInt("selection_priority")).reversed());
                BlockPos found = null;
                for (StructureTemplate.StructureBlockInfo info : infos) {
                    if (info.nbt() != null && Identifier.tryParse(info.nbt().getString("name")) != null
                            && Identifier.tryParse(info.nbt().getString("name")).equals(want)) {
                        found = info.pos();
                        break;
                    }
                }
                if (found == null) return null;
                jigsawPos = found;
            }
            Vec3i vec = jigsawPos.subtract(pos);
            BlockPos origin = pos.subtract(vec);
            BlockBox box = template.calculateBoundingBox(placement, origin);
            int i = (box.getMaxX() + box.getMinX()) / 2;
            int j = (box.getMaxZ() + box.getMinZ()) / 2;
            int y = proj == null ? startY : startY + ctx.heightAt(i, j, proj) + vec.getY();
            return new int[]{i, y, j};
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Heightmap.Type genericHeightmap(StructureDef def) {
        return switch (def.type) {
            case "minecraft:ocean_ruin", "minecraft:buried_treasure" -> Heightmap.Type.OCEAN_FLOOR_WG;
            case "minecraft:shipwreck" -> {
                JsonObject j = def.json;
                boolean beached = j.has("is_beached") && j.get("is_beached").getAsBoolean();
                yield beached ? Heightmap.Type.WORLD_SURFACE_WG : Heightmap.Type.OCEAN_FLOOR_WG;
            }
            default -> Heightmap.Type.WORLD_SURFACE_WG;
        };
    }

    private static Heightmap.Type parseHeightmap(String name, Heightmap.Type fallback) {
        try {
            return Heightmap.Type.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ biomes

    /** Same square spiral as BiomeSource.locateBiome(origin, radius, h, v, ...). */
    public BiomeHit locateBiome(Set<Identifier> targets, BlockPos origin, int radius, int hStep, int vStep) {
        boolean any = false;
        for (Identifier b : targets) if (ctx.biomeIds.contains(b)) any = true;
        if (!any) return null;
        int i = Math.floorDiv(radius, hStep);
        int[] ys = MathHelper.stream(origin.getY(), ctx.minY + 1, ctx.topY, vStep).toArray();
        long n = 0;
        for (BlockPos.Mutable m : BlockPos.iterateInSquare(BlockPos.ORIGIN, i, Direction.EAST, Direction.SOUTH)) {
            if ((++n & 1023) == 0 && cancelled.getAsBoolean()) return null;
            int x = origin.getX() + m.getX() * hStep;
            int z = origin.getZ() + m.getZ() * hStep;
            for (int y : ys) {
                Identifier id = ctx.biomeAt(x, y, z);
                if (id != null && targets.contains(id)) return new BiomeHit(new BlockPos(x, y, z), id);
            }
        }
        return null;
    }
}
