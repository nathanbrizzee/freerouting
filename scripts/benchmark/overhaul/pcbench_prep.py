"""Exports the PCBench reference subset to DSN for router benchmarks.

Usage: python3 -I pcbench_prep.py <pick.txt> <PCBench dir> <out dir>

pick.txt has one board folder per line (relative to the PCBench dir, then tab-separated fields).
Each board's processed.kicad_pcb goes through export_dsn.py in its own process, which removes
the unlocked tracks and vias, so the DSN is the placed board with only locked copper. Board names
are reduced to letters, digits, '-' and '_'. Boards that fail to export are listed and skipped.
"""
import os
import re
import subprocess
import sys

pick, src, out = sys.argv[1:4]
here = os.path.dirname(os.path.abspath(__file__))
os.makedirs(out, exist_ok=True)
ok = failed = 0
for line in open(pick):
    folder = line.split("\t")[0].strip()
    if not folder:
        continue
    name = re.sub(r"[^A-Za-z0-9_-]+", "_", os.path.basename(folder))
    board = os.path.join(src, folder, "processed.kicad_pcb")
    dsn = os.path.join(out, name + ".dsn")
    try:
        r = subprocess.run(
            ["python3", "-I", os.path.join(here, "export_dsn.py"), board, dsn],
            capture_output=True, text=True, timeout=180)
        good = r.returncode == 0 and os.path.exists(dsn) and os.path.getsize(dsn) > 0
    except subprocess.TimeoutExpired:
        good = False
        r = None
    if good:
        ok += 1
        print("ok    ", name, (r.stdout.strip().splitlines() or [""])[-1])
    else:
        failed += 1
        tail = (r.stderr.strip().splitlines() or ["timeout"])[-1] if r else "timeout"
        print("FAILED", name, tail)
print(f"{ok} exported, {failed} failed")
