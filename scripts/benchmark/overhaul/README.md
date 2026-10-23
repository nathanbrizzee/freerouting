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
- `compare.py`, `capcurve.py`, `pcbench_table.py`, `summarize.py`: tables from the run folders.
- `dsn_blind.py`: adds blind and buried vias to a DSN's via rule, for the BGA tests.

Run external Python with `python3 -I`. At most about four JVMs of 4-6 GB fit on a 31 GB machine. Compare jars
side by side, not one after the other: times depend on machine load, step counts do not.
