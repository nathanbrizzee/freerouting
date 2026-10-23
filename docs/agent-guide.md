# Freerouting agent guide

This guide is for an AI agent that runs Freerouting on a printed circuit board and reports the
result to a person. It covers how to start a run, which options to reach for, how to watch and stop
a run, and how to read the log and the result file. `java -jar freerouting.jar --agent-guide` prints
the guide of the jar you have; the full option reference is `docs/settings.md` in the source tree of
the same version.

## 1. What Freerouting does

Freerouting routes the connections of a board whose parts are already placed. The usual round trip:

1. In the EDA tool (KiCad: File > Export > Specctra DSN), export the board as a `.dsn` file.
2. Route it with Freerouting, which writes a Specctra session file (`.ses`) with the new traces and
   vias.
3. Import the `.ses` back into the EDA tool (KiCad: File > Import > Specctra Session), then run the
   tool's own design rule check.

Freerouting never moves parts, changes the board outline or edits fixed items. When a board cannot
be finished, the answer is usually a change only the designer can make (move a part, change a rule,
allow a via type), and the log says where.

Requirements: Java 25 or newer (`java -version`).

## 2. Starting a run

Headless (no window), the normal choice for an agent:

```
java -jar freerouting.jar -de board.dsn -do board.ses --gui.enabled=false \
  --router.job_timeout=00:30:00 \
  --router.result_json=board.result.json \
  --router.status_file=board.status.json \
  --router.stop_file=board.stop
```

With the window visible, so a person can watch it route (same files, same exit code, same log):

```
java -jar freerouting.jar -de board.dsn -do board.ses -dct 0 \
  --router.job_timeout=00:30:00 --router.result_json=board.result.json \
  --router.status_file=board.status.json --router.stop_file=board.stop
```

`-dct 0` skips the 5-second confirmation countdown; the window closes by itself after saving.

Rules that save trouble:

- Always pass `--router.job_timeout` (`HH:MM:SS`). The default is 12 hours, and a board that
  cannot be finished will use it.
- Always pass `--router.result_json`. It is the machine-readable answer; the log is the
  explanation.
- To end a run early, create the stop file. Killing the process loses the board; the stop file
  makes it save its best board, the result file and the report first.
- `-mt 0` turns off the optimizer (the stage that shortens traces and removes vias after
  everything is routed). Use it while you are still trying to finish a board; leave the optimizer
  on (the default) for the final run of a board that finishes.
- To continue from an earlier result instead of starting over, give both files:
  `-de board.dsn earlier.ses`. The earlier routes stay movable, so the router can rip them up.
- The log file goes to the platform's log folder (Windows `%LOCALAPPDATA%\freerouting\logs`,
  macOS `~/Library/Logs/freerouting`, Linux `~/.local/state/freerouting/logs`). Capture standard
  output too (`> board.log 2>&1`); it carries the same lines.

## 3. Profiles and when to change them

`--router.profile=<name>` picks a set of defaults; any option given on the command line still
overrides it. Profiles never set time limits, so a run is repeatable.

| Profile | Use it for | Notes |
|---|---|---|
| `standard` (default) | Every first run | Most complete board in the least time. |
| `thorough` | A board `standard` leaves open | Adds parallel alternative strategies on all cores and a fine-grid finisher that may lift other routes. More CPU; keep a time limit. |
| `quick` | A first look at where a board is stuck | No fallback stage, no optimizer. |
| `classic` | Comparing with the original router | Every newer option off. |

Recommended escalation:

1. `standard` with a 30-minute limit and `-mt 0`.
2. If the result is complete and clean, run again with the optimizer on (drop `-mt 0`), or keep
   the board as it is.
3. If connections stay open, read the verdict (section 6). When it says connections are held by
   crowded space, continue from the result with `thorough` and a 45- to 60-minute limit:
   `-de board.dsn board.ses --router.profile=thorough --router.job_timeout=01:00:00 -mt 0`.
4. If connections are still open, stop and report to the person (section 7). Running longer rarely
   helps once two runs end with the same open connections.

Options worth knowing (all `--router.` on the command line):

