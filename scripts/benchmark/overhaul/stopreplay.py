"""Replay a work-based pass stop over finished run logs.

Usage: python3 -I stopreplay.py <run folder or log> [...] [--ratios 0.5,1,2] [--min-passes 2] [--first-pass 1] [--gains 0.2]

With --gains it also replays `autorouter.stagnation_gain_ratio`: from pass 8, the lane stops once the
last two passes cut the fewest open by less than the fraction, once the lane has spent 100 million
search steps (the g columns).

For each log (split into the race lanes and continuation runs, each its own batch loop) it reads the per-pass open count, wall time and search steps (the "Auto-routing pass
#N ... completed in S seconds with score X (U unrouted" and "Auto-routing pass #N: Search work: ...
K steps" lines) and, for each ratio, finds the pass where `autorouter.stagnation_work_ratio` would
stop: at least min-passes after the fewest open, with more steps since it than ratio times the steps
that reached it, and not before pass first-pass. It prints the fewest open up to that pass against the fewest of the whole run, and
the pass time it would keep. Step counts are machine independent; the times are the log's.
"""
import glob
import os
import re
import sys

PASS = re.compile(r"\] (?:\[([^\]]+)\] )?Auto-routing pass #(\d+) on board .* completed in ([\d.]+) seconds with score [-\d.]+ \((\d+) unrouted")
WORK = re.compile(r"\] (?:\[([^\]]+)\] )?Auto-routing pass #(\d+): Search work: \d+ connection searches, (\d+) steps")


def passes(path):
    """The log's passes as segments, one per lane run: the race lanes ([name] tags) and the
    untagged default run each run their own batch loop, and a pass number that does not rise
    within a lane starts a new run (a continuation). Each segment is a list of
    (pass, open, seconds, steps)."""
    segments = {}
    current = {}
    with open(path, encoding="utf8", errors="replace") as f:
        for line in f:
            m = PASS.search(line)
            if m:
                lane, n = m[1] or "", int(m[2])
                seg = current.get(lane)
                if seg is None or n <= seg[-1][0]:
                    seg = []
                    segments.setdefault(lane, []).append(seg)
                    current[lane] = seg
                seg.append([n, int(m[4]), float(m[3]), 0])
                continue
            m = WORK.search(line)
            if m:
                lane, n = m[1] or "", int(m[2])
                seg = current.get(lane)
                if seg and seg[-1][0] == n:
                    seg[-1][3] = int(m[3])
    return [(lane, [tuple(r) for r in seg]) for lane, segs in segments.items() for seg in segs if seg]


def replay(rows, ratio, min_passes, first_pass=1):
    cum = 0
    fewest, at_pass, at_steps = None, 0, 0
    for i, (n, opened, sec, steps) in enumerate(rows):
        cum += steps
        if fewest is None or opened < fewest:
            fewest, at_pass, at_steps = opened, n, cum
        if opened > 0 and n >= first_pass and n - at_pass >= min_passes and cum - at_steps > ratio * at_steps:
            return i, fewest
    return len(rows) - 1, fewest


def replay_gain(rows, gain, first_pass=8, window=2, min_steps=100_000_000):
    best = []
    fewest = None
    cum = 0
    for i, (n, opened, sec, steps) in enumerate(rows):
        cum += steps
        fewest = opened if fewest is None or opened < fewest else fewest
        best.append(fewest)
        if opened > 0 and fewest > 0 and n >= first_pass and i >= window and cum >= min_steps:
            before = best[i - window]
            if before - fewest < gain * before:
                return i, fewest
    return len(rows) - 1, fewest


def main(argv):
    gains = []
    ratios = [0.5, 1.0, 2.0]
    min_passes = 2
    first_pass = 1
    paths = []
    i = 0
    while i < len(argv):
        if argv[i] == "--ratios":
            ratios = [float(x) for x in argv[i + 1].split(",")]
            i += 2
        elif argv[i] == "--gains":
            gains = [float(x) for x in argv[i + 1].split(",")]
            i += 2
        elif argv[i] == "--first-pass":
            first_pass = int(argv[i + 1])
            i += 2
        elif argv[i] == "--min-passes":
            min_passes = int(argv[i + 1])
            i += 2
        else:
            paths += sorted(glob.glob(os.path.join(argv[i], "*.log"))) if os.path.isdir(argv[i]) else [argv[i]]
            i += 1
    head = "board\tpasses\tbest\tpass_s"
    for r in ratios:
        head += f"\tr{r:g}:best/kept_s [stopped lanes]"
    print(head)
    totals = {r: [0, 0.0] for r in ratios}
    gtotals = {g: [0, 0.0] for g in gains}
    all_best = 0
    all_sec = 0.0
    for path in paths:
        segs = passes(path)
        if not segs:
            continue
        best = min(r[1] for _, rows in segs for r in rows)
        total_sec = sum(r[2] for _, rows in segs for r in rows)
        npasses = max(len(rows) for _, rows in segs)
        all_best += best
        all_sec += total_sec
        line = f"{os.path.basename(path)[:34]}	{len(segs)}/{npasses}	{best}	{total_sec:.0f}"
        for r in ratios:
            fewest_all, kept, stops = None, 0.0, []
            for lane, rows in segs:
                idx, fewest = replay(rows, r, min_passes, first_pass)
                kept += sum(x[2] for x in rows[: idx + 1])
                fewest_all = fewest if fewest_all is None else min(fewest_all, fewest)
                if idx < len(rows) - 1:
                    stops.append(f"{lane or 'default'}@{rows[idx][0]}")
            totals[r][0] += fewest_all
            totals[r][1] += kept
            mark = "" if fewest_all == best else "*"
            line += f"	{fewest_all}{mark}/{kept:.0f} [{','.join(stops)}]"
        for g in gains:
            fewest_all, kept, stops = None, 0.0, []
            for lane, rows in segs:
                idx, fewest = replay_gain(rows, g)
                kept += sum(x[2] for x in rows[: idx + 1])
                fewest_all = fewest if fewest_all is None else min(fewest_all, fewest)
                if idx < len(rows) - 1:
                    stops.append(f"{lane or 'default'}@{rows[idx][0]}")
            gtotals[g][0] += fewest_all
            gtotals[g][1] += kept
            mark = "" if fewest_all == best else "*"
            line += f"	{fewest_all}{mark}/{kept:.0f} [{','.join(stops)}]"
        print(line)
    tail = f"TOTAL\t\t{all_best}\t{all_sec:.0f}"
    for r in ratios:
        tail += f"\t{totals[r][0]} open/{totals[r][1]:.0f} s"
    for g in gains:
        tail += f"	{gtotals[g][0]} open/{gtotals[g][1]:.0f} s"
    print(tail)
    print("* = a lane reached fewer open after where this ratio stops it")
    print("lane_s and kept_s add the pass times of every lane (lanes run in parallel)")


if __name__ == "__main__":
    main(sys.argv[1:])
