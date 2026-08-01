#!/usr/bin/env python3
"""Generate a local datapack that gives terrain-diffusion terrain Sengoku Jidai's ground blocks.

WHY THIS IS A SCRIPT AND NOT A SHIPPED FILE
-------------------------------------------
Sengoku Jidai rethemes the 64 vanilla biomes in place, so installing it alongside
terrain-diffusion already yields Japanese vegetation, biome colours and mob spawns with no setup
at all. What does *not* carry over is the surface rule -- which blocks the ground is actually
made of. Sengoku's lives in `minecraft:worldgen/noise_settings/overworld.json`, and
terrain-diffusion replaces the overworld with its own noise settings
(`terrain-diffusion-mc:terrain_diffusion`), so Sengoku's version is never consulted and the
ground stays vanilla-ish under Japanese trees.

Fixing that means combining their surface rule with our noise router. Sengoku is published
**All Rights Reserved**, so that combined file cannot be shipped inside this mod. It can,
however, be built on the machine of someone who already has the mod, out of the copy they
already legally hold -- which is what this does. Nothing generated here belongs in this repo.

WHAT IT PRODUCES
----------------
A datapack that overrides `terrain-diffusion-mc:terrain_diffusion` with an identical copy of
itself except for `surface_rule`, which is taken from Sengoku. Because it reuses the same id
rather than defining a new one, the existing "Terrain Diffusion" world type simply starts using
Japanese surfaces -- no second world type to pick, and no change to the mod jar.

USAGE
-----
    python3 build_surface_datapack.py \\
        --sengoku ~/.../mods/sengokuFabric-2.1.4+1.21.1-Fabric.jar \\
        --output ~/.../saves/MyWorld/datapacks/terrain-diffusion-sengoku-surfaces.zip

`--sengoku` accepts either the mod jar or the standalone Sengoku datapack zip. Add the result to
a world's `datapacks/` folder (or via "Data Packs" on the world-creation screen). Both mods must
still be installed; this only redirects which surface rule is used.
"""
import argparse
import json
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_NOISE_SETTINGS = (REPO / "versions/1.21.1/common/src/main/resources/data"
                          / "terrain-diffusion-mc/worldgen/noise_settings/terrain_diffusion.json")

SENGOKU_SURFACE_SOURCE = "data/minecraft/worldgen/noise_settings/overworld.json"
OUTPUT_ENTRY = "data/terrain-diffusion-mc/worldgen/noise_settings/terrain_diffusion.json"

# 1.21.1 is pack_format 48; the open-ended max keeps the pack loadable on later versions rather
# than failing closed the way a pinned single format would.
PACK_MCMETA = {
    "pack": {
        "description": "Sengoku Jidai surface rules on Terrain Diffusion terrain",
        "pack_format": 48,
        "supported_formats": {"min_inclusive": 48, "max_inclusive": 99},
    }
}


def read_sengoku_surface_rule(archive_path: Path):
    with zipfile.ZipFile(archive_path) as archive:
        try:
            raw = archive.read(SENGOKU_SURFACE_SOURCE)
        except KeyError:
            raise SystemExit(
                f"{archive_path} has no {SENGOKU_SURFACE_SOURCE}.\n"
                "Expected the Sengoku Jidai mod jar or its standalone datapack zip."
            )
    settings = json.loads(raw)
    rule = settings.get("surface_rule")
    if rule is None:
        raise SystemExit(f"{archive_path}'s overworld noise settings carry no surface_rule.")
    return rule


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sengoku", required=True, type=Path,
                        help="Path to the Sengoku Jidai mod jar or its datapack zip.")
    parser.add_argument("--output", required=True, type=Path,
                        help="Datapack zip to write, e.g. <world>/datapacks/td-sengoku.zip")
    parser.add_argument("--noise-settings", type=Path, default=DEFAULT_NOISE_SETTINGS,
                        help="terrain-diffusion noise settings to graft onto (defaults to this "
                             "checkout's 1.21.1 copy).")
    args = parser.parse_args()

    if not args.sengoku.exists():
        raise SystemExit(f"No such file: {args.sengoku}")
    if not args.noise_settings.exists():
        raise SystemExit(f"No such file: {args.noise_settings}")

    settings = json.loads(args.noise_settings.read_text())
    original = json.dumps(settings.get("surface_rule"))
    settings["surface_rule"] = read_sengoku_surface_rule(args.sengoku)
    grafted = json.dumps(settings["surface_rule"])

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as out:
        out.writestr("pack.mcmeta", json.dumps(PACK_MCMETA, indent=2))
        out.writestr(OUTPUT_ENTRY, json.dumps(settings, indent=2))

    print(f"surface_rule: {len(original):,} chars -> {len(grafted):,} chars")
    print(f"wrote {args.output}")
    print("Add it to a world's datapacks/ folder; both mods must remain installed.")


if __name__ == "__main__":
    sys.exit(main())