| Option | What it does | When to use it |
|---|---|---|
| `autorouter.pad_neck=true` | At the end, for connections still open, lets a trace narrow below the pad width near a pin whose neighbours sit too close for a full-width trace. Kept only if the board gets better. | Hints or `Pad necks:` lines show pins closed in by their own pads, often supply pins on fine-pitch parts. The log lists every neck used, for review against the current the net carries. |
| `min_trace_width_um=<um>` | Narrowest trace the router may create. The DSN does not carry it. | Set it to the board's minimum track width (KiCad: Board Setup, Constraints) whenever necking is possible. |
| `autorouter.force_via_in_pad=true` | Allows vias inside SMD pads even when the DSN forbids them (`attach off`). | Only if the board house builds filled and capped vias. Some boards finish only with it. |
| `autorouter.rip_history_cost=1` | Makes connections that keep ripping each other look for other paths. | The pass lines show the open count swinging between the same values late in the run. It hurts boards that are still converging. |
| `autorouter.grid_finisher=true` | Fine-grid search for each connection left open. On in `thorough`. | With `autorouter.grid_finisher_ripup=true` it may lift up to four other connections. |
| `max_search_steps=<n>` | Caps the search work of the whole job, independent of machine speed. | Repeatable comparisons; keep `job_timeout` as a safety net. |
| `fanout.strategy=planned` | Gives every SMD pin an escape via before routing. | Dense boards whose pins get closed in early; worse on roomy boards. |

## 4. Watching and stopping a run

The status file (JSON) is rewritten every 2 seconds and once at the end, atomically. It holds the
stage, the fallback attempt, the pass and its progress, the open count at the start, now and best,
a per-pass history, the time since the best result last improved and since the last routed
connection, and a rough low-high estimate of the time left. Poll it rather than the log.

A reasonable rule: if the best open count has not improved for about a third of the time spent so
far, and at least 10 minutes, the run has probably reached what it can do. Create the stop file;
the run saves its best board and writes the report.

Exit code: `0` when the job completed or timed out and the output file was written, `1`
otherwise. `final_state` in the result file says which: `COMPLETED` (the router stopped on its own,
or at the stop file), `TIMED_OUT` (the time or step limit stopped it), `CANCELLED` (stopped another
way, such as the window's cancel button), `INVALID` or `TERMINATED` (the input could not be read or
the job failed). `TIMED_OUT` with output written is a normal result, not an error.

## 5. The result file (`result_json`)

The fields an agent needs:

