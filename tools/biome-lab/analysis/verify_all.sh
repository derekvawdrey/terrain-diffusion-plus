#!/usr/bin/env bash
# End-to-end verification: static validators, surface-biome reachability, and the soundness of
# every candidate-filter range the explorer will hand the user.
set -uo pipefail

REPO=/home/derek/IdeaProjects/terrain-diffusion-plus
LAB=$REPO/tools/biome-lab
CATALOG=${1:-$HOME/.local/share/PrismLauncher/instances/1.21.1/minecraft/config/terrain-diffusion-mc/biome_catalog.json}
PIPELINE=$HOME/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json
GSON=/home/derek/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/lib/gson-2.13.1.jar
N=${2:-2000000}
OUTDIR=/tmp/biome-lab-verify

cd "$REPO"

echo "=============================================================="
echo "1. biome-lab: static validators + Monte Carlo"
echo "=============================================================="
( cd tools/biome-lab && python3 run.py --catalog "$CATALOG" --pipeline-data "$PIPELINE" \
    --min-samples "$N" --force-montecarlo --report "$OUTDIR/final.md" 2>&1 | tail -6 )

echo
echo "=============================================================="
echo "2. Surface-biome reachability"
echo "=============================================================="
python3 "$LAB/analysis/status.py" "$CATALOG" "$OUTDIR/final.md"
STATUS_RC=$?

echo
echo "=============================================================="
echo "3. Candidate-filter soundness (explorer 'Show on Map')"
echo "=============================================================="
rm -rf "$OUTDIR/out" && mkdir -p "$OUTDIR/out"
javac -nowarn -d "$OUTDIR/out" -cp "$GSON" -sourcepath common/src/main/java \
    common/src/main/java/com/github/xandergos/terraindiffusionmc/biome/BiomeCandidateFilterCalculator.java \
    "$LAB/analysis/CandProbe.java" || exit 1
python3 "$LAB/analysis/check_soundness.py" "$CATALOG" "$PIPELINE" "$N" "$GSON:$OUTDIR/out" 2>&1 | tail -6
SOUND_RC=${PIPESTATUS[0]}

echo
echo "=============================================================="
echo "verdict: reachability rc=$STATUS_RC  soundness rc=$SOUND_RC"
echo "=============================================================="
