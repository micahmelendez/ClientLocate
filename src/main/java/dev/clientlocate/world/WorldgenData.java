package dev.clientlocate.world;

import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Loads the worldgen registries (noise settings, density functions, biomes, dimensions...) the same way
 * a server does, from the game data plus every mod and datapack. This is what makes terrain and biome
 * mods such as Terralith show up in the calculation.
 */
public final class WorldgenData {
    private static final Logger LOG = LoggerFactory.getLogger("clientlocate");

    public final RegistryWrapper.WrapperLookup lookup;
    /** true when the registries came from the data roots, false when the plain vanilla built-ins are used. */
    public final boolean custom;
    public final String status;

    private WorldgenData(RegistryWrapper.WrapperLookup lookup, boolean custom, String status) {
        this.lookup = lookup;
        this.custom = custom;
        this.status = status;
    }

    public static WorldgenData vanilla(String why) {
        return new WorldgenData(BuiltinRegistries.createWrapperLookup(), false, why);
    }

    /**
     * @param roots data roots in priority order, later ones override earlier ones
     */
    public static WorldgenData load(List<Path> roots) {
        List<Path> terrain = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            if (i == 0 || changesTerrain(roots.get(i))) terrain.add(roots.get(i));
        }
        // lean load of every source, then lean load of only the terrain related sources, then the full load
        Object[][] attempts = {
                {roots, true, "loaded terrain and biome data from " + roots.size() + " sources"},
                {terrain, true, "loaded terrain and biome data from " + terrain.size() + " terrain related sources (some other sources failed to load)"},
                {roots, false, "loaded all worldgen data from " + roots.size() + " sources"},
        };
        for (Object[] a : attempts) {
            @SuppressWarnings("unchecked")
            List<Path> use = (List<Path>) a[0];
            try {
                return new WorldgenData(loadFrom(use, (Boolean) a[1]), true, (String) a[2]);
            } catch (Throwable t) {
                LOG.warn("Worldgen load attempt failed ({})", a[2], t);
            }
        }
        return vanilla("custom worldgen data could not be loaded, using vanilla terrain and biomes");
    }

    private static boolean changesTerrain(Path root) {
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return false;
        try (Stream<Path> ns = Files.list(data)) {
            for (Path n : (Iterable<Path>) ns::iterator) {
                for (String sub : new String[]{"dimension", "worldgen/noise_settings", "worldgen/density_function",
                        "worldgen/noise", "worldgen/biome", "worldgen/multi_noise_biome_source_parameter_list"}) {
                    if (Files.isDirectory(n.resolve(sub))) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Only what terrain and biomes need. Structures, template pools and the rest are read by this mod
     * itself, so a structure file the client cannot parse (for example one that needs another mod's
     * patch) cannot stop the terrain from loading.
     */
    private static final Set<RegistryKey<?>> TERRAIN_REGISTRIES = Set.of(
            RegistryKeys.DIMENSION_TYPE, RegistryKeys.BIOME, RegistryKeys.NOISE_PARAMETERS, RegistryKeys.DENSITY_FUNCTION,
            RegistryKeys.CHUNK_GENERATOR_SETTINGS, RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
            RegistryKeys.PLACED_FEATURE, RegistryKeys.CONFIGURED_FEATURE, RegistryKeys.CONFIGURED_CARVER,
            RegistryKeys.PROCESSOR_LIST);

    private static List<RegistryLoader.Entry<?>> terrainOnly(List<RegistryLoader.Entry<?>> all) {
        List<RegistryLoader.Entry<?>> out = new ArrayList<>();
        for (RegistryLoader.Entry<?> e : all) {
            if (TERRAIN_REGISTRIES.contains(e.key())) out.add(e);
        }
        return out;
    }

    private static RegistryWrapper.WrapperLookup loadFrom(List<Path> roots, boolean lean) {
        List<ResourcePack> packs = new ArrayList<>();
        int i = 0;
        for (Path root : roots) {
            String name = "clientlocate/" + (i++);
            packs.add(new DirectoryResourcePack(new ResourcePackInfo(name, Text.literal(name), ResourcePackSource.NONE, Optional.empty()), root));
        }
        LifecycledResourceManagerImpl manager = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA, packs);
        CombinedDynamicRegistries<ServerDynamicRegistryType> combined = ServerDynamicRegistryType.createCombinedDynamicRegistries();
        DynamicRegistryManager.Immutable worldgen = RegistryLoader.loadFromResource(manager,
                combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN), lean ? terrainOnly(RegistryLoader.DYNAMIC_REGISTRIES) : RegistryLoader.DYNAMIC_REGISTRIES);
        combined = combined.with(ServerDynamicRegistryType.WORLDGEN, worldgen);
        DynamicRegistryManager.Immutable dimensions = RegistryLoader.loadFromResource(manager,
                combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.DIMENSIONS), RegistryLoader.DIMENSION_REGISTRIES);
        combined = combined.with(ServerDynamicRegistryType.DIMENSIONS, dimensions);
        return combined.getCombinedRegistryManager();
    }
}
