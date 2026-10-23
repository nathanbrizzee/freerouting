#!/usr/bin/env python3
"""Best open count reached by each minute of a run, from the per-pass log lines.

Usage: capcurve.py <runs/label dir>
Tells whether a shorter cap would have given the same answer, without another run.
"""
import datetime as dt
import glob
import os
import re
import sys

ts = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+)")
pas = re.compile(r"Auto-routing pass #(\d+).*?\((\d+) unrouted and (\d+) violations")
marks = [60, 120, 180, 240, 300, 360]
print("%-40s %6s %s  %s" % ("board", "end_s", " ".join("%5s" % ("%dm" % (m // 60)) for m in marks), "best"))
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.log"))):
    t0 = None
    pts = []
    for line in open(f, errors="replace"):
        m = ts.match(line)
        if not m:
            continue
        t = dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S.%f")
        if t0 is None:
            t0 = t
        p = pas.search(line)
        if p:
            pts.append(((t - t0).total_seconds(), int(p.group(2))))
    name = os.path.basename(f)[:-4][:40]
    if not pts:
        print("%-40s no routing passes" % name)
        continue

    def best_at(s):
        seen = [u for (tt, u) in pts if tt <= s]
        return min(seen) if seen else None

    cells = " ".join("%5s" % (best_at(s) if best_at(s) is not None else "-") for s in marks)
    print(
        "%-40s %6.0f %s  %d after %d passes"
        % (name, pts[-1][0], cells, min(u for _, u in pts), len(pts))
    )
