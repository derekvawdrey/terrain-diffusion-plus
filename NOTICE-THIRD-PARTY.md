# Third-party notices

This project (MIT licensed, see `LICENSE`) bundles the following third-party mods
unmodified, as jar-in-jar dependencies, for the 1.21.1 Fabric and NeoForge builds
(`versions/1.21.1/build.gradle`) and the 1.20.1 Fabric and Forge builds
(`versions/1.20.1/build.gradle`). They are used to generate overworld cave systems,
replacing the vanilla `minecraft:cave` / `minecraft:cave_extra_underground` carvers
via their own biome-modification hooks — no source from either project has been
copied into this repository.

## YUNG's Better Caves
- Source: https://github.com/YUNG-GANG/YUNGs-Better-Caves
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Bundled versions: `1.21.1-Fabric-3.1.4` / `1.21.1-NeoForge-3.1.4`, `1.20.1-Fabric-2.0.6` / `1.20.1-Forge-2.0.6` (via Modrinth: `maven.modrinth:yungs-better-caves`)

## YUNG's API
- Source: https://github.com/YUNG-GANG/YUNGs-API
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Bundled versions: `1.21.1-Fabric-5.1.6` / `1.21.1-NeoForge-5.1.6`, `1.20-Fabric-4.0.6` / `1.20-Forge-4.0.6` (via Modrinth: `maven.modrinth:yungs-api`)
- Required runtime dependency of YUNG's Better Caves.

## Cloth Config API
- Source: https://github.com/shedaniel/cloth-config
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)
- Bundled versions: `15.0.140+fabric` (1.21.1), `11.1.136+fabric` (1.20.1) (via Modrinth: `maven.modrinth:cloth-config`)
- Required runtime dependency of YUNG's Better Caves on Fabric; a nested mod whose
  dependencies are unsatisfied is silently dropped by Fabric Loader, so without it
  Better Caves would never load. The Forge and NeoForge builds do not need it.

Every mod above is distributed as its original, unmodified compiled jar (including
its own bundled `LICENSE` file) embedded inside this mod's jar. The full LGPL-3.0
text is available at https://www.gnu.org/licenses/lgpl-3.0.html.

Minecraft 1.21.11 has no Better Caves release, so those builds bundle nothing and
generate caves with vanilla's carvers; installing a cave mod alongside works and is
handled the same way (see the README's "Caves" section).
