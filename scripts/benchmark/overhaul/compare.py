#!/usr/bin/env python3
"""Print the board set side by side for one or more run labels.

Usage: compare.py <label> [label ...]
Each cell: open, violations, state (C completed, T timed out), wall s, CPU s, SES hash prefix.
Totals per label: open connections, CPU seconds, boards that completed with 0 open.
"""
import csv
import glob
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
labels = sys.argv[1:]
rows = {}
for label in labels:
    for path in glob.glob(os.path.join(here, "runs", label, "summary.tsv")):
        with open(path, newline="") as f:
            for r in csv.DictReader(f, delimiter="\t"):
                rows.setdefault(r["board"], {})[label] = r

for board in sorted(rows):
    cells = []
    for label in labels:
        r = rows[board].get(label)
        if r is None:
            cells.append("%-36s" % "-")
            continue
        cells.append(
            "%4s v%-4s %s %5.0fs %5.0fc %s"
            % (
                r["unrouted"],
                r["clearance_viol"],
                r["state"][0],
                float(r["wall_s"]),
                float(r["cpu_s"]),
                r["ses_sha"][:6],
            )
        )
    print("%-40s | %s" % (board[:40], " | ".join(cells)))

for label in labels:
    rs = [rows[b][label] for b in rows if label in rows[b]]
    print(
        "%s: %d boards, open %d, cpu %d s, clean %d"
        % (
            label,
            len(rs),
            sum(int(r["unrouted"]) for r in rs),
            round(sum(float(r["cpu_s"]) for r in rs)),
            sum(1 for r in rs if r["unrouted"] == "0" and r["state"] == "COMPLETED"),
        )
    )
