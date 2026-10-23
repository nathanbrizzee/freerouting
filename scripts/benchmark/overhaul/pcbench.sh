#!/usr/bin/env bash
# PCBench reference set: 24 open-source boards (see pcbench_pick.txt), each capped at TIMEOUT
# (default 2 minutes), run in STREAMS parallel streams (default 6).
# Usage: [EXTRA="--router.x=y"] [TIMEOUT=00:02:00] [STREAMS=6] ./pcbench.sh <label> <jar>
# Prints one line per board and the totals: clean boards (no open connections and no new or
# changed clearance violations), open connections, CPU and wall seconds, search steps.
# Prepare the boards once with: python3 -I pcbench_prep.py pcbench_pick.txt ../external/PCBench boards/pcbench
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
label=$1; jar=$(realpath "$2")
export TIMEOUT=${TIMEOUT:-00:02:00}
streams=${STREAMS:-6}
mapfile -t boards < <(ls "$here"/boards/pcbench/*.dsn)
for ((s = 0; s < streams; s++)); do
  part=()
  for ((i = s; i < ${#boards[@]}; i += streams)); do part+=("${boards[$i]}"); done
  "$here/run-set.sh" "$label-$s" "$jar" "${part[@]}" > "$here/runs-$label-$s.out" 2>&1 &
done
wait
python3 -I "$here/pcbench_table.py" "$here/runs" "$label" "$streams"
