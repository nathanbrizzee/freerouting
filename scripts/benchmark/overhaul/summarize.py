"""Print one TSV line from a Freerouting result manifest: board, wall, unrouted, violations, passes, fallback, state, ses hash."""
import hashlib
import json
import os
import sys

name, start, end, manifest_path, ses_path = sys.argv[1:6]
time_path = sys.argv[6] if len(sys.argv) > 6 else None
wall = float(end) - float(start)
unrouted = viol = passes = fallback = state = "?"
try:
    with open(manifest_path) as f:
        m = json.load(f)
    state = m.get("final_state", "?")
    stats = m.get("board_statistics") or {}
    phases = m.get("phases") or {}
    auto = phases.get("autorouter") or {}
    passes = auto.get("passes_completed", "?")
    fb = phases.get("fallback") or {}
    chosen = fb.get("chosen") or {}
    if isinstance(chosen, str):
        # Since batch 2 the manifest lists the attempts and names the chosen strategy.
        name_chosen = chosen
        chosen = next((a for a in fb.get("attempts", []) if a.get("strategy") == name_chosen), {})
        chosen = dict(chosen, strategy=name_chosen)
    fallback = chosen.get("strategy", "-") if fb else "-"
    # Prefer the fallback's chosen outcome, then the final board statistics.
    unrouted = chosen.get("unrouted", "?")
    viol = chosen.get("clearance_violations", "?")
    if unrouted == "?":
        conn = stats.get("connections") or {}
        unrouted = conn.get("incompleteCount", conn.get("incomplete_count", "?"))
    if viol == "?":
        cv = stats.get("clearanceViolations") or stats.get("clearance_violations") or {}
        viol = cv.get("totalCount", cv.get("total_count", "?"))
except (OSError, ValueError) as e:
    state = f"no-manifest({e.__class__.__name__})"
log_path = os.path.splitext(ses_path)[0] + ".log"
if os.path.exists(log_path):
    # The manifest's pass count is the last fallback attempt's; count every pass in the log and
    # report "default run passes/all passes".
    default_passes = total_passes = 0
    in_fallback = False
    with open(log_path, errors="replace") as f:
        for line in f:
            if "Routing fallback chose '" in line:
                fallback = line.split("Routing fallback chose '", 1)[1].split("'", 1)[0]
            if "Routing fallback:" in line:
                in_fallback = True
            if "Auto-routing pass #" in line and "was completed" in line:
                total_passes += 1
                if not in_fallback:
                    default_passes += 1
    passes = f"{default_passes}/{total_passes}"
sha = "-"
if os.path.exists(ses_path):
    with open(ses_path, "rb") as f:
        sha = hashlib.sha256(f.read()).hexdigest()[:12]
cpu = ""
if time_path and os.path.exists(time_path):
    try:
        with open(time_path) as f:
            user, sys_ = f.read().split()[-2:]
        cpu = f"\t{float(user) + float(sys_):.1f}"
    except ValueError:
        cpu = "\t?"
print(f"{name}\t{wall:.1f}\t{unrouted}\t{viol}\t{passes}\t{fallback}\t{state}\t{sha}{cpu}")
