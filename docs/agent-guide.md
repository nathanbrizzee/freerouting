# Freerouting agent guide

This guide is for an AI agent that runs Freerouting on a printed circuit board and reports the
result to a person. It covers how to start a run, which options to reach for, how to watch and stop
a run, and how to read the log and the result file. `java -jar freerouting.jar --agent-guide` prints
the guide of the jar you have and `--version` prints its version (neither opens the GUI); the full option reference is `docs/settings.md` in the source tree of
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
- Memory: a plain `java -jar` run is fine for one board at a time; Java then allows a heap of about
  a quarter of the machine's RAM. When you run several boards at once, give each its own limit
  (`java -Xmx6g -jar freerouting.jar ...`) so the total stays below the machine's RAM: the
  `standard` profile on boards of tens of thousands of connections needs 4 to 6 GB each, and
  eight such runs on a 31 GB machine were killed by the system. Four at a time is a safe
  ceiling there. `-Xmx` is a Java flag and goes before `-jar`. If a run ends with
  `OutOfMemoryError` in the log, raise it, or run fewer boards at once. If the optimizer runs out
  of memory, the log says `The optimizer ran out of memory in pass #N and was stopped` and the
  routed board is still saved; lower `--router.optimizer.max_threads` or
  `--router.optimizer.max_search_steps_per_connection` (default 1000000) or
  `--router.optimizer.max_search_steps_per_candidate` (default 4000000), or use `-mt 0`.
  A run whose heap limit is more than the physical memory free logs `Memory: the heap limit is X
  GB but only Y GB of physical memory is free ...` at the start: the machine can page or freeze
  before Java stops, so lower `-Xmx` or run fewer jobs. Parallel work is sized to what is free:
  the race runs on fewer threads, and the optimizer logs `Optimizer pass #N: K of M thread(s), as
  many candidates as the free memory holds.`
- The log file goes to the platform's log folder (Windows `%LOCALAPPDATA%\freerouting\logs`,
  macOS `~/Library/Logs/freerouting`, Linux `~/.local/state/freerouting/logs`). Capture standard
  output too (`> board.log 2>&1`); it carries the same lines.

## 3. Profiles and when to change them

`--router.profile=<name>` picks a set of defaults; any option given on the command line still
overrides it. Profiles never set time limits, so a run is repeatable.

| Profile | Use it for | Notes |
|---|---|---|
| `standard` (default) | Every run | Most complete board in one run: fails fast in the maze passes, races alternative strategies on the cores the memory allows, then a fine-grid finisher that may lift other routes, then the optimizer. `thorough` is accepted and runs this. |
| `quick` | A first look at where a board is stuck | No fallback stage, no optimizer. |
| `classic` | Comparing with the original router | Every newer option off. |

Recommended escalation:

1. `standard` with a 30-minute limit and `-mt 0`.
2. If the result is complete and clean, run again with the optimizer on (drop `-mt 0`), or keep
   the board as it is.
3. If connections stay open, read the verdict (section 6). When it says connections are held by
   crowded space, continue from the result with a 45- to 60-minute limit:
   `-de board.dsn board.ses --router.job_timeout=01:00:00 -mt 0`.
4. If connections are still open, stop and report to the person (section 7). Running longer rarely
   helps once two runs end with the same open connections.

Options worth knowing (all `--router.` on the command line):

