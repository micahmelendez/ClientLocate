package dev.clientlocate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.clientlocate.data.StructureData;
import dev.clientlocate.locate.Locator;
import dev.clientlocate.mixin.BiomeAccessAccessor;
import dev.clientlocate.world.DimensionContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class ClientLocate implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("clientlocate");

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clientlocate-worker");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger JOB = new AtomicInteger();

    private static ClientLocateConfig config;
    private static volatile StructureData data;
    private static volatile CompletableFuture<StructureData> loading;
    private static DimensionContext cachedContext; // worker thread only

    @Override
    public void onInitializeClient() {
        config = ClientLocateConfig.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
    }

    // ------------------------------------------------------------------ command tree

    private void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("clocate")
                .then(literal("structure")
                        .then(argument("target", new TargetArgument())
                                .suggests((c, b) -> suggestStructures(b))
                                .executes(c -> locateStructure(c, config.structureRadius))
                                .then(argument("radius", IntegerArgumentType.integer(1, 1000))
                                        .executes(c -> locateStructure(c, IntegerArgumentType.getInteger(c, "radius"))))))
                .then(literal("biome")
                        .then(argument("target", new TargetArgument())
                                .suggests((c, b) -> suggestBiomes(b))
                                .executes(ClientLocate::locateBiome)))
                .then(literal("seed")
                        .executes(ClientLocate::showSeed)
                        .then(argument("value", StringArgumentType.greedyString())
                                .executes(ClientLocate::setSeed)))
                .then(literal("list")
                        .executes(c -> list(c, ""))
                        .then(argument("filter", StringArgumentType.greedyString())
                                .executes(c -> list(c, StringArgumentType.getString(c, "filter")))))
                .then(literal("sources").executes(ClientLocate::sources))
                .then(literal("reload").executes(ClientLocate::reload))
                .then(literal("cancel").executes(c -> {
                    JOB.incrementAndGet();
                    c.getSource().sendFeedback(gray("Search cancelled."));
                    return 1;
                })));
    }

    /** Reads a resource location or a #tag, which Brigadier's own identifier type would reject. */
    private static final class TargetArgument implements ArgumentType<String> {
        @Override
        public String parse(StringReader reader) {
            int start = reader.getCursor();
            while (reader.canRead() && reader.peek() != ' ') reader.skip();
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public Collection<String> getExamples() {
            return List.of("minecraft:village_plains", "#minecraft:village");
        }
    }

    // ------------------------------------------------------------------ suggestions

    private static CompletableFuture<Suggestions> suggestStructures(SuggestionsBuilder b) {
        StructureData d = data;
        if (d == null) {
            ensureLoaded();
            return b.buildFuture();
        }
        List<String> names = new ArrayList<>();
        for (Identifier id : d.locatableStructures()) names.add(id.toString());
        for (Identifier id : d.structureTagIds()) names.add("#" + id);
        return CommandSource.suggestMatching(names, b);
    }

    private static CompletableFuture<Suggestions> suggestBiomes(SuggestionsBuilder b) {
        StructureData d = data;
        if (d == null) {
            ensureLoaded();
            return b.buildFuture();
        }
        List<String> names = new ArrayList<>();
        for (Identifier id : d.biomeIds) names.add(id.toString());
        for (Identifier id : d.biomeTagIds()) names.add("#" + id);
        return CommandSource.suggestMatching(names, b);
    }

    // ------------------------------------------------------------------ data loading

    private static synchronized CompletableFuture<StructureData> ensureLoaded() {
        if (data != null) return CompletableFuture.completedFuture(data);
        if (loading == null) {
            loading = CompletableFuture.supplyAsync(() -> {
                long t = System.currentTimeMillis();
                StructureData d = StructureData.load(config.dataFolders());
                LOG.info("Loaded {} structure sets and {} structures in {} ms", d.sets.size(), d.structures.size(), System.currentTimeMillis() - t);
                data = d;
                return d;
            }, WORKER);
        }
        return loading;
    }

    private static synchronized void invalidate() {
        StructureData old = data;
        if (old != null) {
            JOB.incrementAndGet(); // cancel a running search before its files go away
            WORKER.execute(old::close); // single worker thread: runs after the cancelled search has stopped
        }
        data = null;
        loading = null;
        cachedContext = null;
    }

    // ------------------------------------------------------------------ seed handling

    private static String serverKey(MinecraftClient mc) {
        ServerInfo info = mc.getCurrentServerEntry();
        if (info != null && info.address != null) return info.address.toLowerCase(Locale.ROOT);
        return null;
    }

    private static Long currentSeed(MinecraftClient mc) {
        IntegratedServer srv = mc.getServer();
        if (srv != null) return srv.getOverworld().getSeed();
        String key = serverKey(mc);
        return key == null ? null : config.seeds.get(key);
    }

    /** true if the seed hashes to the value the server sent us, false if not, null if we cannot tell. */
    private static Boolean seedMatchesWorld(MinecraftClient mc, long seed) {
        ClientWorld w = mc.world;
        if (w == null) return null;
        try {
            long hashed = ((BiomeAccessAccessor) (Object) w.getBiomeAccess()).clientlocate$getSeed();
            return hashed == BiomeAccess.hashSeed(seed);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long parseSeed(String s) {
        s = s.trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode();
        }
    }

    private static int showSeed(CommandContext<FabricClientCommandSource> c) {
        MinecraftClient mc = c.getSource().getClient();
        Long seed = currentSeed(mc);
        if (seed == null) {
            c.getSource().sendError(Text.literal("No seed saved for this server. Run /clocate seed <seed> (the server owner can find it with /seed or in server.properties)."));
            return 0;
        }
        String how = mc.getServer() != null ? "singleplayer world" : "saved for " + serverKey(mc);
        c.getSource().sendFeedback(Text.literal("Seed " + seed + " (" + how + ")").formatted(Formatting.GREEN));
        return 1;
    }

    private static int setSeed(CommandContext<FabricClientCommandSource> c) {
        MinecraftClient mc = c.getSource().getClient();
        long seed = parseSeed(StringArgumentType.getString(c, "value"));
        if (mc.getServer() != null) {
            c.getSource().sendFeedback(gray("This is a singleplayer world, so its real seed is used automatically."));
            return 1;
        }
        String key = serverKey(mc);
        if (key == null) {
            c.getSource().sendError(Text.literal("Join a multiplayer server first so the seed can be saved for it."));
            return 0;
        }
        Boolean ok = seedMatchesWorld(mc, seed);
        if (Boolean.FALSE.equals(ok)) {
            c.getSource().sendError(Text.literal("That seed does not match this server (its hashed seed differs from the one the server sent). Not saved."));
            return 0;
        }
        config.seeds.put(key, seed);
        config.save();
        invalidateContext();
        MutableText msg = Text.literal("Seed " + seed + " saved for " + key);
        msg.append(Text.literal(ok == null ? " (could not verify it against the server)." : " and verified against the server.").formatted(ok == null ? Formatting.YELLOW : Formatting.GREEN));
        c.getSource().sendFeedback(msg.formatted(Formatting.GREEN));
        return 1;
    }

    /** Called by the built-in seed cracker when it has found the world seed of the current server. */
    static void onSeedCracked(long seed) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.world == null || mc.getServer() != null) return; // singleplayer already knows its seed
            String key = serverKey(mc);
            if (key == null) return;
            Boolean ok = seedMatchesWorld(mc, seed);
            if (Boolean.FALSE.equals(ok)) return;
            config.seeds.put(key, seed);
            config.save();
            invalidateContext();
            MutableText msg = Text.literal("Seed " + seed + " found by the seed cracker and saved for " + key);
            msg.append(Text.literal(ok == null ? " (could not verify it against the server)." : " and verified against the server. /clocate is ready.")
                    .formatted(ok == null ? Formatting.YELLOW : Formatting.GREEN));
            if (mc.player != null) mc.player.sendMessage(msg.formatted(Formatting.GREEN), false);
        });
    }

    private static void invalidateContext() {
        WORKER.execute(() -> cachedContext = null);
    }

    // ------------------------------------------------------------------ locate

    private static int locateStructure(CommandContext<FabricClientCommandSource> c, int radius) {
        return startSearch(c, false, radius);
    }

    private static int locateBiome(CommandContext<FabricClientCommandSource> c) {
        return startSearch(c, true, config.biomeRadius);
    }

    private static int startSearch(CommandContext<FabricClientCommandSource> c, boolean biome, int radius) {
        FabricClientCommandSource src = c.getSource();
        MinecraftClient mc = src.getClient();
        if (mc.world == null || mc.player == null) return 0;
        Long seed = currentSeed(mc);
        if (seed == null) {
            src.sendError(Text.literal("No seed saved for this server. Run /clocate seed <seed> first."));
            return 0;
        }
        Boolean ok = seedMatchesWorld(mc, seed);
        if (Boolean.FALSE.equals(ok)) {
            src.sendError(Text.literal("The saved seed does not match this world. Run /clocate seed <correct seed>."));
            return 0;
        }
        RegistryKey<World> dim = mc.world.getRegistryKey();
        if (!DimensionContext.supports(dim)) {
            src.sendError(Text.literal("This dimension has custom world generation, which this mod cannot compute. Overworld, Nether and End are supported."));
            return 0;
        }
        String target = StringArgumentType.getString(c, "target");
        BlockPos origin = mc.player.getBlockPos();
        int job = JOB.incrementAndGet();
        src.sendFeedback(gray("Searching for " + target + " (runs in the background, /clocate cancel to stop)..."));
        long seedFinal = seed;
        ensureLoaded().thenAcceptAsync(d -> {
            try {
                DimensionContext ctx = context(dim, seedFinal, d);
                Locator locator = new Locator(d, ctx, () -> JOB.get() != job);
                if (biome) {
                    Set<Identifier> targets = d.resolveBiomeTarget(target);
                    Locator.BiomeHit hit = locator.locateBiome(targets, origin, radius, 32, 64);
                    if (JOB.get() != job) return;
                    mc.execute(() -> reportBiome(src, target, origin, hit));
                } else {
                    Set<Identifier> targets = d.resolveStructureTarget(target);
                    boolean known = false;
                    for (Identifier t : targets) if (d.locatableStructures().contains(t)) known = true;
                    if (!known) {
                        mc.execute(() -> src.sendError(Text.literal("No structure set contains \"" + target + "\". Try /clocate list " + target.replace("#", ""))));
                        return;
                    }
                    Locator.StructureHit hit = locator.locateStructure(targets, origin, radius);
                    if (JOB.get() != job) return;
                    mc.execute(() -> reportStructure(src, target, origin, hit, radius));
                }
            } catch (Throwable t) {
                LOG.error("Locate failed", t);
                mc.execute(() -> src.sendError(Text.literal("Locate failed: " + t)));
            }
        }, WORKER);
        return 1;
    }

    private static DimensionContext context(RegistryKey<World> dim, long seed, StructureData d) {
        DimensionContext c = cachedContext;
        if (c == null || c.dimension != dim || c.seed != seed) {
            c = d.worldgen != null ? DimensionContext.create(dim, seed, d.worldgen.lookup) : DimensionContext.create(dim, seed);
            cachedContext = c;
        }
        return c;
    }

    private static void reportStructure(FabricClientCommandSource src, String target, BlockPos origin, Locator.StructureHit hit, int radius) {
        if (hit == null) {
            src.sendError(Text.literal("Could not find \"" + target + "\" within reasonable distance (searched " + radius + " regions). It may not generate in this dimension."));
            return;
        }
        src.sendFeedback(Text.translatable("commands.locate.structure.success", hit.structure().toString(), coords(hit.pos().getX(), "~", hit.pos().getZ()),
                distance(origin, hit.pos())));
    }

    private static void reportBiome(FabricClientCommandSource src, String target, BlockPos origin, Locator.BiomeHit hit) {
        if (hit == null) {
            src.sendError(Text.literal("Could not find a biome matching \"" + target + "\" within reasonable distance."));
            return;
        }
        src.sendFeedback(Text.translatable("commands.locate.biome.success", hit.biome().toString(), coords(hit.pos().getX(), "~", hit.pos().getZ()),
                distance(origin, hit.pos())));
    }

    private static int distance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return MathHelper.floor(MathHelper.sqrt((float) (dx * dx + dz * dz)));
    }

    private static Text coords(int x, String y, int z) {
        return Texts.bracketed(Text.translatable("chat.coordinates", x, y, z)).styled(s -> s
                .withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + x + " " + y + " " + z))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("chat.coordinates.tooltip"))));
    }

    // ------------------------------------------------------------------ info commands

    private static int list(CommandContext<FabricClientCommandSource> c, String filter) {
        FabricClientCommandSource src = c.getSource();
        MinecraftClient mc = src.getClient();
        ensureLoaded().thenAccept(d -> mc.execute(() -> {
            String f = filter.trim().toLowerCase(Locale.ROOT);
            Set<Identifier> all = d.locatableStructures();
            if (f.isEmpty()) {
                Map<String, Integer> perNs = new TreeMap<>();
                for (Identifier id : all) perNs.merge(id.getNamespace(), 1, Integer::sum);
                src.sendFeedback(Text.literal(all.size() + " locatable structures:").formatted(Formatting.GREEN));
                perNs.forEach((ns, n) -> src.sendFeedback(gray("  " + ns + ": " + n + "   (use /clocate list " + ns + ")")));
                return;
            }
            List<String> hits = new ArrayList<>();
            for (Identifier id : all) if (id.toString().contains(f)) hits.add(id.toString());
            src.sendFeedback(Text.literal(hits.size() + " match \"" + f + "\":").formatted(Formatting.GREEN));
            int shown = 0;
            for (String h : hits) {
                if (shown++ >= 60) {
                    src.sendFeedback(gray("  ...and " + (hits.size() - 60) + " more, narrow the filter"));
                    break;
                }
                src.sendFeedback(Text.literal("  " + h).styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/clocate structure " + h))));
            }
        }));
        return 1;
    }

    private static int sources(CommandContext<FabricClientCommandSource> c) {
        FabricClientCommandSource src = c.getSource();
        MinecraftClient mc = src.getClient();
        ensureLoaded().thenAccept(d -> mc.execute(() -> {
            src.sendFeedback(Text.literal("Loaded " + d.sets.size() + " structure sets and " + d.structures.size() + " structures.").formatted(Formatting.GREEN));
            if (d.sourceLog.isEmpty()) src.sendFeedback(gray("No mods or datapacks added worldgen data, only vanilla is loaded."));
            for (String s : d.sourceLog) src.sendFeedback(gray("  " + s));
            for (int i = 0; i < Math.min(5, d.warnings.size()); i++) src.sendFeedback(Text.literal("  warning: " + d.warnings.get(i)).formatted(Formatting.YELLOW));
            src.sendFeedback(gray("Datapack folders scanned: " + config.dataFolders().size() + ". Drop your server's datapacks into " + ClientLocateConfig.dropFolder()));
        }));
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> c) {
        invalidate();
        c.getSource().sendFeedback(gray("Reloading structure data..."));
        FabricClientCommandSource src = c.getSource();
        MinecraftClient mc = src.getClient();
        ensureLoaded().thenAccept(d -> mc.execute(() -> src.sendFeedback(Text.literal("Reloaded: " + d.sets.size() + " structure sets, " + d.structures.size() + " structures.").formatted(Formatting.GREEN))));
        return 1;
    }

    private static MutableText gray(String s) {
        return Text.literal(s).formatted(Formatting.GRAY);
    }
}
