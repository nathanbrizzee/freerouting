# Overhaul benchmark harness

Scripts used to produce the measurements in `docs/router-overhaul-summary.md` and
`docs/research/router-overhaul-progress.md`. They run a set of DSN boards with one jar, several boards at a
time, and print one line per board.

- `run-set.sh <label> <jar> [board.dsn ...]`: runs the boards one after the other with `--router.job_timeout`
  (`TIMEOUT`, default 30 minutes), `HEAP` (default 4g) and any `EXTRA` options; writes
  `runs/<label>/<board>.{log,ses,json,time}` and `summary.tsv`. A bare board name is looked up in `fixtures/`
  (`FIXTURES` overrides the folder); a path is used as it is.
- `run-batch.sh <label> <jar> [fast|hard|all]`: the 2-minute and 6-minute board groups, five runs at a time.
  The boards are expected in `boards/` next to the scripts (exports of the Antmicro designs and the synthetic
  board from `scripts/benchmark/gen_hard_board.py`).
- `pcbench.sh` with `pcbench_prep.py` and `pcbench_pick.txt`: the 24 PCBench boards.
- `export_dsn.py`: exports a `.kicad_pcb` to DSN with KiCad's pcbnew, unlocked tracks and vias
  removed; run it with KiCad's bundled Python.

Where the `run-batch.sh` boards come from (they are not committed; `boards/` is ignored): the
PCBench ones from `scripts/benchmark/fixtures/PCBench/<name>/processed.kicad_pcb` or
https://github.com/PCBench/PCBench (`PCBs/<name>`), through `export_dsn.py`;
`oskirby_logicbone_blind` with `dsn_blind.py <logicbone.dsn> <out.dsn> 0-2:450 0-5:500`;
`ddr5_testbed` from https://github.com/antmicro/ddr5-testbed and `sdi_mipi` from
https://github.com/antmicro/sdi-mipi-bridge-hw (the `.kicad_pcb` at the repository head), through
`export_dsn.py`; `synthetic/hard6f.dsn` from `gen_hard_board.py <out.dsn> 6 1 full`. On Windows,
`run-set.sh` falls back to bash's `time` when there is no `/usr/bin/time`; it cannot see the Java
process's CPU there, so read CPU from the job's own log line.
- `compare.py`, `capcurve.py`, `pcbench_table.py`, `summarize.py`: tables from the run folders.
- `dsn_blind.py`: adds blind and buried vias to a DSN's via rule, for the BGA tests.

Run external Python with `python3 -I`. At most about four JVMs of 4-6 GB fit on a 31 GB machine. Compare jars
side by side, not one after the other: times depend on machine load, step counts do not.
