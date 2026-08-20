# Terrain Diffusion Mod [[Modrinth]](https://modrinth.com/mod/terrain-diffusion)

#### UPDATE: The research behind this mod has been accepted to SIGGRAPH 2026, the world's premier graphics conference! That means the research was officially peer reviewed and recognized as a significant contribution to the field. Enjoy the mod!

This is a Minecraft multiplatform mod integrating [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion).

## What this mod does

It replaces Minecraft's overworld generator with a diffusion model trained on real-world terrain. Instead of composing noise functions, the model generates an elevation map plus four climate variables — temperature, temperature seasonality, precipitation, and precipitation variability — and the world is built from those.

That single change pulls several systems with it:

- **Real landforms.** Mountain ranges, river valleys, coastal shelves and plateaus come out with the large-scale structure real terrain has, because the model learned it from real terrain rather than from stacked octaves of noise.
- **Climate-driven biomes.** Biomes are not painted on; they are derived from the elevation and climate the model produced, through a data-driven rule catalog (`biome_catalog.json`). Deserts sit in rain shadows, taiga follows latitude and altitude, and biome borders land where the climate actually changes.
- **Hydrology.** Rivers are traced by a fluvial network over the generated elevation, so they run downhill, gather tributaries, and reach the sea.
- **Tall worlds.** The terrain uses build heights well beyond vanilla's, scaled by the `World Scale` setting — up to 2032 blocks.
- **Surface features.** Procedural boulders, hoodoos, arches, sea stacks and similar structures are placed against the real slope and material of the terrain under them.
- **Caves that reach the terrain.** The 1.20.1 and 1.21.1 builds ship YUNG's Better Caves; on every build, each carver's altitudes and spawn rate are moved into the taller world so caves fill the mountains instead of a band near bedrock. See [Caves](#caves).
- **A terrain explorer.** `/td-explore` opens a browser map of the generated world for scouting and debugging.

Generation runs a neural network, so it needs a GPU to be comfortable — see [Requirements](#requirements).

## Which version should I use?

Three runtime builds are available on the [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) page.

**The CPU build is slow unless you are on macOS.**

| Build                     | Supports                    | Setup required                          |
|---------------------------| --------------------------- | --------------------------------------- |
| **Windows** (recommended) | Windows with any modern GPU | None                                    |
| **CUDA**                  | NVIDIA GPUs                 | [CUDA + cuDNN install](CUDA_INSTALL.md) |
| **CPU**                   | Everything else             | None                                    |

> **Mac users:** the CPU build automatically uses CoreML for hardware acceleration on Apple Silicon. No extra setup is needed.

Use the `-cuda` build only if you are on Linux, or have an NVIDIA GPU and prefer CUDA.

## Supported Minecraft versions and loaders

| Minecraft   | Java version | Fabric | Forge | NeoForge |
|-------------| ------------ | ------ | ----- | -------- |
| **1.20.1**  | Java 17      | yes    | yes   | no       |
| **1.21.1**  | Java 21      | yes    | no    | yes      |
| **1.21.11** | Java 21      | yes    | no    | yes      |

## Requirements

- Windows with a GPU or Linux with an NVIDIA GPU is strongly recommended. CPU inference works but is very slow.
- VRAM (GPU RAM) needed: roughly 1.5 GB.
- RAM needed: roughly 2.5 GB. You may need to increase Minecraft's RAM allocation.

One of the following:

- Minecraft with [Fabric](https://fabricmc.net/) and the [Fabric API Mod](https://modrinth.com/mod/fabric-api) installed.
- Minecraft with [Forge](https://files.minecraftforge.net/) installed, for Minecraft 1.20.1 only.
- Minecraft with [NeoForge](https://neoforged.net/) installed, for Minecraft 1.21.x and later versions only.

## Usage

**If using the CUDA build:** first see [CUDA_INSTALL.md](CUDA_INSTALL.md).

1. Download the mod jar from [Releases](https://github.com/xandergos/terrain-diffusion-mc/releases) for your Minecraft version and loader, then place it in your Minecraft `mods/` folder. Make sure the Minecraft version matches.
2. Launch Minecraft at least once online to download the Terrain Diffusion models. The first model download is large, around 2.5 GB total.
3. Create a world and select the **Terrain Diffusion** world type. Click **Customize** to set the `World Scale`.
4. The mod searches for a land spawn point near the world origin automatically. If the area around `(0, 0)` is entirely ocean, it may take a moment to find land.
5. Use `/td-explore` to scout the generated world from a browser.

## Exploring the world

The mod includes a built-in terrain explorer web UI. Run the `/td-explore` command in-game; it prints a clickable link, for example `http://localhost:19801`, which opens an interactive map in your browser.

Click the map on the left to open a detailed view. Click the detailed view to get coordinates in the bottom left. You can also filter for certain climates.

## Mod compatibility

Other mods are detected at runtime and are always optional — nothing is bundled, and nothing needs to be installed. Install the mod you want and the biome catalog adapts on its own.

### Biome mods

The shipped catalog carries every supported integration in one file, and each entry declares which mod it needs. Entries whose mod is absent are dropped at load, so the same jar serves every setup:

| Installed | Biomes in play |
|---|---|
| Nothing extra | 65 vanilla |
| [Biomes O' Plenty](https://modrinth.com/mod/biomes-o-plenty) | 124 |

To add your own, drop a `biome_catalog.json` into `config/terrain-diffusion-mc/` — it replaces the bundled one. Entries may set `"requiredMods": ["some_mod"]` to gate themselves the same way.

### Total-conversion mods

Mods that retheme the whole game usually redefine the vanilla biomes in place. That works here with no configuration: their trees, colours, mobs and structures arrive through the biome registry, so the terrain is this mod's and the look is theirs.

One thing is handled automatically:

- **Overworld reclaim.** Such mods often ship their own `minecraft:dimension/overworld`, which normally outranks any world type the player picked — the overworld would silently revert to their generator. If a world was created with the Terrain Diffusion world type, its overworld is reasserted on load. Worlds created with any other world type are untouched.

Nothing from another mod is copied or redistributed; this reads only what is already loaded in the game.

Note that such a mod's own surface rule lives in the vanilla overworld noise settings, which this mod replaces, so the ground blocks stay this mod's own.

### Caves

Caves come from **carvers**: the per-biome list every cave mod, biome mod and datapack already
writes to. That list is run unchanged here, so installing a cave mod does what you would expect.
Two things are done to it, and nothing else.

**Altitudes are moved to where the terrain is.** A carver is authored for a world whose surface
sits around y=64..140, so it says things like "anywhere between the world floor and y=180". This
mod's dimension is up to 2032 blocks tall and `World Scale` stretches everything above sea level,
so an untouched carver would cut a band near the bottom of the world and leave every mountain
above it solid. Each carver's altitudes are therefore multiplied by the same factor the terrain
was, and nothing else about it — shape, size, radius, block choice — is changed. Nothing happens
at `World Scale` 1, where the two heights are the same. The underground below sea level is never
stretched at any scale, so deep carvers keep their authored depth exactly.

**Cave frequency is kept per slab of world.** A carver that starts one cave system per chunk with
some probability and drops it anywhere in its range would, over a range several times taller,
produce the same number of caves spread much thinner — most visibly as far fewer cave mouths on a
mountainside. Its spawn chance is raised by the same factor its range grew by. Carvers that fill a
chunk from noise instead, such as YUNG's Better Caves, already scale with the band they are given
and are left alone.

Both can be turned off in `config/terrain-diffusion-mc.properties` (`caves.lift_carvers`,
`caves.density_compensation`).

#### YUNG's Better Caves

The 1.20.1 and 1.21.1 builds bundle [YUNG's Better Caves](https://modrinth.com/mod/yungs-better-caves)
jar-in-jar, so the overhauled caves, underground lakes and lava oceans are there out of the box —
nothing to install. Nothing is overridden: whatever a pack or the player configures for it is what
generates, and its cave and cavern bands are moved into our taller world at runtime rather than by
shipping a file over its config. There is no Better Caves release for 1.21.11, so those builds use
vanilla's carvers unless you install a cave mod yourself.

Because it is bundled it cannot be removed from the mods folder. To run a different cave overhaul,
set `caves.bundled_cave_mod=false`: its carvers are dropped from every biome and vanilla's caves
are put back — unless another cave mod's carver is already there, in which case that mod's caves
are the ones that generate.

> On Minecraft 1.20.1 with [C2ME](https://modrinth.com/mod/c2me-fabric), set
> `threadedWorldGen.enabled=false` in C2ME's config. Better Caves places its water and lava regions
> from state that threaded chunk generation races on, and the symptom is lava pockets inside water
> regions. 1.21.1 is unaffected.

#### Teaching this mod about your carver

A carver whose configuration is a mod's own class is passed through untouched, because there is no
general way to know which of its numbers are altitudes. A mod or pack can say so, and get the same
altitude lifting vanilla's carvers get, by dropping `config/terrain-diffusion-mc/carver_altitudes.json`:

```json
{
  "altitudeKeys": {
    "somemod:crystal_cavern": ["min_height", "max_height"]
  },
  "excluded": [
    "othermod:vertical_shaft"
  ]
}
```

`altitudeKeys` is keyed by carver **type** id — the `"type"` of a configured carver JSON — and
lists the keys of that type's config that hold an absolute y. Any such key, at any depth in the
configuration, is lifted; every other number in it is left exactly as authored, because the
configuration is rebuilt by the mod's own codec rather than by field-poking. An empty list disables
a built-in entry. The mod ships one entry, for Better Caves' `bottom_y`/`top_y`.

`excluded` lists **configured** carver ids that are never rewritten at all, even vanilla-shaped
ones — the escape hatch for a mod or pack that has already tuned its carver for a tall world.

A mod that replaces the carving step outright is never touched by any of this: it has taken over
cave generation, and this mod stays out of its way.

#### Noise caves

Some cave overhauls do not use carvers at all — they cut caves in the density function, by editing
`minecraft:overworld`'s noise settings. Those edits cannot reach this dimension, which has noise
settings of its own; nothing is lost, but nothing is gained either.

There is a place to put them. This mod's `final_density` ends in a named density function that is
constant zero:

```
final_density = (terrain + beardifier) + terrain-diffusion-mc:cave_density
```

Override `data/terrain-diffusion-mc/worldgen/density_function/cave_density.json` in a datapack and
whatever you put there is added to the terrain everywhere — negative where you want air. That is
one small file, not a copy of the whole noise settings, so it keeps working when this mod's terrain
changes. The default is `{"type": "minecraft:constant", "argument": 0.0}`, which changes nothing.

Remember that this dimension is up to 2032 blocks tall: a density function written for a 384-block
world will want its y scales adjusted by the same `World Scale` factor everything else moves by.

#### Cave biomes

Cave biomes are not a separate system in Minecraft — lush caves, dripstone caves and the deep dark
are ordinary rows in the same climate table as surface biomes, reached at depths this mod's own
biome rules never produce. Rather than guessing, the underground is asked of whatever overworld
biome source the pack loaded, so a mod's own cave biomes arrive with no configuration, in bands
that follow the terrain above them.

## Configuration

Edit `config/terrain-diffusion-mc.properties`, created automatically on first launch:

```properties
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto".
# "gpu" uses DirectML on the Windows build, or CUDA on the CUDA build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Whether inactive models are offloaded from VRAM between pipeline stages.
# "auto" keeps all three resident when the GPU has room (roughly 2.5 GB) and offloads
# if it does not; offloading reloads a model on every stage switch, which costs about
# 0.8 s per generated tile. "true" always offloads (peak 1.5-2 GB), "false" never does.
inference.offload_models=auto

# Overlap between neighbouring model windows: "full" (default) generates every decoder
# pixel about twice and blends the copies, "reduced" widens the decoder stride for
# faster generation with less margin for the blend to hide a window boundary.
# This changes generated terrain, so pick it before creating a world and keep it.
inference.window_overlap=full

# Validate SHA-256 for pre-existing files in .minecraft/terrain-diffusion-models.
# Set to false if you want to provide custom models/config files without hash checks.
validate_model=true

# Port for the local terrain explorer web UI (/td-explore).
explorer.port=19801

# Spawn search: coarse-pixel region sizes for finding a land spawn near (0, 0).
# Starts at initial_size x initial_size and expands by 8 each step up to max_size x max_size.
# Each coarse pixel covers a large area, so 16-128 is typically sufficient.
spawn_search.initial_size=16
spawn_search.max_size=128

# Procedural surface structures (boulders, hoodoos, arches, ...) placed on top of the terrain.
# Disable if you only want vanilla features.
surface_features.enabled=true

# Caves. See the "Caves" section above for what these do; all three change which caves
# are generated, so pick them before creating a world.
caves.lift_carvers=true
caves.density_compensation=true
# 1.20.1 and 1.21.1 only, where YUNG's Better Caves is bundled inside this jar.
caves.bundled_cave_mod=true
```

### Per-world settings

For Terrain Diffusion worlds, click **Customize** in world creation and set:

- `World Scale`, integer `1..6`.

This value is saved with the world save and affects:

- how many real-world meters each block represents (`scale=1` => `30m/block`, `scale=2` => `15m/block`, etc.);
- world max height for newly created worlds;
- performance balance between GPU inference and CPU chunk generation.

`2` is recommended for a good balance of scale and playability. Use `1` for smaller, more compressed worlds. Lower values put more stress on the GPU because Terrain Diffusion runs more often. Higher values put more stress on the CPU because the world height is larger.

## Building from source

An internet connection is required during the build to fetch Minecraft, loader dependencies, and the pinned model manifest metadata from Hugging Face.

The Windows/DirectML build requires `libs/onnxruntime-dml.jar`, which is provided as part of this repository. See [Building onnxruntime with DirectML](#building-onnxruntime-with-directml) to build it from source.

### Workspace layout

This repository is a multi-version Gradle workspace.

```text
common/                  Shared Java code that is stable across Minecraft versions
loaders/fabric/          Shared Fabric loader code and metadata
loaders/neoforge/        Shared NeoForge loader code and metadata for 1.21.x
libs/                    Shared local runtime jars, including onnxruntime-dml.jar
gradle/conventions/      Shared Gradle convention scripts
terrain-diffusion-models/ Shared model assets created automatically by development runs
versions/1.20.1/         Minecraft 1.20.1: common + fabric + forge
versions/1.21.1/         Minecraft 1.21.1: common + fabric + neoforge
versions/1.21.11/        Minecraft 1.21.11: common + fabric + neoforge
```

Minecraft API-sensitive files live under `versions/<minecraft-version>/common`. This is intentional. Mixins, worldgen JSON, dimension types, biome source registration, and client world-creation screens are not stable enough to stay in the root `common` folder.

All Gradle development runs share `terrain-diffusion-models/` at the repository root, so switching Minecraft versions or loaders does not download another copy of the model assets. Distribution jars do not receive the development override and continue to use `<gameDir>/terrain-diffusion-models` in production.

### Java versions

Use the Java version matching the target Minecraft version.

| Minecraft   | Required Java |
|-------------| ------------- |
| **1.20.1**  | Java 17       |
| **1.21.1**  | Java 21       |
| **1.21.11** | Java 21       |

The per-version `gradle.properties` files set `java_version`, but your installed JDK/toolchain still needs to be available to Gradle.

#### Minecraft 1.20.1

```powershell
cd .\versions\1.20.1
..\..\gradlew.bat :fabric:runClient
..\..\gradlew.bat :forge:runClient
```

Clean run commands:

```powershell
cd .\versions\1.20.1
..\..\gradlew.bat :common:clean :fabric:clean :fabric:runClient
..\..\gradlew.bat :common:clean :forge:clean :forge:runClient
```

#### Minecraft 1.21.1

```powershell
cd .\versions\1.21.1
..\..\gradlew.bat :fabric:runClient
..\..\gradlew.bat :neoforge:runClient
```

Clean run commands:

```powershell
cd .\versions\1.21.1
..\..\gradlew.bat :common:clean :fabric:clean :fabric:runClient
..\..\gradlew.bat :common:clean :neoforge:clean :neoforge:runClient
```

#### Minecraft 1.21.11

```powershell
cd .\versions\1.21.11
..\..\gradlew.bat :fabric:runClient
..\..\gradlew.bat :neoforge:runClient
```

Clean run commands:

```powershell
cd .\versions\1.21.11
..\..\gradlew.bat :common:clean :fabric:clean :fabric:runClient
..\..\gradlew.bat :common:clean :neoforge:clean :neoforge:runClient
```

### Build one Minecraft version

From the repository root, these tasks build every configured loader and runtime variant for one Minecraft version:

```powershell
.\gradlew.bat buildMc1201
.\gradlew.bat buildMc1211
.\gradlew.bat buildMc12111
```

### Build one Minecraft version by runtime variant

From the repository root, these tasks build every configured loader for one Minecraft version and one runtime variant.

#### Windows / DirectML

```powershell
.\gradlew.bat buildMc1201Windows
.\gradlew.bat buildMc1211Windows
.\gradlew.bat buildMc12111Windows
```

#### CUDA

```powershell
.\gradlew.bat buildMc1201Cuda
.\gradlew.bat buildMc1211Cuda
.\gradlew.bat buildMc12111Cuda
```

#### CPU / CoreML

```powershell
.\gradlew.bat buildMc1201Cpu
.\gradlew.bat buildMc1211Cpu
.\gradlew.bat buildMc12111Cpu
```

### Build inside a version folder

From a version folder, use the version's own Gradle tasks.

#### 1.20.1: Fabric + Forge

```powershell
cd .\versions\1.20.1
..\..\gradlew.bat buildAllVariants
..\..\gradlew.bat buildWindows
..\..\gradlew.bat buildCuda
..\..\gradlew.bat buildCpu
..\..\gradlew.bat buildFabricWindows
..\..\gradlew.bat buildFabricCuda
..\..\gradlew.bat buildFabricCpu
..\..\gradlew.bat buildForgeWindows
..\..\gradlew.bat buildForgeCuda
..\..\gradlew.bat buildForgeCpu
```

#### 1.21.1 and 1.21.11: Fabric + NeoForge

```powershell
cd .\versions\1.21.1
..\..\gradlew.bat buildAllVariants
..\..\gradlew.bat buildWindows
..\..\gradlew.bat buildCuda
..\..\gradlew.bat buildCpu
..\..\gradlew.bat buildFabricWindows
..\..\gradlew.bat buildFabricCuda
..\..\gradlew.bat buildFabricCpu
..\..\gradlew.bat buildNeoForgeWindows
..\..\gradlew.bat buildNeoForgeCuda
..\..\gradlew.bat buildNeoForgeCpu
```

```powershell
cd .\versions\1.21.11
..\..\gradlew.bat buildAllVariants
..\..\gradlew.bat buildWindows
..\..\gradlew.bat buildCuda
..\..\gradlew.bat buildCpu
..\..\gradlew.bat buildFabricWindows
..\..\gradlew.bat buildFabricCuda
..\..\gradlew.bat buildFabricCpu
..\..\gradlew.bat buildNeoForgeWindows
..\..\gradlew.bat buildNeoForgeCuda
..\..\gradlew.bat buildNeoForgeCpu
```

## Common issues

**A dynamic link library (DLL) initialization routine failed**

This can happen for some older Java versions. Use the Java version required by your Minecraft target:

- Java 17 for Minecraft 1.20.1.
- Java 21 for Minecraft 1.21.x.

The [latest Microsoft OpenJDK 17 and 21](https://learn.microsoft.com/en-us/java/openjdk/download) version is known to work.

**LoadLibrary failed with error 126** *CUDA build only*

This is typically due to an improper CUDA or cuDNN installation. See [CUDA_INSTALL.md](CUDA_INSTALL.md) for troubleshooting steps.

**java.lang.IllegalStateException: Failed to load terrain-diffusion models**

This typically indicates an out-of-memory error, or that the ONNX Runtime dependency was not visible to the loader at runtime. Terrain Diffusion's models take about 2.5 GB of RAM, so make sure to allocate enough RAM.

**If your issue is still not resolved, please [raise it here](https://github.com/xandergos/terrain-diffusion-mc/issues/new).**

## Building onnxruntime with DirectML

**Requirements**

- [Windows 10 SDK (10.0.17134.0)](https://developer.microsoft.com/en-us/windows/downloads/sdk-archive/index-legacy) — for Windows 10 version 1803 or newer
- Visual Studio 2017 toolchain — install *Desktop development with C++* from the VS Installer
- Visual Studio 2022 toolchain — same as above
- Python 3.10+: [https://python.org/](https://python.org/)
- CMake 3.28 or higher

Keep both VS toolchains up to date. Full details at the [ONNX Runtime build docs](https://onnxruntime.ai/docs/build/inferencing.html) and the [DirectML EP requirements](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html#build).

**Steps**

Run all commands from the **Developer Command Prompt for VS 2022**.

**Steps**

Run all commands from the **Developer Command Prompt for VS 2022**.

```powershell
git clone --recursive https://github.com/Microsoft/onnxruntime.git
cd onnxruntime
.\build.bat --config RelWithDebInfo --build_shared_lib --parallel --compile_no_warning_as_error --skip_submodule_sync --use_dml --build_java --build
```

The built jar appears in `java/build/`. Rename it to `onnxruntime-dml.jar` and place it in `libs/` in this repository.

## Note For Mod Developers

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is deliberately easy to work on. The model outputs elevation + 4 climate variables, and those are turned into Minecraft biomes by a **data file, not code**: [`biome_catalog.json`](versions/1.21.1/common/src/main/resources/biome_catalog.json) lists each biome with the climate windows it may appear in, and a weight saying how much of the overlapping area it should take. You can retune the entire biome layout of the world without recompiling — drop an edited copy into `config/terrain-diffusion-mc/`.

The pieces worth knowing:

- [`BiomeClassifier`](common/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java) derives climate variables per pixel and samples the noise fields.
- [`BiomeRuleEngine`](common/src/main/java/com/github/xandergos/terraindiffusionmc/biome/BiomeRuleEngine.java) picks among every biome eligible at a pixel, weighted by `rarity`, using a spatially coherent noise field so the result is real patches rather than per-pixel confetti.
- [`tools/biome-lab`](tools/biome-lab) is a standalone Python harness that Monte Carlo simulates a catalog against the real climate distributions. It exists because the failure mode here is silent: a rule can be subtly self-contradictory and make a biome unreachable with no error, crash, or warning. Run it after any catalog edit — it reports dead conditions, never-spawn biomes, and effective biome count.

The terrain diversity far outpaces the biome diversity and there's a real opportunity to close that gap. I'm hoping someone goes crazy with it.
