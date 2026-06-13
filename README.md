# Terrain Diffusion Mod [[Modrinth]](https://modrinth.com/mod/terrain-diffusion)

#### UPDATE: The research behind this mod has been accepted to SIGGRAPH 2026, the world's premier graphics conference! That means the research was officially peer reviewed and recognized as a significant contribution to the field. Enjoy the mod!

This is a Minecraft multiplatform mod integrating [Terrain Diffusion](https://github.com/xandergos/terrain-diffusion).

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

## Configuration

Edit `config/terrain-diffusion-mc.properties`, created automatically on first launch:

```properties
# Terrain Diffusion MC configuration

# Inference device: "cpu", "gpu", or "auto".
# "gpu" uses DirectML on the Windows build, or CUDA on the CUDA build.
# GPU builds default to "gpu" so startup fails loudly if no GPU is detected.
# CPU build defaults to "auto": uses CoreML on macOS, otherwise CPU.
inference.device=gpu

# Offload inactive models from VRAM between pipeline stages.
# Keeps peak VRAM to roughly 1.5-2 GB. Set to false if you have roughly 2.5+ GB free
# for slightly faster generation.
inference.offload_models=true

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
versions/1.20.1/         Minecraft 1.20.1: common + fabric + forge
versions/1.21.1/         Minecraft 1.21.1: common + fabric + neoforge
versions/1.21.11/        Minecraft 1.21.11: common + fabric + neoforge
```

Minecraft API-sensitive files live under `versions/<minecraft-version>/common`. This is intentional. Mixins, worldgen JSON, dimension types, biome source registration, and client world-creation screens are not stable enough to stay in the root `common` folder.

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

From the repository root:

```powershell
.\gradlew.bat buildMc1201
.\gradlew.bat buildMc1211
.\gradlew.bat buildMc12111
```

These tasks build every configured loader and runtime variant for that Minecraft version.

### Build per-version release jars

From a version folder, use the version's own Gradle tasks.

#### 1.20.1: Fabric + Forge

```powershell
cd .\versions\1.20.1
..\..\gradlew.bat buildFabricWindows
..\..\gradlew.bat buildFabricCuda
..\..\gradlew.bat buildFabricCpu
..\..\gradlew.bat buildForgeWindows
..\..\gradlew.bat buildForgeCuda
..\..\gradlew.bat buildForgeCpu
..\..\gradlew.bat buildRelease
```

#### 1.21.1 and 1.21.11: Fabric + NeoForge

```powershell
cd .\versions\1.21.1
..\..\gradlew.bat buildFabricWindows
..\..\gradlew.bat buildFabricCuda
..\..\gradlew.bat buildFabricCpu
..\..\gradlew.bat buildNeoForgeWindows
..\..\gradlew.bat buildNeoForgeCuda
..\..\gradlew.bat buildNeoForgeCpu
..\..\gradlew.bat buildRelease
```

```powershell
cd .\versions\1.21.11
..\..\gradlew.bat buildFabricWindows
..\..\gradlew.bat buildFabricCuda
..\..\gradlew.bat buildFabricCpu
..\..\gradlew.bat buildNeoForgeWindows
..\..\gradlew.bat buildNeoForgeCuda
..\..\gradlew.bat buildNeoForgeCpu
..\..\gradlew.bat buildRelease
```

### Build variant flags

The named tasks above are preferred, but the direct Gradle property calls also work.

```powershell
# Default Windows/DirectML build
..\..\gradlew.bat :fabric:build
..\..\gradlew.bat :forge:build
..\..\gradlew.bat :neoforge:build

# CUDA build
..\..\gradlew.bat :fabric:build -PuseCuda=true
..\..\gradlew.bat :forge:build -PuseCuda=true
..\..\gradlew.bat :neoforge:build -PuseCuda=true

# CPU/CoreML build
..\..\gradlew.bat :fabric:build -PuseCpu=true
..\..\gradlew.bat :forge:build -PuseCpu=true
..\..\gradlew.bat :neoforge:build -PuseCpu=true
```

Only one of `-PuseDml=true`, `-PuseCuda=true`, or `-PuseCpu=true` may be set at a time. If no property is set, the build defaults to the Windows/DirectML variant.

### Build outputs

Loader jars are written under each loader module:

```text
versions/<minecraft-version>/fabric/build/libs/
versions/<minecraft-version>/forge/build/libs/
versions/<minecraft-version>/neoforge/build/libs/
```

Per-version release collection writes to:

```text
versions/<minecraft-version>/build/release/<loader>/
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

While modifying the AI terrain itself is quite complex, the integration with Minecraft biomes is extremely simple. The model outputs elevation + 4 climate variables, and this is converted to Minecraft biomes with hand-written rules. This is the most immediate way to improve the quality of the terrain and is relatively easy, but takes time to get realistic. The entire biome classifier is [only 250 lines](https://github.com/xandergos/terrain-diffusion-mc/blob/master/src/main/java/com/github/xandergos/terraindiffusionmc/pipeline/BiomeClassifier.java).

The terrain diversity far outpaces the biome diversity and there's a real opportunity to close that gap. I'm hoping someone goes crazy with it.
