#!/usr/bin/env bash
# Run the board set in two groups at two caps, several boards at a time, and print the table.
# Usage: [STREAMS=5] [HEAP=3g] [EXTRA="--router.x=y"] ./run-batch.sh <label> <jar> [fast|hard|all]
# Output: runs/<label>/... as run-set.sh writes it, then compare.py and capcurve.py on that label.
# The fast group reached its final open count inside 2 minutes in the 6-minute runs of 2026-10-21;
# the hard group still improved between minutes 3 and 6 (roadmap section 8). Adjust the caps there.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
label=$1; jar=$(realpath "$2"); group=${3:-all}
streams=${STREAMS:-5}
export HEAP=${HEAP:-3g}
export EXTRA=${EXTRA:-}
P=$here/boards/pcbench
B=$here/boards

fast_cap=${FAST_CAP:-00:02:00}
hard_cap=${HARD_CAP:-00:06:00}
fast=(
  "$P/TX5823_TX5823.dsn" "$P/Starling_Starling_V1.dsn" "$P/kitspace_mppt-1210-hus.dsn"
  "$P/Librecalc-Hardware__autosave-calculator.dsn" "$P/front-end-modules_LimeSDR_Sony.dsn"
  "$P/EncoderBoard_Enc_Pan_Led.dsn" "$P/OpenHardwareExG_Shield_OpenHardwareExG_Shield.dsn"
  "$P/kitspace_sensor.dsn" "$P/DoroidOscillo-Board_Android_Oscilloscope.dsn"
)
hard=(
  "$P/kitspace_lpa96.dsn" "$P/kitspace_Unifying.dsn" "$B/ddr5_testbed.dsn"
  "$B/synthetic/hard6f.dsn" "$P/reworkctf_reworkctf.dsn" "$B/sdi_mipi.dsn" "$P/oskirby_logicbone.dsn"
  "$P/oskirby_logicbone_blind.dsn"
)

out=$here/runs/$label
mkdir -p "$out"
summary=$out/summary.tsv
[ -f "$summary" ] || printf 'board\twall_s\tunrouted\tclearance_viol\tpasses\tfallback\tstate\tses_sha\tcpu_s\n' > "$summary"

# Hard boards first so the long runs start while the short ones fill the other streams.
jobs=()
if [ "$group" != fast ]; then for d in "${hard[@]}"; do jobs+=("$hard_cap $d"); done; fi
if [ "$group" != hard ]; then for d in "${fast[@]}"; do jobs+=("$fast_cap $d"); done; fi

printf '%s\n' "${jobs[@]}" | xargs -P "$streams" -L 1 bash -c \
  'TIMEOUT=$0 "'"$here"'/run-set.sh" "'"$label"'" "'"$jar"'" "$1" > /dev/null 2>&1 || true'

python3 -I "$here/compare.py" "$label"
python3 -I "$here/capcurve.py" "$out"