| Field | Meaning |
|---|---|
| `final_state`, `exit_code`, `output_written` | How the job ended (section 4). |
| `board_statistics.connections.incomplete_count` | Open connections. 0 means complete. |
| `board_statistics.clearance_violations` | `total_count`, and how many the router introduced (`router_introduced_count`) against those already in the design (`pre_existing_count`, `unfixable_count`). Pre-existing ones are the designer's to fix. |
| `phases.*.duration_seconds`, `cpu_seconds`, `resource_usage.cpu_time` | Wall and CPU time per stage and in total. |
| `settings_snapshot` | Every option the run used, including `profile`. |
| `open_connections` | One entry per open connection: `net`, `from` and `to` (component-pin), their positions in mm (`from_mm`, `to_mm`), `length_mm`, and for each pin end an `exit`. |
| `open_connections[].from_exit` / `to_exit` | `exit`: `free` (a trace of the net's width can leave the pin), `necked` (only the neck-down width fits), `closed` (nothing fits). `by_design: true` means the pin's own pads, fixed copper, a keepout or the board edge close it: no route reaches it as placed. `closed_by` names what is in the way. |
| `placement_hints` | Open ends grouped by component, most first, with a `hint` sentence: a design limit at the part's own pads, or other parts and routes closing it in. |
| `crowded_regions` | Up to five areas the open connections cross, ranked by copper fill times crossings: corner (`x_mm`, `y_mm`), `width_mm`, `height_mm`, `copper_fill` (0 to 1), `pins`, `components`, `open_connections_crossing`. |
| `verdict` | One sentence that splits the open connections three ways (section 6). |

## 6. Reading the log

Lines start with a timestamp, a level and, for job lines, a job id in brackets. The lines worth
reading, in the order they appear:

- `Router profile: <name>.` The profile in effect.
- `Design Warning: Board has N pre-existing clearance violation(s)` The design's own violations,
  before routing. Freerouting does not fix them; they stay in the final count.
- `Fanout pass #N ... completed ... with A SMD pins fanouted, B not routed` Escape traces and vias
  from SMD pins, before routing.
- `Auto-routing pass #N on board '...' was completed in T seconds with score S (U unrouted and V
  violations)` One line per routing pass. `U` falling pass after pass is progress; `U` swinging
  between the same values means connections are ripping each other.
- `Auto-routing pass #N: Search work: ...` and `... Rip-up: ...` How much searching the pass did,
  how much failed, and which net pairs rip each other.
- `Routing fallback ...` lines: when the pass loop stalls, other strategies are tried
  (`fanout-retry`, `short-escape`, `direct-first`, `open-escape`, `no-fanout`, seeds), and
  `Routing fallback chose '<name>'` says which board was kept.
- `Grid finisher: N open connection(s), T tried, C closed ...` The fine-grid finisher's result.
- `Pad necks: ...`, `Pad neck continuation: ... kept / not better` The pad-neck step's plan and
  result.
- `Job '...' finished with state: <STATE>` The end of the run.
- `Open connections (N): net: A - B; ...` Every connection still open.
- `Placement hint: <part>: ...` Up to ten parts with open ends and what closes them in.
- `Crowded region: (x1, y1) to (x2, y2) mm: copper fill P%, ... pins of <parts>; K open connections
  cross it` The most crowded areas the open connections cross.
- `Verdict: N connections open: A at a pin the design closes in ...; B at a pin that routes or
  nearby parts close in; C whose ends can be left ..., held by the space between them`
- `Pad neck used: <pin> (<net>) W mm trace on a P mm pad, class width C mm` Every narrowed trace
  in the saved board, for review.

What the verdict's three numbers mean, and what to do:

| Part of the verdict | Meaning | What helps |
|---|---|---|
| At a pin the design closes in | The pin's own pads, fixed copper, a keepout or the board edge leave no exit. No routing run will reach it. | A design change: pad or clearance rule, a narrower neck (`pad_neck`, `min_trace_width_um`), via-in-pad if allowed, or routing it by hand. Name the pin and what closes it (`closed_by`). |
| At a pin routes or nearby parts close in | Other copper or parts block the exit. | `thorough`, `pad_neck`, moving the named parts, or fanning the part out by hand. |
| Ends can be left, held by the space between them | The pins are reachable but the area between them is full. | `thorough` first; then moving parts out of the crowded regions, or another layer. |

## 7. Reporting to the person

Give the person what they need to decide the next step, in this order:

1. The outcome: complete or N open connections, clearance violations the router introduced (should
   be 0), wall and CPU time, and the profile.
2. The verdict sentence.
3. For each open connection: net, pins, and why it is open (exit `closed` by what, or crowded
   space). Group them by part, as the placement hints do.
4. The crowded regions with their coordinates in mm and the parts in them, as places to spread out.
5. Any `Pad neck used:` lines, which a person should check against the net's current.
6. What you recommend: another run (which profile or option, and why), or a design change.

Do not report the design's pre-existing clearance violations as routing errors.

## 8. Common mistakes

- No `job_timeout`: the run may take 12 hours on a board that cannot be finished.
- Killing the process instead of using the stop file: the board and the report are lost.
- Reading `TIMED_OUT` as failure: it is a normal end with the best board saved.
- Rerunning the same settings and expecting a different result: routing is deterministic, so the
  same board, options and limits give the same result. Change the profile or an option, or
  continue from the earlier `.ses`.
- Judging a run by the optimizer stage: completion (open connections) comes first; the optimizer
  only polishes a finished board.
- Treating `autorouter.force_via_in_pad=true` as free: via-in-pad changes what the board house must build;
  ask the person first.
