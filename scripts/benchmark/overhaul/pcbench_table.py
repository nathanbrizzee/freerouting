import glob, json, os, re, sys
"""Prints the PCBench table. Usage: python3 -I pcbench_table.py <runs dir> <label> <streams>"""
runs, label, streams = sys.argv[1], sys.argv[2], int(sys.argv[3])
rows = []
for s in range(streams):
    d = os.path.join(runs, "%s-%d" % (label, s))
    for line in open(os.path.join(d, "summary.tsv")).read().splitlines()[1:]:
        board, wall, unrouted, viol, passes, fb, state, sha, cpu = line.split("\t")
        new = "?"
        try:
            m = json.load(open(os.path.join(d, board + ".json")))
            new = (m.get("board_statistics") or {}).get("clearance_violations", {}).get("new_or_changed_count", "?")
        except (OSError, ValueError):
            pass
        log = os.path.join(d, board + ".log")
        text = open(log, errors="replace").read() if os.path.exists(log) else ""
        # A job stopped by router.max_search_steps logs its exact total; otherwise sum the passes.
        final = re.findall(r"stopped at its search step budget after (\d+) steps", text)
        steps = int(final[-1]) if final else sum(int(x) for x in re.findall(r"Search work: \d+ [a-z ]+ searches, (\d+) steps", text))
        rows.append((board, unrouted, new, state, float(cpu or 0), float(wall or 0), steps))
rows.sort()
print("%-44s %8s %5s %-10s %8s %8s %12s" % ("board", "unrouted", "new", "state", "cpu_s", "wall_s", "steps"))
for r in rows:
    print("%-44s %8s %5s %-10s %8.1f %8.1f %12d" % ((r[0][:44],) + r[1:]))
clean = sum(1 for r in rows if r[1] == "0" and str(r[2]) == "0")
opens = sum(int(r[1]) for r in rows if r[1].isdigit())
print("clean %d/%d, open connections %d, cpu %.0f s, wall %.0f s (sum), steps %d" % (
    clean, len(rows), opens, sum(r[4] for r in rows), sum(r[5] for r in rows), sum(r[6] for r in rows)))
