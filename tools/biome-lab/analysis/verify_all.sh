#!/usr/bin/env bash
# End-to-end verification: static validators, surface-biome reachability, and the soundness of
# every candidate-filter range the explorer will hand the user.
set -uo pipefail

REPO=/home/derek/IdeaProjects/terrain-diffusion-plus
SP=/tmp/claude-1000/-home-derek-IdeaProjects-terrain-diffusion-plus/3eda2d28-84dc-4c0d-9309-7025cf240420/scratchpad
CATALOG=$REPO/common/src/main/resources/biome_catalog.json
PIPELINE=$HOME/.local/share/PrismLauncher/instances/1.21.1/minecraft/terrain-diffusion-models/pipeline_data.json
GSON=/home/derek/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1/lib/gson-2.13.1.jar
N=${1:-2000000}

cd "$REPO"

echo "=============================================================="
echo "1. biome-lab: static validators + Monte Carlo"
echo "=============================================================="
( cd tools/biome-lab && python3 run.py --catalog "$CATALOG" --pipeline-data "$PIPELINE" \
    --min-samples "$N" --force-montecarlo --report "$SP/final.md" 2>&1 | tail -6 )

echo
echo "=============================================================="
echo "2. Surface-biome reachability"
echo "=============================================================="
python3 "$SP/status.py" "$CATALOG" "$SP/final.md"
STATUS_RC=$?

echo
echo "=============================================================="
echo "3. Candidate-filter soundness (explorer 'Show on Map')"
echo "=============================================================="
rm -rf "$SP/out" && mkdir -p "$SP/out"
javac -nowarn -d "$SP/out" -cp "$GSON" -sourcepath common/src/main/java \
    common/src/main/java/com/github/xandergos/terraindiffusionmc/biome/BiomeCandidateFilterCalculator.java \
    "$SP/CandProbe.java" || exit 1
python3 "$SP/check_soundness.py" "$CATALOG" "$PIPELINE" "$N" "$GSON:$SP/out" 2>&1 | tail -6
SOUND_RC=${PIPESTATUS[0]}

echo
echo "=============================================================="
echo "verdict: reachability rc=$STATUS_RC  soundness rc=$SOUND_RC"
echo "=============================================================="
