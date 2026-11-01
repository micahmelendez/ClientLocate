# ClientLocate

A client-side `/clocate` for Minecraft 1.21.1 (Fabric, Java 21) that works without operator rights, plus a
built-in seed cracker. Find the world seed of a server, then locate structures and biomes from it. Structures
added by mods and datapacks are included.

- `/clocate` works like vanilla `/locate`, but it runs on your computer from the world seed, so the server is not asked anything.
- The seed cracker is [SeedCrackerX](https://github.com/19MisterX98/SeedcrackerX) (MIT), merged in. When it finds the
  seed, ClientLocate checks it against the server and saves it, so `/clocate` is ready without typing anything.
- Structure, terrain and biome data is read from the game, every installed mod and your datapacks, the way a server loads it.

Many servers forbid seed-based locating. Check the rules before using it on a server that is not yours.

## Install

1. Install Fabric Loader and Fabric API for 1.21.1, with Java 21.
2. Put `clientlocate-<version>.jar` in your `mods` folder.
3. Remove a separate SeedCrackerX jar if you have one. This mod includes it, and the game refuses to start with both.

## Use

```
/clocate seed [seed]                  show or set the seed for this server (checked against the server)
/clocate structure <id or #tag> [r]   like /locate structure, r = search radius in regions (default 100)
/clocate biome <id or #tag>           like /locate biome
/clocate list [text]                  list locatable structures, filter by text
/clocate sources                      show which mods and datapacks were read
/clocate reload                       rescan after changing datapacks
/clocate cancel                       stop a running search
```

Tab completion lists every structure that the installed mods and datapacks define. In singleplayer the real seed is
used automatically.

### Finding a seed you do not know

Join the server and explore. The seed cracker watches the structures and terrain you see (igloos, desert pyramids,
jungle temples, swamp huts, shipwrecks, pillager outposts, monuments and more) and works out the seed, usually
within a few minutes of exploring. When it succeeds, you get a chat message and the seed is saved for that server.
See [docs/SeedCrackerX-README.md](docs/SeedCrackerX-README.md) for how the cracker works and its own commands.

If you already know the seed (the owner can use `/seed` or read `server.properties`), run `/clocate seed <seed>`.
The mod compares it with the hashed seed the server sends on join and refuses a wrong one.

## Where worldgen data comes from

At first use the mod reads, in this order (later ones override earlier ones, like datapacks):

1. the game jar (vanilla),
2. every installed mod (`data/<namespace>/worldgen/...`),
3. datapack folders: `<instance>/datapacks`, `config/openloader/data`, `config/paxi/datapacks`,
   `config/clientlocate/datapacks`, plus anything in `extraDataPaths` in `config/clientlocate.json`.

Datapacks that only exist inside a world folder (`saves/<world>/datapacks`, or `world/datapacks` on a server) are not
seen. If the server uses one that changes terrain, biomes or structures, copy it into `config/clientlocate/datapacks`
and run `/clocate reload`. Use the same version as the server. `/clocate sources` shows what was loaded.

Example: the Cobbleverse modpack ships its gyms and Team Rocket tower in `COBBLEVERSE-DP-*.zip`. Copy that file
into `config/clientlocate/datapacks`, and if the server also runs Terralith, keep Terralith enabled.

## How accurate is it

Placement (spacing, separation, salt, frequency, exclusion zones, stronghold rings) uses the game's own code.
Terrain and biomes use the game's own noise generators, fed with the worldgen files of every installed mod and
datapack, so terrain and biome mods that ship as datapacks (Terralith, for example) are part of the calculation.
For structures built from jigsaw pools (villages, most modded structures) the real first piece is rebuilt from the
template files, so the biome is checked where the game checks it.

Checked against real servers running vanilla 1.21.1, with `/locate` output recorded at the origin:

- Vanilla, 4 seeds, Overworld, Nether and End: 151 of 156 exact.
- Terralith 2.5.8, 2 seeds, Overworld (vanilla and Terralith structures and biomes): 146 of 148 exact.
- Terralith plus the Cobbleverse datapack, 3 seeds, Overworld: 88 of 88 exact.

The misses were all vanilla ruined portals and the mesa mineshaft.

Limits:

- Terrain and biomes come from data files, not from mod code. A mod that changes worldgen with Java code (a custom
  biome source or density function type) is not simulated, so results near biome borders can be wrong.
- Your client has to load the same worldgen data as the server, same versions included.
- Only the Overworld, Nether and End are supported.
- Custom structure types written in code (not `minecraft:jigsaw`) are checked at the chunk centre on the surface.
  Treat a result as "go look here".
- Searches far away can be slow with heavy terrain mods (minutes for structures tens of thousands of blocks away).
  `/clocate cancel` stops one.

## Build

```
./gradlew build      # jar in build/libs
./gradlew test       # compares against recorded vanilla /locate results
```

The Terralith comparison needs the Terralith jar and a recording, see `TerralithParityTest`.

## Credits and license

MIT. The seed cracker is SeedCrackerX by KaptainWutax and 19MisterX98, taken at tag 2.15.0 with its history kept
(`git log` shows it). Their copyright notice is in `LICENSE.md`, with ours below it.
