#!/usr/bin/env bash
# Run the overhaul board set with one jar and print one summary line per board.
# Usage: [EXTRA="--router.x=y ..."] ./run-set.sh <label> <jar> [board ...]
# Output: runs/<label>/<board>.{log,ses,json,time} and runs/<label>/summary.tsv
# cpu_s (user+sys) is less sensitive than wall_s to other load on the machine; compare it when
# the machine is busy. No -mt flag: since add21ca5e "-mt 0" turns the optimizer off, and the
# set runs on defaults (optimizer on, CPU count - 1 threads), as earlier sets effectively did.
set -euo pipefail
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/jdk-25}
export PATH=$JAVA_HOME/bin:$PATH

here=$(cd "$(dirname "$0")" && pwd)
fixtures=${FIXTURES:-$here/../../../fixtures}
label=$1; jar=$(realpath "$2"); shift 2
boards=("$@")
if [ ${#boards[@]} -eq 0 ]; then
  boards=(Issue508-DAC2020_bm01 Issue508-DAC2020_bm05 Issue313-FastTest Issue143-rpi_splitter Issue070-Autorouter_FQ101_PCB_2022-05-13)
fi
timeout=${TIMEOUT:-00:30:00}
heap=${HEAP:-4g}
out=$here/runs/$label
mkdir -p "$out"
summary=$out/summary.tsv
[ -f "$summary" ] || printf 'board\twall_s\tunrouted\tclearance_viol\tpasses\tfallback\tstate\tses_sha\tcpu_s\n' > "$summary"

for b in "${boards[@]}"; do
  dsn=$fixtures/$b.dsn
  [ -f "$dsn" ] || dsn=${b}   # allow a full path to a DSN outside fixtures
  name=$(basename "$dsn" .dsn)
  start=$(date +%s.%N)
  /usr/bin/time -o "$out/$name.time" -f "%U %S" java -Xmx"$heap" -jar "$jar" -de "$dsn" -do "$out/$name.ses" --gui.enabled=false \
    --router.job_timeout="$timeout" --router.result_json="$out/$name.json" ${EXTRA:-} \
    > "$out/$name.log" 2>&1 || true
  end=$(date +%s.%N)
  python3 -I "$here/summarize.py" "$name" "$start" "$end" "$out/$name.json" "$out/$name.ses" "$out/$name.time" >> "$summary"
  tail -1 "$summary"
done