| Option | What it does | When to use it |
|---|---|---|
| `autorouter.pad_neck=true` | At the end, for connections still open, lets a trace narrow below the pad width near a pin whose neighbours sit too close for a full-width trace. Kept only if the board gets better. | Hints or `Pad necks:` lines show pins closed in by their own pads, often supply pins on fine-pitch parts. The log lists every neck used, for review against the current the net carries. |
| `min_trace_width_um=<um>` | Narrowest trace the router may create. The DSN does not carry it. | Set it to the board's minimum track width (KiCad: Board Setup, Constraints) whenever necking is possible. |
| `autorouter.force_via_in_pad=true` | Allows vias inside SMD pads even when the DSN forbids them (`attach off`). | Only if the board house builds filled and capped vias. Some boards finish only with it. |
| `autorouter.rip_history_cost=1` | Makes connections that keep ripping each other look for other paths. | The pass lines show the open count swinging between the same values late in the run. It hurts boards that are still converging. |
| `autorouter.grid_finisher=true` | Fine-grid search for each connection left open. On in `standard`. | With `autorouter.grid_finisher_ripup=true` it may lift up to four other connections. |
| `max_search_steps=<n>` | Caps the search work of the whole job, independent of machine speed. | Repeatable comparisons; keep `job_timeout` as a safety net. |
| `fanout.strategy=planned` | Gives every SMD pin an escape via before routing. | Dense boards whose pins get closed in early; worse on roomy boards. |
| `autorouter.differential_pairs=off` | On by default: pairs declared in the DSN (`(pair (nets P N (gap g) (skew s)))`, both optional, in the file's units) or found by name (`_P/_N`, `D+/D-`; a bare `P/N` suffix needs `dsn_pairs.py`) are routed first as two coupled traces, with a via pair at an end whose pads are on another layer, held fixed until the last stage. | Turn it off to compare, or when a name-detected pair is not really a pair. A pair that cannot be coupled is left to the ordinary router; the `Pair ...: left to the router: ...` line says why. |
| `autorouter.pair_max_skew_um=<um>` | Largest length difference within a routed pair; the shorter member gets length bumps until it matches. Default 100 um, -1 off. A pair's own `(skew s)` in the DSN overrides it for that pair. | Set it to the interface's skew allowance; give a looser pair (Ethernet) its own `(skew s)` instead of raising it for all. `, tuned from S mm` on a routed line means bumps were added; `not tuned: no room ...` means the skew stayed. |
| `autorouter.pair_max_detour=<ratio>` | Longest coupled route of a pair as a multiple of the direct distance between its ends. Default 2.5, below 1 off. A route over 1.5 times is compared with the other layers; the shortest wins, and if it is still over the limit the pair goes to the ordinary router. | A pair whose line says `lengths 45 and 43 mm` for pads 10 mm apart. The line then ends `N times the direct distance, the shortest of the layers tried`, or `left to the router: the shortest coupled route is ...`. |
| `autorouter.pair_reroute=<count>` | Most pairs ripped and routed again as a unit, nearest the open connections first, when connections are still open after routing. Default 4, -1 off. A try is kept only when the pair stays coupled, fewer connections are open and no violation is new. | Connections left open beside a coupled pair. Each try logs `Pair re-route <pair>: N open before, M after ...; kept.` or `not better, the board before it is kept.` |
| `autorouter.pair_return_via_mm=<mm>` | Ground return vias beside each via pair of a coupled pair, at most this far from each signal via (1.27 is the common rule). Default off. Needs a plane named GND (or containing GND). | High-speed links (PCIe, USB 3) on a board with room: the pair stage logs `Return vias: N of M placed ...`. They cost completion on a crowded board (CM5: 13 open against 9). |
| `autorouter.pair_layer_hold_passes=<passes>` | Passes in which a pair member left to the ordinary router keeps to its partner's layers; the optimizer always holds, and after routing a split pair's member is routed again on its partner's layers. Default 2, -1 off (hold and repair). | A line `Pair <name>: the members are on different layers (...)` after routing names a pair whose members split even after the repair; route it by hand or move what blocks the shared layer. |
| `autorouter.pair_fanout=keep` | By default a pair lifts the movable fanout already on its pins and leaves the pads itself; the fanout comes back if the pair fails. | Keep fanout placed by hand on purpose, or lock it in KiCad (locked copper is never lifted). |
| `autorouter.pair_gap_um=<um>` | Gap between the members of a pair that declares none. Default: the clearance between the two nets. | Set it to the board's differential pair gap (KiCad: net class, Differential pair gap), which a KiCad DSN does not carry. Better: add the pairs, their gap and their width to the DSN first with `python3 scripts/kicad/dsn_pairs.py board.kicad_pcb in.dsn out.dsn`, which reads them from the net classes in the `.kicad_pro` next to the board, and pass the `--router.min_trace_width_um=<um>` it prints. |

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
  before routing. Freerouting does not fix them; they stay in the final count. A line `fixed
  traces/vias inside keepouts` counts copper held from the run (fixed) under a keepout: usually a
  keepout drawn for the nets being routed over vias of held nets. Nothing routes into either, so
  these do not hold the run back.
- `Fanout pass #N ... completed ... with A SMD pins fanouted, B not routed` Escape traces and vias
  from SMD pins, before routing.
- `Auto-routing pass #N on board '...' was completed in T seconds with score S (U unrouted and V
  violations)` One line per routing pass. `U` falling pass after pass is progress; `U` swinging
  between the same values means connections are ripping each other.
- `Auto-routing pass #N: Search work: ...` and `... Rip-up: ...` How much searching the pass did,
  how much failed, and which net pairs rip each other.
- `Differential pairs: N found, M of K pair connection(s) routed as coupled traces.` and one `Pair
  <name> (<pin> to <pin>): routed on layer L, lengths A and B mm, skew S mm (target T mm)` or `... left to the
  router: <reason>` line per pair connection. A routed line ends with `, tuned from S mm` when the shorter
  member got length bumps, and with `, replacing N fanout item(s)` when the pair took the place of
  fanout already on its pins. With `pair_return_via_mm` set, `Return vias: N of M placed beside K
  via pair(s) ...` follows.
- `Pair re-route <pair>: N open before, M after with the pair coupled again, target T mm, K new violation(s);
  kept.` After routing, when connections are still open, the pair nearest them was ripped and
  routed again around them; the pair's new route lines follow, indented. `not better, the board
  before it is kept.` means the try changed nothing; `coupled again but S mm skew, over the T mm
  target` means the pair could not be length matched again, so it kept its old route; a kept try
  says `coupled again, target T mm`.
- `Pair <name>: <member> moved to the layers of <partner> (<layers>): N open before, M after, now
  on <layers>, K new violation(s); kept.` The members of a pair had ended on different layers and
  one was routed again on the other's layers; `not better, the board before it is kept.` when that
  would have opened a connection or added a violation.
- `Pair <name>: the members are on different layers (P on ..., N on ...)` A pair whose members
  are still on different layers after the repair, which breaks impedance matching.
- `Pin <part>-<pin> is closed in by copper the router does not move (pads, fixed copper, keepouts);
  its later searches are skipped.` No trace can leave that pin, even with every route ripped, and
  with vias in pads (`via_in_pad`, which a board with its own vias in pads turns on) no via of its
  net fits in the pad either; the connection stays open. Move the part or the fixed copper, or fan
  the pin out by hand.
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
| At a pin routes or nearby parts close in | Other copper or parts block the exit. | Continuing from the result, `pad_neck`, moving the named parts, or fanning the part out by hand. |
| Ends can be left, held by the space between them | The pins are reachable but the area between them is full. | Continuing from the result first; then moving parts out of the crowded regions, or another layer. |

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
- Starting many runs at once without `-Xmx`: each may grow to a quarter of the RAM, and the system
  kills the processes. Cap each run and start fewer.
- Treating `autorouter.force_via_in_pad=true` as free: via-in-pad changes what the board house must build;
  ask the person first.
