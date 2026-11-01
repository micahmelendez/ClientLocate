package dev.clientlocate.world;

import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla terrain noise and biome source for one dimension of one seed. Everything here is
 * the real game code, so biome and height lookups match what the server generates.
 */
public final class DimensionContext {
    private static RegistryWrapper.WrapperLookup vanillaLookup;

    private static synchronized RegistryWrapper.WrapperLookup vanillaLookup() {
        if (vanillaLookup == null) vanillaLookup = BuiltinRegistries.createWrapperLookup();
        return vanillaLookup;
    }

    public final RegistryKey<World> dimension;
    public final long seed;
    public final BiomeSource biomeSource;
    public final NoiseConfig noise;
    public final NoiseChunkGenerator generator;
    public final HeightLimitView heightView;
    public final int minY;
    public final int topY;
    public final int seaLevel;
    public final Set<Identifier> biomeIds = new HashSet<>();

    private final MultiNoiseUtil.MultiNoiseSampler sampler;
    private final Map<Long, Integer> surfaceCache = new HashMap<>();

    private DimensionContext(RegistryKey<World> dimension, long seed, BiomeSource source, RegistryEntry<ChunkGeneratorSettings> settings, NoiseConfig noise) {
        this.dimension = dimension;
        this.seed = seed;
        this.biomeSource = source;
        this.noise = noise;
        this.generator = new NoiseChunkGenerator(source, settings);
        this.minY = generator.getMinimumY();
        this.topY = minY + generator.getWorldHeight();
        this.seaLevel = generator.getSeaLevel();
        this.heightView = HeightLimitView.create(minY, generator.getWorldHeight());
        this.sampler = noise.getMultiNoiseSampler();
        for (RegistryEntry<Biome> b : source.getBiomes()) {
            b.getKey().ifPresent(k -> biomeIds.add(k.getValue()));
        }
    }

    public static boolean supports(RegistryKey<World> dimension) {
        return dimension == World.OVERWORLD || dimension == World.NETHER || dimension == World.END;
    }

    public static DimensionContext create(RegistryKey<World> dimension, long seed) {
        return create(dimension, seed, vanillaLookup());
    }

    public static DimensionContext create(RegistryKey<World> dimension, long seed, RegistryWrapper.WrapperLookup lk) {
        if (dimension != World.NETHER && dimension != World.END) dimension = World.OVERWORLD;
        // a datapack or mod can replace a whole dimension (Terralith does this for the overworld)
        RegistryKey<DimensionOptions> optionsKey = dimension == World.NETHER ? DimensionOptions.NETHER
                : dimension == World.END ? DimensionOptions.END : DimensionOptions.OVERWORLD;
        var optionsRegistry = lk.getOptionalWrapper(RegistryKeys.DIMENSION);
        if (optionsRegistry.isPresent()) {
            var entry = optionsRegistry.get().getOptional(optionsKey);
            if (entry.isPresent() && entry.get().value().chunkGenerator() instanceof NoiseChunkGenerator ncg) {
                RegistryEntry<ChunkGeneratorSettings> settings = ncg.getSettings();
                NoiseConfig noise = NoiseConfig.create(settings.value(), lk.getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS), seed);
                return new DimensionContext(dimension, seed, ncg.getBiomeSource(), settings, noise);
            }
        }
        RegistryKey<ChunkGeneratorSettings> settingsKey;
        BiomeSource source;
        if (dimension == World.NETHER) {
            settingsKey = ChunkGeneratorSettings.NETHER;
            source = MultiNoiseBiomeSource.create(lk.getWrapperOrThrow(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        } else if (dimension == World.END) {
            settingsKey = ChunkGeneratorSettings.END;
            source = TheEndBiomeSource.createVanilla(lk.getWrapperOrThrow(RegistryKeys.BIOME));
        } else {
            dimension = World.OVERWORLD;
            settingsKey = ChunkGeneratorSettings.OVERWORLD;
            source = MultiNoiseBiomeSource.create(lk.getWrapperOrThrow(RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        }
        RegistryEntry<ChunkGeneratorSettings> settings = lk.getWrapperOrThrow(RegistryKeys.CHUNK_GENERATOR_SETTINGS).getOrThrow(settingsKey);
        NoiseConfig noise = NoiseConfig.create(settings.value(), lk.getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS), seed);
        return new DimensionContext(dimension, seed, source, settings, noise);
    }

    public MultiNoiseUtil.MultiNoiseSampler sampler() {
        return sampler;
    }

    /** Biome id at a block position, or null if the biome has no key. */
    public Identifier biomeAt(int x, int y, int z) {
        RegistryEntry<Biome> e = biomeSource.getBiome(x >> 2, y >> 2, z >> 2, sampler);
        return e.getKey().map(RegistryKey::getValue).orElse(null);
    }

    public RegistryEntry<Biome> biomeEntryAt(int x, int y, int z) {
        return biomeSource.getBiome(x >> 2, y >> 2, z >> 2, sampler);
    }

    /** First free Y above the terrain for a heightmap type, same value the world generator uses. */
    public int heightAt(int x, int z, Heightmap.Type type) {
        long key = (((long) x << 32) ^ (z & 0xFFFFFFFFL)) * 8 + type.ordinal();
        Integer cached = surfaceCache.get(key);
        if (cached != null) return cached;
        int y = generator.getHeight(x, z, type, heightView, noise);
        if (surfaceCache.size() > 200_000) surfaceCache.clear();
        surfaceCache.put(key, y);
        return y;
    }

    public int surfaceY(int x, int z) {
        return heightAt(x, z, Heightmap.Type.WORLD_SURFACE_WG);
    }
}
