# Third-party notices

This project (MIT licensed, see `LICENSE`) bundles the following third-party mods
unmodified, as jar-in-jar dependencies, for the 1.21.1 Fabric and NeoForge builds
(`versions/1.21.1/build.gradle`). They are used to generate overworld cave systems,
replacing the vanilla `minecraft:cave` / `minecraft:cave_extra_underground` carvers
via their own biome-modification hooks — no source from either project has been
copied into this repository.

## YUNG's Better Caves
- Source: https://github.com/YUNG-GANG/YUNGs-Better-Caves
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Bundled version: `1.21.1-Fabric-3.1.4` / `1.21.1-NeoForge-3.1.4` (via Modrinth: `maven.modrinth:yungs-better-caves`)

## YUNG's API
- Source: https://github.com/YUNG-GANG/YUNGs-API
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Bundled version: `1.21.1-Fabric-5.1.6` / `1.21.1-NeoForge-5.1.6` (via Modrinth: `maven.modrinth:yungs-api`)
- Required runtime dependency of YUNG's Better Caves.

Both mods are distributed as their original, unmodified compiled jars (including
their own bundled `LICENSE` files) embedded inside this mod's jar. The full LGPL-3.0
text is available at https://www.gnu.org/licenses/lgpl-3.0.html.
