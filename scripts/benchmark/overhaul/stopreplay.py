"""Replay a work-based pass stop over finished run logs.

Usage: python3 -I stopreplay.py <run folder or log> [...] [--ratios 0.5,1,2] [--min-passes 2]

For each log it reads the per-pass open count, wall time and search steps (the "Auto-routing pass
#N ... completed in S seconds with score X (U unrouted" and "Auto-routing pass #N: Search work: ...
K steps" lines) and, for each ratio, finds the pass where `autorouter.stagnation_work_ratio` would
stop: at least min-passes after the fewest open, with more steps since it than ratio times the steps
that reached it. It prints the fewest open up to that pass against the fewest of the whole run, and
the pass time it would keep. Step counts are machine independent; the times are the log's.
"""
import glob
import os
import re
import sys

PASS = re.compile(r"Auto-routing pass #(\d+) on board .* completed in ([\d.]+) seconds with score [-\d.]+ \((\d+) unrouted")
WORK = re.compile(r"Auto-routing pass #(\d+): Search work: \d+ connection searches, (\d+) steps")


def passes(path):
    rows = {}
    with open(path, encoding="utf8", errors="replace") as f:
        for line in f:
            m = PASS.search(line)
            if m:
                rows.setdefault(int(m[1]), {})
                rows[int(m[1])].update(sec=float(m[2]), open=int(m[3]))
                continue
            m = WORK.search(line)
            if m:
                rows.setdefault(int(m[1]), {})["steps"] = int(m[2])
    out = []
    for n in sorted(rows):
        r = rows[n]
        if "open" in r:
            out.append((n, r["open"], r["sec"], r.get("steps", 0)))
    return out


def replay(rows, ratio, min_passes):
    cum = 0
    fewest, at_pass, at_steps = None, 0, 0
    for i, (n, opened, sec, steps) in enumerate(rows):
        cum += steps
        if fewest is None or opened < fewest:
            fewest, at_pass, at_steps = opened, n, cum
        if opened > 0 and n - at_pass >= min_passes and cum - at_steps > ratio * at_steps:
            return i, fewest
    return len(rows) - 1, fewest


def main(argv):
    ratios = [0.5, 1.0, 2.0]
    min_passes = 2
    paths = []
    i = 0
    while i < len(argv):
        if argv[i] == "--ratios":
            ratios = [float(x) for x in argv[i + 1].split(",")]
            i += 2
        elif argv[i] == "--min-passes":
            min_passes = int(argv[i + 1])
            i += 2
        else:
            paths += sorted(glob.glob(os.path.join(argv[i], "*.log"))) if os.path.isdir(argv[i]) else [argv[i]]
            i += 1
    head = "board\tpasses\tbest\tpass_s"
    for r in ratios:
        head += f"\tr{r:g}:stop/best/kept_s"
    print(head)
    totals = {r: [0, 0.0] for r in ratios}
    all_best = 0
    all_sec = 0.0
    for path in paths:
        rows = passes(path)
        if not rows:
            continue
        best = min(r[1] for r in rows)
        total_sec = sum(r[2] for r in rows)
        all_best += best
        all_sec += total_sec
        line = f"{os.path.basename(path)[:34]}\t{len(rows)}\t{best}\t{total_sec:.0f}"
        for r in ratios:
            idx, fewest = replay(rows, r, min_passes)
            kept = sum(x[2] for x in rows[: idx + 1])
            totals[r][0] += fewest
            totals[r][1] += kept
            mark = "" if fewest == best else "*"
            line += f"\t#{rows[idx][0]}/{fewest}{mark}/{kept:.0f}"
        print(line)
    tail = f"TOTAL\t\t{all_best}\t{all_sec:.0f}"
    for r in ratios:
        tail += f"\t{totals[r][0]} open/{totals[r][1]:.0f} s"
    print(tail)
    print("* = fewer open was reached later in the run than where this ratio stops")


if __name__ == "__main__":
    main(sys.argv[1:])
