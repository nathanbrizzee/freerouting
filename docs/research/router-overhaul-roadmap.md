# Router overhaul roadmap

Status: in progress on branch `router-overhaul` of the fork (branched from `perf-fixes`, which is upstream master
`5aa1b24` plus the logging fix). Started 2026-10-19. Each fix is its own commit, so the history shows the route from
the baseline to the end state. Code paths are relative to `src/main/java/app/freerouting/`.

Goal, from the user: speed and completion. Simple and medium boards must not take hours, and the router must finish
every connection without leaving danglers. AI agents should be able to steer the router through APIs and read its
output.

Why it matters, from the user (2026-10-19): routing their four boards took over a week, and about 90% of that time was
waiting for the router, which often spun for hours or got stuck. Modern routers, and even OrCAD's old grid router,
finish such boards far faster. The measure of success is wall time to a finished, clean board, not points of score.

## 1. Inputs

- **Chat AI answers.** Four chat AIs answered the question "how can Freerouting compete with proprietary routers,
  and would a grid router help". Their main proposals were a hybrid grid and shape router, global routing with
  negotiated congestion (PathFinder), smarter rip-up with memory, a dedicated escape solver, parallel routing, a
  native rewrite, and AI for net ordering and strategy choice. Section 3 checks each against the code.
- **Hardware board routing brief.** Written by the agent that routes the user's four boards (6-layer, high-voltage
  rules). Condensed with answers in `hardware-board-routing-brief.md`.
- **Upstream research in this folder.** In particular `large-board-autorouter-performance.md`, which records the
  parallel and corridor experiments upstream measured and removed, and `escape_congestion_research.md`.
- **The fork's benchmark.** Issue070 (2 layers, 688 nets), fallbacks off: fanout escaped 44.6% of pins. Pass 1 took
  74 s and allocated 76 GB; passes 2-21 took about 3 s each and recovered 7 connections. The run ended with 90
  unrouted, 51 of them GND, on a board with no planes. With fallbacks on, the job took 10.5 minutes, 7 of them in
  fallback, for 3 more connections.
- **A code read** of the search, the pass loop, geometry, rules, the API and integrations, with the main claims
  checked line by line.

## 2. Conclusions

1. **The routing loop has bugs that cost completion and leave danglers.** These are cheap to fix and come first.
2. **The search rebuilds its rooms and via sites for every connection** (reuse exists but is off by default), and
   start rooms begin board-sized. An earlier draft said every room did whole-board work; that holds only for the
   free-angle tree. The default 45-degree tree prunes as the room shrinks (`board/searchtree/ShapeSearchTree45Degree.java:148-219`),
   so the remaining wins there are smaller and need a profile before they are ranked (section 11).
3. **Rip-up has no memory and destroys its own work.** That is why later passes are worse than pass 1.
4. **The fallback stage reruns the whole board up to five times.** Fallbacks should work on the leftovers only.
5. **Agents can only submit a whole board and wait.** Region- and net-limited routing, graceful stop, structured
   output and a session API turn Freerouting into a routing kernel an agent can steer.
6. **A grid router helps in two specific places, not as a replacement** (section 5).
7. **Stay on Java** (section 9).
8. **Every change must work for every kind of board** (section 2.1). The user's boards carry high-voltage buses, but
   most Freerouting users route dense low-voltage boards, and the changes are meant for PCB routing in general.

### 2.1 Every kind of board: dense low-voltage and high-voltage

**The user's direction (2026-10-19):** "These are my 4 boards that have high voltage on them. Other people route low
voltage high density boards and they need to work also. Whatever changes we make need to work for all types of boards
and for the greater good of PCB routing in general." The user also noted that the original author does not seem to
have planned for buses of 72 V, 120 V or even 1000 V, which need much wider gaps to keep from arcing.

**Rules for every change:**
- **No special cases for one kind of board.** Nothing in the router knows about voltage. Spacing comes only from the
  design rules: net-class clearance, `class_class` pairs and per-layer rules. A high-voltage board is a board with
  some very large clearances, and a dense board is one with small clearances and fine pitch. A change that helps one
  by hard-coding for it is rejected.
- **Two benchmark groups, both measured for every routing change.** A change is accepted only when neither group gets
  worse; a trade-off between them goes to the user.
  - *Dense low-voltage* (upstream fixtures): DAC2020 bm01 and bm05, FastTest, Issue070 (688 nets, 2 layers), the
    Coldfire kit (Issue690) and a tomu FPGA board (Issue756) for fine-pitch BGA escape.
  - *Stretch tests, from the user:* the S6 and L6 boards on **4 layers**. Both went to 6 layers only because
    the router could not finish them on 4: wide traces cut the board into pockets, and the user could see a path a
    grid router would have found, while the shape router got stuck again and again. Finishing either on 4 layers is
    the clearest sign the overhaul works. They are measured at milestones, not on every commit.
  - *High-voltage*: four private high-voltage boards, labelled L6, S6, P4 and M4 (KiCad 10; described in section 1 of the brief;
    not public, so only their rules and lessons are recorded here; exported
    to DSN with private benchmark scripts that add the voltage-group rules) and the PCBench `Box0-hv-analog-breakout` fixture.
- **Each new engine part takes its spacing from the same clearance matrix** as the maze search: the fine-grid
  finisher, the global planner's capacity model, shove and escape planning. A grid sized for 0.2 mm gaps must not
  assume every gap is small, so the finisher's cell size and the planner's capacity both come from the rules of the
  nets involved.

**What high voltage needs from a router, and where it belongs:**

| Need | In Freerouting today | Plan |
|---|---|---|
| Wide gaps between voltage groups, for every item kind | `class_class` reached traces only, not vias or pads, until phase 1.2 | Done in phase 1.2. The user's pipeline grew via padstacks as a workaround; it can be dropped after a check on their boards. |
| Gaps that differ by layer (IPC-2221B allows less on inner layers than on uncoated outer ones) | The DSN parser reads layer-dependent rules in the structure and in net classes, and the clearance matrix is per layer. Not yet verified end to end, and KiCad's exporter does not write them. | A test board in phase 1, then document how an agent writes them into the DSN. This is often the difference between fitting and not fitting on a dense HV card. |
| Speed with very large clearances | Each clearance class gets its own compensated search tree, and wide gaps shrink every free-space room. | Profile a high-voltage board in phase 2.2 next to the dense ones, so a fix for one does not slow the other. |
| Creepage (distance along the board surface, slots that lengthen it) | Not a routing concept here; KiCad 10's DRC checks it | Out of scope for routing. KiCad DRC stays the final check (phase 0). |
| Voltage to clearance (an IPC table) | Not Freerouting's job | The design tool or the agent sets net classes and `class_class` rules. The agent guide (section 6.1b) shows how to express voltage groups. |

## 3. The chat AI claims, checked against the code

| Claim | Finding |
|---|---|
| v2.0.0 introduced a C++/Qt port | False. The only port is freerouting.net, in C# by Erik Gavriluk. Its fixes were ported back into Java (`docs/migrating_Eriks_improvements_part_2.md`). |
| A Rust port runs 4-5x faster | Not verifiable here. Upstream's GraalVM native image measured about 3% faster than the JVM, with 65% less heap. The waste is algorithmic, and a port would carry it along. |
| Coarse corridor, then the detailed maze | Upstream built it and removed it. Every measured slice gave the same SES, for a few tenths of a second saved. A clip does not change which net gets which space; only negotiation does (section 7). |
| Route nets in parallel | Upstream measured snapshot search (slower than serial) and spatial bins (worse score), and removed both. Parallelism fits the global router (section 7). |
| Use A* with a good heuristic | The search is already A*-like, but the heuristic is weak and not admissible (`autoroute/maze/DestinationDistance.java:102-293`). Per-layer-pair via costs are always 0 (`AutorouteControl.java:177-181`). |
| Use R-trees | There is a bounding-volume tree (`datastructures/MinAreaTree.java`). The problem is its use: board-sized queries, all layers at once, one copy per clearance class that is never freed. |
| Negotiated congestion | Absent. No history or present-congestion cost anywhere (`autoroute/RetryNeighbourhood.java:21-23`). |
| Grid fallback for the last nets | Absent; no grid router exists. The five fallbacks are parameter retries of the same maze (`autoroute/pipeline/RoutingFallback.java:56-97`). |
| A dedicated escape solver | Agreed. Fanout is pin by pin, with a fixed 2.5-4.5 mm via annulus, and oscillates. |
| An agent drives it through the API | The MCP server only mirrors the job API. There is no net- or region-level control. |
| Use AI or learning | Later. It needs logged per-connection outcomes first (section 8 makes them). |

## 4. Bugs and causes found in the code

| Problem | Cause | Planned fix |
|---|---|---|
| Danglers on incomplete boards | Final tail and via cleanup is skipped whenever the router stops itself: every stagnation, no-improvement or max-passes stop sets the stop flag first (`AutorouteBatchLoop.java:661` against `:747-752`). The cleanup is a single sweep. | Run it on every exit and repeat until nothing changes. Never neck below the minimum width (phase 1.7e: `router.min_trace_width_um`, opt-in, because DSN files do not carry the minimum). |
| Router vias ignore `class_class` | A rule without a type list sets only the matrix entry named after the net class (`io/specctra/parser/Network.java:656-663`); via and pad sub-classes miss it. Likely cause; a test board will prove it. | Propagate the rule to the sub-classes. Rebuild via infos after class pairs are read. |
| Later passes worse than pass 1 | A strict-DRC rejection (from pass 3) leaves its victims ripped (`AutorouteConnectionRouter.java:299-314`). A partial insert does the same (`FoundConnectionInserter.java:97-106`). A 2-pin net that fails twice rips its whole self (`AutoroutePassRunner.java:175-180`). | Restoring the victims was tried and routed worse (section 12, phase 1.4): the damage was acting as the only perturbation. Revisit with negotiated congestion or shove. |
| Rip-up loops | The randomization meant to break them never runs: `ripupPassNo` stays 1 (`AutorouteControl.java:189`, read at `MazeRipupResolver.java:158`). | Wire it to the pass number, then measure. |
| Stagnation fires on real progress on big boards | On a board with more than about 1300 connections, closing one connection moves the score by less than the 0.5 threshold (`BatchAutorouter.java:64`). | Judge stagnation on unrouted count and violations. |
| Hangs | Via-site splitting has no cap and does not see the time limit (`geometry/planar/PolylineArea.java:168-204`). A failed polygon split is not cached and returns null, so it repeats and logs again (`PolygonShape.java:552-613`). A shape that fails to split disappears from the index. | Work cap and time limit; cache the failure; treat the shape as an obstacle. |
| SMD pin will not join a plane | Plane layers are inactive for routing, so plane targets are skipped (`AutorouteControl.java:155-161`, `MazeSearchEngine.java:654`). | A plane layer is a target for its own net only. |
| Will not join a fixed track | Joining needs a split, and fixed traces refuse it (`board/trace/PolylineTrace.java:727`). | Topology-only split; both halves stay fixed. |
| Overlapping same-net pads not connected | `Pin.java:771`, `DrillItem.java:333` | Checked 2026-10-19 (phase 1.7c): upstream already connects overlapping pads of different parts. A via or track end inside a pad but off its centre is still not a contact; the router adds a short stub inside the pad, which is harmless, so this is left. The real defect was a fixed same-net via in an SMD pad counted as a clearance violation (fixed). |
| Wire crossing the board bounds dropped | `Wiring.java:498-509` | Keep it. Fixed 2026-10-19 (phase 1.7d). |
| Preferred direction has no effect | It biases the room sequence only, not the final geometry (section 6, question 13 of the brief). | Later phase. |
| False clearance violations | Octagon geometry and binary-search DRC (`Item.java:446-563`). | Exact circle and segment distance, in a later phase. |
| Possible hidden cost in every headless run | Every board-updated event rebuilds the whole SES in memory (`RoutingJobSchedulerActionThread.java:127`, `:358-369`). The event fires up to four times a second during a pass (`BatchAutorouter.shouldFireBoardUpdate`). Not yet measured. | Profile in phase 2.2. Build the SES only when it is asked for, at the end, and when the best result improves. |
| The autorouter rips where KiCad would shove | The maze can shove a trace only when it has the same half-width and the same clearance class as the net being routed (`autoroute/maze/MazeTraceShover.java:27-31`), only through a door that is not small and into a thick room (`MazeSearchEngine.java:524-530`). On a board that mixes power widths, high-voltage classes and signals the test almost never passes, so the search falls back to rip-up, which destroys work. The interactive shover (`board/optimize/TraceShover.java`) is not limited this way. Locked traces are fixed and must stay unshoveable. | Phase 2.4, moved up (see section 8): shove any unfixed trace or via, of any width and class, with clearances from the clearance matrix, before ripping. Constraints, from the user (2026-10-19: "impedance matched traces need to stay the same length when a push and shove moves them"): (a) fixed and locked copper never moves; (b) a net with a length rule (DSN min/max length, already parsed into `rules/NetLengthConstraint`) may be shoved or rerouted only if its length stays inside the window, rechecked after the move, otherwise the move is rejected; (c) impedance comes from width and spacing to the reference plane, so a shove never changes width or layer, and never necks a controlled net; (d) Freerouting has no differential-pair model, so a net in a pair or matched group is not shoved unless both members move together, which needs a pair rule; until then such nets are locked or marked no-shove. The same rule (b) applies to rip-up: today the autorouter ignores length rules (only DRC and interactive routing check them), so ripping and rerouting a tuned net can already break it. |
| KiCad pours look like solid copper to Freerouting | KiCad exports each zone as a `(plane ...)` polygon with the zone outline, not the filled copper. Every other-net pad inside the outline then counts as a pre-existing clearance violation: 192 on StickHub, 845-991 on the private high-voltage boards ("pin-to-keepout" in the load warning). Pours are not obstacles by default (`plane_as_obstacle=false`), so routes may cross them and KiCad's refill flows around those tracks. Likely, to verify: a pin inside a pour outline counts as connected to it even where the real fill will not reach after routing cuts it. | Exclude pre-existing violations from scores and comparisons. Check pour connectivity against KiCad DRC after refill (phase 0 oracle). If it is false, count a pour only as a connection target, not as copper that already connects. |
| GUI freezes for seconds on zoom or layer toggle while routing a large board (the user, on the L6 board, every time) | Not a lock: painting takes no board lock (checked). Every repaint copies all items and loops priority x draw step x all items, about 10-16 passes over every item on 6 layers, with no cached image (`gui/rendering/BoardRenderer.java:42-78`); pad and via bounding boxes are rebuilt per call (`DrillItem.java:191-199`). The router asks for a full repaint up to four times a second (`WorkspacePortAdapter.publishProgress`, through `runOnEdt`), so a paint longer than 250 ms keeps the event queue full and a zoom waits behind it. The GUI worker also rebuilds the whole SES on every board update, on the router thread (`GuiRoutingJobWorker.java:244-256`). | Phase 2.12: (1) at most one pending repaint, about 1 per second while routing, and none when only the status text changed; (2) draw the board into a cached image and blit it on zoom or layer change, redrawing in the background and showing the scaled old image meanwhile; (3) select items through the spatial index for the clip box instead of scanning all of them per step, and cache drill bounding boxes; (4) build the SES only at pass end or on request. Measure first: time one render of the L6 board into an off-screen image. |
| Layer toggles lag and some do nothing (the user, bm01 in the GUI under WSLg, 2026-10-20) | Zoom works, but switching layers on and off in the display settings does not catch up with the display while routing, and switching the silkscreen or fab layers changes nothing: only copper is drawn. Not yet traced: whether the DSN carries those layers at all (the KiCad export may not), or the toggles change a setting the renderer does not read. Also, while routing, the toolbar's zoom buttons are disabled (scroll bars and the mouse wheel still work): the toolbar disables every button while the board is read-only, view-only ones included (`gui/board/BoardToolbar.java:489-492`). | Phase 2.12, with the repaint work above: trace the layer-visibility path from the checkbox to `BoardRenderer`; hide toggles for layers the board has no items on; keep view-only controls (zoom, fit, display settings) enabled while routing and disable only the editing ones. |
| `save_intermediate_stages` does nothing | `fireBoardSnapshotEvent` has no listener anywhere (`NamedAlgorithm.java:83-92`). | Replace it with the best-session write (section 6.1a). |

## 5. Would a grid router help?

Three possible roles, three different answers.

### 5.1 A grid router instead of the shape router: no

- **Pins.** Fine-pitch pins are off any practical grid. At 0.5 mm pitch with 0.2 mm rules, a 0.25 mm grid cannot
  reach every pin, and a 0.05 mm grid on the 404 x 286 mm L6 board is about 46 million cells per layer.
- **The real problem.** Grid search is cheaper per step, but the expensive part of this router is not the step. It
  is whole-board room building (section 2), destructive rip-up and whole-board fallbacks. A grid router without
  negotiation has the same order and congestion problem.
- **Geometry.** Results come out jagged and need conversion to shapes and an exact check anyway.

### 5.2 A coarse grid as the global planner: yes, with negotiation

This is the grid-first idea done the way that works. Section 7.1 describes it.

- **What it is.** One- to two-millimetre cells per layer, capacity from free width, every net routed on the cell
  graph, then PathFinder negotiation so nets compete for narrow channels before any copper goes down.
- **What it gives.** Ordering and layer assignment, so the detailed router does less rip-up and fewer passes. Upstream's
  corridor experiment shows that a corridor without negotiation saves only search time on easy nets. The gain comes
  from the negotiation, not from the grid.
- **Speed.** A grid this coarse is small: a 100 x 100 mm 4-layer board at 1 mm is 40,000 nodes.

### 5.3 A fine local grid as a finisher for the last connections: yes, and early

This is the role with direct evidence. The user's Python grid router (`pcb_link.py`: 0.25 mm grid, A*, preferred
direction per layer, exact segment checks) closed every connection Freerouting left open across five full L6 board
runs, 1-50 s per link, including 60-97 mm runs on nearly empty inner layers.

- **How it works.** For each open connection after the maze passes, take a window around the gap. Build a fine grid
  in it (a fraction of the minimum pitch, so about 0.05 mm) from the real clearance matrix and lock state. Run A*
  with layer costs and bounded rip-up inside the window. Convert to traces and vias. Check exactly, and keep the
  result only if it adds no violation.
- **Speed.** A 20 x 20 mm window at 0.05 mm is 400 x 400 cells per layer, and a search takes milliseconds to a
  second.
- **The time it saves is the tail.** Today the maze spends pass after pass on the last few percent: Issue070 passes
  2-21, and upstream's Aleste and DSP boards spend tens of minutes on the last connections. A finisher lets the maze
  stop after a few passes and hands the rest to a router that is good at small local problems. It also removes the
  user's second router and its SES round trip.
- **Order.** First fix the bugs that cause leftovers (rip-up damage, plane targets, fixed-copper targets), since some
  leftovers will disappear. Then add the finisher. Its link-order problem (links taking lanes from neighbouring pins)
  goes away when it sees the same rules and locks as the main router, and when escapes are planned first (section 7.3).

## 6. Agent control and output

### 6.1 Graceful stop

Two stop levels already exist in the code: `requestStop()` (whole job) and `requestStopAutoRouter()` (routing stage
only, `autoroute/pipeline/AutorouteBatchLoop.java:751`). The REST API exposes only cancel. Add:

- `PUT /v1/jobs/{id}/finish`. Stop after the current connection, run tail cleanup, restore the best board, write the
  output and report `COMPLETED_EARLY`. Cancel stays the abort.
- CLI: on SIGTERM or Ctrl-C, do the same and write the SES. Today a killed run loses everything.
- Write the best session each time the best result improves (brief P6), so a killed process still leaves the best
  pass on disk.

### 6.1a Live status, an estimated finish time and an agent-triggered stop

**Why.** The user's routing agent runs the Freerouting CLI and can only wait for it to exit. It waited up to 2 hours
on typical boards and once 7 hours before it could send the result to KiCad. During that time it cannot tell
whether the run is progressing, stuck on one connection, or rerunning the board in the fallback stage, and it
cannot stop the run without losing everything, because the session is written only at the end. The default
`router.job_timeout` is 12 hours.

**What exists today:**
- one prose log line per pass, so nothing until a pass ends;
- `GET /v1/jobs/{id}` with state and pass number, but only in server mode;
- an SSE partial-output stream, also server mode only;
- the result manifest (`--router.result_json`), only at the end.

**What to build.** It is new code beside the router and does not change routes.

1. `--router.status_file=<path>`: a JSON file rewritten atomically (temp file plus rename) every few seconds and at
   every pass and stage boundary. Contents:
   - stage and pass number;
   - items done and total in the current pass, and the current net;
   - unrouted at start, now and best so far, and violations;
   - unrouted per pass so far, so the trend is visible;
   - elapsed time, estimated time left as a low-high range with a confidence label;
   - seconds since the best result last improved, and since the last routed connection;
   - path of the best session written so far;
   - the stop reason once finished.
2. **Best session on improvement.** Write the SES whenever the best result improves (brief P6), so a killed run still
   leaves the best board on disk.
3. **`--router.stop_file=<path>`** (and `PUT /v1/jobs/{id}/finish` for the API). When the file appears, Freerouting:
   - finishes the current connection;
   - skips the remaining passes and fallbacks;
   - removes danglers (phase 1.1 makes that run on every exit);
   - restores the best board, writes the SES and exits normally.
4. **`events.ndjson`**: one JSON line per pass, fallback attempt and stage, for agents that want the history (section 6.4).

**How the estimate works.** It is rough by nature, so it is reported as a range:
- **Rest of the current pass:** items left times the measured time per item in this pass. Fairly good.
- **Remaining passes:** bounded, because the stop rules are fixed. The loop stops after 10 passes without a 0.5-point
  gain, and restore checks run from pass 8 on every 4th pass (`AutorouteBatchLoop.java:367-413, 492-599`). Recent pass
  times give the low end; the pass ceiling gives the high end.
- **Fallback stage:** up to five attempts, each about as long as the default run, so it can be estimated as soon as the
  stage starts. On long runs this is the largest hidden cost.
- **Stuck rather than slow:** "time since the last routed connection" tells the agent that one hard connection is
  holding the pass, which no estimate can predict.

**When.** Phase 2, step 10. The user chose to wait for the full set of fixes and test the finished router on the
other machine, rather than ship this alone. Until then, an agent can bound the run with
`--router.autorouter.fallback_strategies=none` (Issue070: 10.5 minutes down to 2.4, for 3 more unrouted),
`--router.job_timeout=00:45:00`, and `-mp N`.

### 6.1b An agent guide that ships with the jar

**Why.** An agent that drives Freerouting needs to know every capability and how to call it, and an agent on another
machine may have only the jar, not this repository. `AGENTS.md` is for coding agents working on the source, so it
is the wrong place.

**What to build:**
- `docs/AGENT_GUIDE.md` as the source of truth. It covers:
  - what Freerouting does;
  - recommended command lines per situation;
  - every agent-relevant flag (status file, stop file, net and region routing, result JSON);
  - the status JSON fields;
  - how to read the estimate and when to stop a run;
  - exit codes and output files;
  - the REST and MCP equivalents.
- The same file bundled in the jar as a resource and printed by `java -jar freerouting.jar --agent-guide`. The first
  line of `--help` points to it.
- `llms.txt` at the repo root, a short pointer to the guide.
- An MCP tool and a REST endpoint that return the guide.
- A unit test that fails when a flag named in the guide does not exist in the settings, so the guide cannot drift.

**How agents discover it,** by the way they meet Freerouting:

| Entry point | Pointer |
|---|---|
| Browsing the repo | A "For AI agents and scripts" section in the README, after the MCP section; `llms.txt` at the root; one line in `AGENTS.md`: "Using Freerouting rather than developing it? See docs/AGENT_GUIDE.md" |
| Only the jar | First line of `--help`; `--agent-guide` prints the full guide; one startup log line in headless runs; `AGENT_GUIDE.md` next to the jar in the release zip and installers |
| A bad or missing argument | The usage message ends with the same pointer |
| MCP | The `instructions` field of the `initialize` reply, which clients pass to the model, plus a `get_agent_guide` tool |
| REST | `GET /v1/agent-guide`, linked from the OpenAPI description |

**Human documentation.** The README is upstream's general one, and the existing human docs are reference lists
(`docs/command_line_arguments.md`, `docs/settings.md`). Add:

- **`docs/headless-and-automation.md`**, a narrative guide:
  - running headless (WSL, remote sessions, CI);
  - recommended settings by board size;
  - reading the status file and the estimate, and when to stop;
  - the stop file;
  - where the best session goes;
  - script examples in bash, PowerShell and Python.
- **New flags in `docs/command_line_arguments.md`**, added in the same commit as each feature.
- **A README pointer** to the human guide, beside the agent one.
- **A one-line fork note at the top of the README**, linking to this roadmap. It stays in the fork and is not
  upstreamed.

The drift test covers every `--router.*` flag named in the agent guide, the human guide and
`command_line_arguments.md`.

**When.** Phase 2, with step 10. Each later feature updates the guide in the same commit.

### 6.2 Changing rules mid-run: only at pass boundaries, and only strategy settings

Changing clearance or width rules while a search runs is the wrong design. Rules feed the clearance matrix, the
per-class compensated search trees and the via infos. Changing them invalidates all of that, existing copper may
become a violation, and determinism goes. What works instead:

- **Pause, patch, resume at a pass boundary** for strategy settings: rip-up cost, pass limits, which nets or region to
  route, locks, net order and the finisher on or off. These are read per pass or per connection, so changing them
  between passes is safe and deterministic, given the same patch sequence.
- **Rule changes as restart from a snapshot.** Snapshot the board, apply the new rules and start a new job seeded
  with the snapshot. The API already accepts `initial_session`, so this is cheap, and the agent sees it as one call.

### 6.3 Session API and MCP tools

A long-lived board session in one JVM, so there is no 6 s start and DSN reload per window (brief section 3.1).

| Tool | Purpose |
|---|---|
| `board_load`, `board_summary` | Layers, classes, nets, open count, violations |
| `list_unrouted` | Net, endpoints, airline length, last failure reason, blockers |
| `route` | Nets, connections or a region, with a work budget; returns what changed |
| `rip`, `lock`, `unlock` | By net, item ids or region |
| `add_keepout`, `set_strategy` | Session-only keepouts and strategy settings (section 6.2) |
| `congestion` | Grid heat map per layer |
| `drc` | Clearance report |
| `snapshot`, `restore`, `diff` | Try something, compare, roll back |
| `finish`, `cancel` | Section 6.1 |
| `export` | SES, KiCad JSON, or write-back through the KiCad IPC bridge |

The engine side (route only these nets or this region, locks, rip by region, failure diagnosis) also serves the
fallback stage and the finisher. It is built in phase 2.

### 6.4 Sub-logs and machine-readable output

Today there is one text log: Log4j2 with one named logger and a configuration built in code
(`logger/Log4j2ConfigurationFactory.java`), plus a result manifest at the end (`--router.result_json`). An agent has
to scan regexes over prose, as the brief's process manager does. Proposed per-job output directory
(`--router.output_dir`):

| File | Content | Why |
|---|---|---|
| `summary.log` | The current human log at INFO, unchanged | Humans and compatibility |
| `status.json` | Overwritten atomically after each connection batch and pass: stage, pass, unrouted, best so far, violations, work used, current net | An agent polls one small file instead of parsing a log |
| `events.ndjson` | One JSON object per line: stage start and end, pass end (unrouted, violations, vias, length, time, allocation), fallback attempt, best-board restore, stop reason | Exact numbers without regexes |
| `connections.ndjson` | One line per attempted connection: net, endpoints, pass, outcome (routed, failed, rejected by DRC, insert error), work used, what it ripped, failure reason and blocking items | Failure diagnosis, the finisher's input, and later training data for ordering |
| `fanout.ndjson` | One line per pin attempt | Fanout analysis (brief P10) |
| `warnings.log` | Geometry and parser warnings, collapsed to a count per message and location | Stops a burst like `DivisionPoint: projection not found` from flooding the main log, while still showing it |
| `result.json` | The existing result manifest plus the final unrouted list and the danglers count | One answer at the end |
| `trace/` | TRACE output, only when asked for | Debugging |

Each file is a separate Log4j2 logger (`freerouting.events`, `freerouting.connections` and so on), so it can be
turned on and off and sent elsewhere. The same events are served on the existing SSE stream, so an API client gets
what a CLI user gets in files. The cost: JSON lines are written only when their logger is enabled, behind
`isEnabled` guards, which is the lesson of the `isTraceEnabled` fix on `perf-fixes`.

### 6.5 Logging and agent hooks found while working

The user (2026-10-19): while going through the fixes, add logging that helps, and more ways for AI to interact
with the router, wherever we spot them. This list collects them as they come up, and they are built with phase 2.10
(status file, events, best session) unless they are small enough to ride along with the fix that found them.

| Found | Gap | Add |
|---|---|---|
| P4 board fallbacks | Nothing says how the time split between the default run and the fallback stage, or whether a fallback attempt could still win | One summary line and manifest block: time in fanout, default passes, each fallback attempt, optimizer; per attempt its best result against the incumbent |
| P4 board stuck passes | Ten passes at 25 unrouted look like progress in the log; the board kept changing or cycled through 3 states | Per pass: whether the board changed, whether its hash was seen before, and the passes since the last routed connection, in the status file and events |
| Pour outlines as violations | The load warning lists 845-991 "pre-existing" violations on the HV boards, and the fallback lines count them inconsistently ("0 new violations (988 total)" in one run, "991 new violations (991 total)" in the other) | Keep pre-existing and new violations apart everywhere, and name the cause (pour outline against pad) so an agent does not chase them |
| Result manifest | `phases.autorouter.passes_completed` reports the last fallback attempt (2 on the P4 board after 20 default passes), and `phases.fallback` was empty | Report the default run and every attempt separately, with the chosen one marked |
| Persistent failures | The unrouted report lists open connections only at the stagnation stop | Per connection: attempts, the failure reason each time, the blocking items, and coordinates of both ends, in JSON, so an agent can crop a window or relax a rule for exactly those |
| Optimizer gate | Skipped silently on partial boards unless the agent knows `enable_preflight_guards=false` | Name the option in the skip log line and in the agent guide |
| Pair and length rules (section 7.4a) | Ignored without a word | Log the count read, and report pair gap, skew and length violations in the manifest |
| GUI responsiveness | No way to see the cost of drawing | Log the render time of a frame when it exceeds the repaint interval |
| Shove (phase 2.4) | Not visible today | Per pass, counts of shoves, rips and rip-backs, so a run that rips its own work shows it |
| Pre-existing violation warning (phase 1.7c) | Told the user to fix in KiCad what KiCad does not flag (a fixed via in its own pad); fixed | Keep each category of that warning checked against a KiCad DRC run |
| Neck floor (phase 1.7e) | The board minimum track width is not in the DSN, so a run cannot know it | The agent guide tells agents to read it from the KiCad project (`design_settings.rules.min_track_width`) and pass `--router.min_trace_width_um`; a log line confirms the floor |

## 7. The new engine (later phases)

### 7.1 Global router with negotiated congestion

- **Grid.** G-cells per signal layer. Capacity from free width divided by class pitch. Layer restrictions, preferred
  direction and class-pair clearance enter as capacity and costs.
- **Routing.** Route every connection on the cell graph with A*. Iterate PathFinder costs (base plus history, times
  present overuse) until no cell is overused, or the remaining overuse is reported.
- **Output.** Per-connection corridors and layer assignments, plus a congestion map that is useful on its own
  through the API.

### 7.2 Detailed router follows the plan softly, with feedback

- **Soft cost.** Leaving the corridor costs extra; the corridor is not a clip.
- **Feedback.** When a corridor fails in detail, lower that cell's capacity and renegotiate only the affected nets.
  This is what upstream's corridor experiment lacked.

### 7.3 Escape planning per component

Candidate via sites outside the pad field, pin-to-site assignment as min-cost matching (straight out, one lane per
pin), layers from the global plan. This removes fanout oscillation by construction. The user's `plan_fanout()` is a
working prototype of the policy.

### 7.4 Parallelism and GPU

**Why the maze is single-threaded.** One detailed maze search reads the whole board and writes expansion rooms into
the board's shared search tree. A search on another net sees that copper through clearance, and rip-up changes other
nets. Two searches on one board therefore conflict, and a result that depends on which thread finished first breaks
determinism. Upstream measured two parallel designs and removed both (section 3). Running one search with several
threads does not pay either: the work per room is small and irregular.

**The approach that works: race independent attempts on board copies, then pick deterministically.** Every attempt
must be deterministic on its own, and the pick must depend only on the results and a fixed order, never on finish
time. Budgets are counted in work, not seconds. Under those rules the output is the same on 1 core or 22.

| Where | What runs in parallel | Expected gain | Notes |
|---|---|---|---|
| Fallback portfolio | The five fallback strategies, each on its own copy of the input board | Fallback time drops from the sum of the attempts to about the longest one. On Issue070 that is roughly 7 minutes down to under 2. | Attempts already deserialize their own board and clone settings (`autoroute/pipeline/RoutingFallbackRunner.java:258`). What blocks it is `runPrepared` swapping `job.board`, `job.routerSettings` and the stop flag on the shared job, so each attempt needs its own job context. The winner is picked with the existing `replaces` rule in strategy order, which gives the same result as the sequential loop. Phase 2. |
| Strategy portfolio from the start | K default-pass runs with different rip-up costs or item-order seeds | The brief measured 96 against 65 unrouted from `start_ripup_costs` alone (100 against 1000), and its scripts already run 4 variants as separate processes. In-process sharing of the loaded board and a fixed ranking make this one command. | CPU cost is K times. Memory is K board copies (peak heap 0.3-1 GB each on the measured boards). An option, not the default. |
| Leftover windows | The grid finisher or a windowed maze on disjoint windows | Close many leftover connections at once | Windows whose boxes plus a clearance halo do not overlap are independent. Each one is solved on a copy, and results commit in a fixed order with a conflict recheck; a conflict reruns serially. Phase 3. |
| Hard connections | Several searches with different costs on one connection, best result kept | Only for connections that already failed or used their budget | Upstream found that copying a board costs more than an easy search, so this is not for every connection. |
| Global router | All nets within one negotiation iteration | Near-linear in cores | Each iteration reads the previous iteration's costs (Jacobi style), so nets are independent; costs merge by net id. Phase 5. |
| Escape planning | One component's matching problem per thread | Planning is pure computation | Only the insertion is serial. |
| Checks | Full DRC and statistics at pass boundaries | Small | Read-only scans. |
| Optimizer | Already parallel | — | `board/optimize/BatchOptimizer.java:697-777`. It keeps only one improvement per chunk and discards the rest; merging non-overlapping improvements is a separate gain. |

**GPU.** The gridless shape router does not map onto a GPU: its work is pointer-chasing through irregular polygon
geometry, branchy, with very little arithmetic per step. Grid workloads do map well: wavefront or delta-stepping
search on the global router's cell graph, congestion maps, and the fine-grid finisher's windows. Even there, CPU
threads come first, for three reasons:

- **The grids are small.** A 1 mm global grid is tens of thousands of cells, and CPUs handle that in milliseconds.
- **Headless installs.** Freerouting must run headless on WSL and remote sessions, where a usable GPU is often absent.
- **Vendor lock-in.** Any GPU path in Java means TornadoVM, OpenCL or CUDA through the foreign-function API, and that
  ties the build to a vendor.

A GPU backend is worth building only if a profile of the global router or the finisher shows grid search dominating
on large boards. If it is built, it must be optional, with the CPU path as the reference result. The Java Vector API
(SIMD on the CPU) is the cheaper first step for grid kernels.

### 7.4a Differential pairs, matched lengths and impedance

**Why.** The user (2026-10-19): "Impedance and differential pairs would be huge. That's a major miss for this app."
Every modern router handles them, and without them Freerouting cannot route USB, Ethernet, LVDS, HDMI, PCIe, DDR or
fast DSP buses, or differential analog sensing, without the user hand-routing and locking those nets first.

**Which signals need what.** Not every bus needs all of it:

| Signals | Needs |
|---|---|
| I2C, slow SPI, GPIO | Nothing special |
| Fast SPI, parallel buses, DDR data groups | Length matching within a group (clock against data), sometimes impedance |
| USB, Ethernet, LVDS, HDMI, PCIe, fast ADC/DAC links | Differential pairs at a set width and gap (impedance), skew inside the pair, length matching between pairs |
| Differential analog sensing | Pairs routed together, symmetric, sometimes guarded |

**What Freerouting has today:**
- min and max net length from the DSN class `(length ...)` rule (`rules/NetLengthConstraint.java`), reported by DRC
  and interactive routing; the autorouter ignores it;
- no differential pairs, no matched-length groups, no skew rule, no notion of impedance beyond each class's trace
  width (per-layer widths are possible through layer rules).

**The file format.** Specctra DSN is Cadence's format, defined in the *Specctra Design Language Reference*. It is not
an IPC standard, but it is the common autorouter exchange: Allegro, Altium, Pulsonix and KiCad all export it. The
language has constructs for pairs, matched-length groups and from-to lengths. We take the exact syntax from the
reference, and from sample DSNs that Allegro, Altium or Pulsonix export with pairs in them (worth asking for), and
we read whatever those tools write. KiCad's exporter writes no pair or length rules: the user's DSNs have none, although
their `.kicad_pro` files carry diff-pair width, gap and via gap. KiCad users can get them in two ways. One is the
KiCad JSON input Freerouting already has (`management/BoardLoader.java:43`), extended with KiCad's net-class pair and
length rules. The other is the agent adding pair rules to the DSN, the way `dsn_hv.py` adds `class_class`. Impedance
itself (width and gap for a target ohms on a given stackup, IPC-2141) is calculated by the design tool. The router
takes the width and gap per layer as rules. IPC-2581, which KiCad and the others export, carries the stackup if a
later phase wants to check it.

**Plan, in order of risk:**
1. **Read and protect (phase 2, beside shove).** Parse pair, matched-group and length rules from DSN and KiCad JSON,
   and write them back in the session. Rip-up, shove and the optimizer never move a pair member alone and keep every
   length rule. Report pair gap, skew and group mismatch through DRC and the result manifest. Hand-routed pairs then
   survive the autorouter, which is not true today.
2. **Length-aware routing (phase 3).**
   - The maze search prunes paths whose length already exceeds a net's maximum.
   - A tuning pass adds meanders where a net is too short for its minimum or its group, like KiCad's length tuner.
     It runs after routing, within the clearance rules.
   - Skew inside a pair is tuned the same way.
3. **Coupled pair routing (phase 4).** Route the pair as one wide trace (two widths plus the gap) through the maze,
   then split it into two parallel traces at the gap. This suits a shape router well: the search is the one it
   already does, with a wider compensated width. Separately:
   - Pad breakout uses short uncoupled stubs, kept under a maximum uncoupled length.
   - Vias are placed as a via pair at the via gap.
   - Corners keep the gap at 45 degrees.
   - The finisher and escape planner learn the pair form too.
4. **Tests.** The user asked for a demo board, or one made up, to test against the Specctra spec. Both are set up:
   - **Real boards from KiCad's own demos** (`kicad-demos` 10.0.6 is installed in WSL):
     - StickHub: 2 layers, 47 nets, 8 USB pairs.
     - CM5_MINIMA: 6 layers, 220 nets, 32 pairs. It has impedance net classes for 100, 90 and 50 ohm, each with
       its own pair width and gap. It is the same design as the upstream `Issue732` fixture.
     - jetson-agx-thor-baseboard: 10 layers, 1387 nets, about 180 pairs, as the stretch test.
     - `bench/overhaul/dsn_pairs.py` adds `(pair (nets P N (gap um)))` from the board's net classes, since
       KiCad's export drops them.
     - Checked: today's parser loads these DSNs and ignores the pair rules without a warning.
   - **Open boards from GitHub** (cloned 2026-10-19 into `bench/external/`, outside git; licenses read from each
     repo's LICENSE file):
     - Antmicro SDI-MIPI bridge: 6 layers, 175 nets, 13 MIPI pairs, and an inner-layer pair class. Apache-2.0.
     - Antmicro Jetson Orin baseboard: 8 layers, 799 nets, 104 pairs. Its 100 and 85 ohm classes cover HDMI,
       Ethernet, CSI, PCIe and USB, and its `.kicad_dru` has 12 pair-gap rules. Apache-2.0.
     - Antmicro DDR5 testbed: 6 layers, 224 nets, clock and strobe pairs, and data groups for matched-length tests.
       It has no length rules of its own. Apache-2.0.
     - InverterGen5 gate driver: 6 layers, 97 nets, a 200-450 V traction inverter. Its isolation is in the
       layout, not in net classes (one 0.2 mm class). CERN-OHL-W-2.0.
     - The Apache-2.0 boards may become repo fixtures with their LICENSE and NOTICE files. The CERN-OHL-W board
       stays in `bench/` until the user decides.
     - The search found no Allegro, Altium, Pulsonix or PADS export with pair rules. Freerouting issue #358 shows
       pair nets from an unnamed tool.
   - **Made-up fixtures** for unit tests: small hand-written DSNs that use each construct once, written to the
     v10 syntax below. They cover a pair with a per-layer gap, a matched class, a min and max length, and a ratio
     length.
   - KiCad DRC, which knows pair gap and skew, checks the routed results.

**Specctra syntax, from the reference in `docs/reference/` (v10, May 2000):**
- `(network (pair (nets <net> <net> (gap <dim> (layer <layer>)))))`. The gap can be set per layer; with no gap,
  the wire-to-wire clearance applies. `?` wildcards pair several nets at once.
- `(pair (wires ...))` pairs two from-tos instead of two whole nets.
- `(circuit (length <max> [<min>] [(type ratio|actual)]))` on a net or class; -1 means unset.
- `(circuit (match_net_length on (tolerance <dim>)))` on a class. The default tolerance is one inch.
- `match_fromto_length` and `match_fromto_delay` do the same for from-tos.
- `length_gap` and `length_amplitude` shape the meanders used for tuning, and `length_factor` weights length by layer.
- `parallel_segment`, `parallel_noise` and `tandem_noise` are crosstalk rules, later if ever.

Newer tool versions (Allegro PCB Router 16 and 17) may add keywords. Sample exports from Allegro, Altium or
Pulsonix with pairs would show what they write.

### 7.5 Learning

Only after `connections.ndjson` has collected enough outcomes across a corpus: net ordering, corridor-cost tuning,
choosing a strategy per connection. Each learned policy must beat the deterministic one on the benchmark, and none may
relax a rule.

## 8. Plan and order

Each step is a commit, measured against the baseline. Benchmarks use the default settings, so fallbacks are on.

**Benchmark policy.**
- **Every commit:** the unit and fixture tests that cover the change, plus the quick routing test.
- **Milestones only:** the board set (Issue070, a few fixture boards, the user's stage DSNs when available).
- Changes intended to keep routes identical are checked by an SES diff.
- The harness prints one summary line per run, to keep analysis cheap.

| Phase | Steps |
|---|---|
| 0. Harness | Benchmark script (one row per run), SES diff, baseline. Two board groups, dense low-voltage and high-voltage, both measured for every routing change (section 2.1); the four private high-voltage boards are in that group. KiCad DRC as the clearance oracle in the harness, because Freerouting's own count rests on the octagon DRC. |
| 1. Bugs | (1) dangler cleanup on every exit, minimum neck width; (2) `class_class` for vias and pads, with a test board; (3) hangs; (4) a failed attempt (failed insert or strict-DRC rejection) restores the board, including ripped victims and partial copper; (5) 2-pin self-rip; dead randomization as its own measured commit; (6) stagnation on unrouted count, best-session write; starting later passes from the best board as an option that is off by default and benchmarked against the recoveries upstream documented; (7) plane targets (target doors open on the plane layer, expansion stays closed), fixed-track targets, overlapping pads, board-bounds wires; (8) the optimizer, or a cleanup subset, on partial boards. |
| 2. Speed and control | (1) a work budget in search steps instead of time, first, because every later measurement depends on it and it makes results the same on every machine; (2) profile, then rank the search costs (start-room size, layer test order, per-layer filtering, unused clearance trees); (3) per-layer-pair via cost and an admissible heuristic, the likeliest cause of long runs left open on empty layers; (4) shove before rip; (5) deterministic net ordering (airline length, pin density, bounding box); (6) a tolerance in `enforceStrictDrc` so it stops rejecting routes over 2-4 um octagon artefacts; (7) the master pass-1 slowdown (111 s against 9 s on v2.5.0); (8) route only these nets or this region; (9) fallback on the leftovers only; (10) live status file with an estimated finish time, best session on improvement, an agent-triggered stop file (section 6.1a), sub-logs, and pause and stop at connection boundaries as well as pass boundaries; (11) parallel fallback portfolio, only if (9) leaves enough fallback time to matter. (12) the GUI stays responsive while routing (section 4, GUI freeze row). Order change (2026-10-19): (4) shove comes right after (2) the profile, ahead of (3), because the user's boards get stuck where wide traces leave narrow channels, and today's shove only moves traces of the routed net's own width and class (section 4). Hard connections that still fail may try several shove and rip choices on board copies in parallel, picked deterministically (section 7.4, hard connections row). Everything must work headless (`--gui.enabled=false`), which is how remote sessions run it; the GUI also runs under WSLg (checked 2026-10-20), and `-dct 0` skips the 5-second auto-start dialog when a script launches it. |
| 3. Finisher and escapes | Fine-grid local finisher (section 5.3) with a window of the connection's bounding box plus margin, coarse then fine as `pcb_link.py` does, and the exact check after pull-tight. Escape planner per component (section 7.3): fanout is the first stage, the 45-minute hang and the 44.6% escape rate live there, and a prototype exists. Room and via-site reuse between connections is measured here, since it likely changes routes. |
| 4. Agent API | Session API and MCP tools (section 6.3); pause, patch, resume. |
| 5. Engine | Global router with negotiation; soft corridors with feedback; escape planner; parallel global routing. |
| 6. Geometry | Exact circle clearance and DRC; preferred direction in the final geometry; thermal-spoke room. |

## 9. Why stay on Java

- The measured waste is in the algorithms, and a rewrite carries it along unless the algorithms change. Changing
  them in place is far cheaper than rewriting 144,000 lines.
- A native, cross-platform build already exists: GraalVM native image with PGO, built for five platforms in CI
  (`build.gradle:577-597`).
- KiCad 10 and later talk to Freerouting through DSN files or the IPC API (protobuf over NNG). Both are
  language-neutral.
- The global router is a small numeric module. If a profile ever shows Java is the limit there, that one module can
  go native.

## 10. Open questions

- Does room reuse change routes? If it does, the "no route change" claim does not hold for that step.
- Why does pass 1 on master take 111 s against 9 s on v2.5.0 for Issue070?
- Why do cropped windows report 11-27 unrouted for 1-2 open connections?
- Why do long runs stay open on empty layers (brief case C7)? Retest after the phase 1 fixes.

## 11. Independent review (2026-10-19)

A separate reviewer (Claude Fable 5.1, fresh context, reading this document and spot-checking the code) found the
following. Accepted points are already reflected in sections 2 and 8.

| Point | Response |
|---|---|
| "Whole-board work for every room" is wrong for the default 45-degree tree, which prunes progressively; the upstream profile puts room completion at 14.8% | Accepted and corrected. Profile before ranking search costs. |
| Strict-DRC rejection and failed inserts leave victims ripped; failed inserts also leave their partial copper | Confirmed. Phase 1.4 restores the whole board state, not only the victims. |
| `enforceStrictDrc` judges with the octagon DRC, so from pass 3 it rejects valid routes over micrometre artefacts | Accepted: a tolerance there moves into phase 2. |
| Wiring the dead randomization changes routes on every board from pass 4 | Accepted: its own measured commit. |
| Fallback attempts are only partly independent (a cross-attempt memo, sequential early stops, continuations chained on the best board); the 7-minute saving was not shown from the log | Accepted: the portfolio moves after "fallback on leftovers only", and continuations stay serial. |
| Plane fix is more than activating the layer; check what KiCad exports | Accepted into phase 1.7. |
| Work budget first; exact DRC, escape planner, via-pair costs and heuristic earlier; optimizer on partial boards missing; user DSNs into phase 0 | Accepted into section 8. |
| Room reuse later; "start from best board" off by default | Accepted. |
| Finisher window must follow the connection's bounding box, two-level grid | Accepted (section 8, phase 3). |
| The global router fixes congested boards; the user's boards fail for other reasons (inner layers 98% empty) | Accepted. Phases 1-3 target the user's boards; phase 5 targets dense boards. |
| `start_ripup_costs` 100 against 1000 (96 against 65 unrouted) says the default is wrong; a density-based default beats K board copies | Noted. Test a density-based default before building the portfolio. |
| Pause and stop should also work at connection boundaries; log applied patches | Accepted (section 8, phase 2.10). |
| The Vector API is still incubating in JDK 25, which complicates the native image | Noted for the GPU and SIMD question. |
| Missing: shove before rip, deterministic net ordering, a KiCad DRC oracle, determinism that today holds only per machine | Accepted into sections 8 and 10. |

## 12. Progress log

| Date | Commit | Change | Result |
|---|---|---|---|
| 2026-10-19 | (this document) | Roadmap and brief | — |
| 2026-10-19 | phase 1.1 | Final tail and via cleanup runs on every pass-loop exit, repeated until nothing is removed | New `DanglingTailCleanupTest` (bm01, 1 pass, 20 items) fails before the change with dangling items left and passes after it. |
| 2026-10-19 | phase 1.2 | Untyped `class_class` rules reach every clearance class both net classes use (shared ones only raised) | New `ClassClassClearanceTest`: before, HV trace, via and pad against a default-class SMD pad got 0.20 mm instead of 0.51 mm (the `smd` class from `(type default_smd)` was never updated); after, all nine pairs get 0.51 mm. A 0 rule stays exact between the own classes and does not lower `smd`. Applies to tracks as well as vias. |
| 2026-10-19 | phase 1.3 | Hangs: a failed polygon split is remembered instead of redone on every query; via-site splitting uses the engine's stop check, which includes the per-connection time limit, and an interrupted page is not cached | Geometry and autoroute tests pass (103). The hang boards from the brief are not in this repository, so the hang itself is not reproduced here; the SES-identical check runs at the end of phase 1. Not done: a work cap inside `PolylineArea.splitToConvex` (it would change routes) and null checks in the `PolygonShape` callers. |
| 2026-10-19 | phase 1.4 | A failed connection attempt (failed insert, strict-DRC rejection, or any failure after rip-up) undoes itself through an undo snapshot: ripped victims come back and partial copper goes. Undo now updates the connection ledger. | All 948 fast tests pass. Changes routes; the board-set comparison against the baseline was still running at commit time. |
| 2026-10-19 | phase 1.5 dropped | The 2-pin self-rip after two failures removes only that net's own stubs and fanout escapes (a 2-pin net that failed has no finished route), so it clears its own blockers. Not a bug. | — |
| 2026-10-19 | phase 1.5b | Rip-up randomization wired: `AutorouteControl.ripupPassNo` gets the pass number, as in v1.9 | Neutral. bm05: main loop 7 -> 5 unrouted, 3 -> 5 after fallbacks. P4 board 25 -> 24, M4 board 10 -> 11. bm01 and FastTest unchanged (FastTest SES identical). Kept as the intended behaviour, which shove builds on. |
| 2026-10-19 | phase 1.6a | Stagnation counts a pass as progress when it routes another connection and the score rises (`AutorouteBatchLoop.isProgress`, `StagnationProgressTest`) | Only changes boards where one connection is worth less than 0.5 points (more than about 1300 connections). Measured with batch 1. |
| 2026-10-19 | phase 1.8, no code | Optimizer on partial boards | Already possible: `--router.optimizer.enable_preflight_guards=false` lifts the 0-unrouted gate (`BatchOptimizer.java:163`) along with the other pre-flight skips. Its candidate test already rejects changes that add an unrouted connection to the touched nets. Not made the default: the optimizer adds time and closes no connections. Goes in the agent guide. |
| 2026-10-19 | plan | Batching | At the user's suggestion, fixes now ship in batches measured once on both board groups: batch 1 is the phase 1 rest, batch 2 the time wasters (fallbacks on leftovers first, best session, stop and status file, SES rebuild, GUI repaint), batch 3 speed and quality (work budget, profile, shove, length rules, via cost). Each fix keeps its own commit and fast checks; a regression is bisected on the board that got worse. A repeated-board stop was measured as worth only 1-2 minutes of a 40-minute P4 run (boards keep changing or cycle through 3 states), so it is dropped. |
| 2026-10-19 | phase 2.10, part 1, pulled into batch 1 | `--router.status_file=<path>`: live JSON status from headless CLI runs (section 6.1a, item 1), rewritten every 2 s and at the end through a temp file and a rename. `core/RoutingStatus` is fed at stage, pass, item, fallback-attempt and optimizer points. | `RoutingStatusTest`; all fast tests pass. bm05 run: the file showed pass 12, 15 of 25 items, 16 unrouted against a best of 10, 8 passes since the best, and 1-7 s to the stagnation stop. It ended with `final_state` TIMED_OUT and 30 passes of history. It only reads values, so routes cannot change. Still to come: best session, stop file, events, the REST and MCP view of the same status. |
| 2026-10-19 | phase 1.7b | A route may join a fixed track: a fixed trace is split where an unfixed trace ends on it (T-junction); pieces keep geometry and fixed state; crossings are still not split, as in KiCad | New `FixedTrackJoinTest`: 1 open connection after 5 passes before, 0 after. A first version that also split crossings broke 5 KiCad DRC comparison tests and was narrowed. All fast tests pass. |
| 2026-10-19 | phase 1.7c | A fixed via in an SMD pad of its own net is not a clearance violation: the via padstack's attach rule (KiCad exports `attach off`) only limits where the router places vias. Routing still obeys it (`Via.isObstacle` unchanged). Overlapping same-net pads already connect | New `OverlapContactsTest`. FastTest: 40 pre-existing violations to 0, SES identical to the baseline. Mars-64 DRC fixture: 67 to 3; all 64 dropped were test-point pads with their own via; the 3 left are tracks near mounting holes, including KiCad's hole clearance violation. Four tests updated from 67 to 3. All fast tests pass. |
| 2026-10-19 | phase 1.7d | A wire with corners outside the board box is kept, with one warning per wire. It was dropped, so its connection was routed again and a KiCad session import, which replaces the tracks, deleted it. The polyline-path branch never had the check; the router clips expansion to the board | New `WireOutsideBoardTest` fails before (the net was rerouted) and passes after. No benchmark board logged the warning, so benchmark results do not change. All fast tests pass. |
| 2026-10-19 | phase 1.7e | `--router.min_trace_width_um`: floor for every neck (pin taper and maze search through `Pin.getTraceNeckdownHalfwidth`, fanout micro neckdown, necked retry). `BoardRules.minAllowedTraceHalfWidth` existed but nothing set it, so the floor was always 0. Opt-in: a floor taken from the narrowest DSN width rule cost dense boards routability (bm06 8 to 10 unrouted, SMD demo 2 to 6, bm11 fanout escapes 154 to 143), because KiCad allows tapers below the class widths and its minimum track width is not exported | New `NeckFloorTest`: a 0.6 mm net into 0.15 mm pads necked to 0.15 mm before, 0.2 mm with the setting. Default runs unchanged. All fast tests pass. For HV-group benchmarks, pass the board minimum (the export script can read it from the KiCad project). |
| 2026-10-19 | fix | `RouterSettings.clone()` copies the result manifest path, the status file path and `fix_kicad_footprints`. A fallback attempt runs on a clone and a timeout during it leaves the clone in place, so long runs that timed out in the fallback stage wrote no manifest and stopped updating the status file. Found from a thread dump: the CLI reached the manifest step and skipped it (path null) | New `RouterSettingsCloneTest` sets every plain field by reflection and fails before the fix on exactly those three. Logging was not at fault: the log appender is synchronous. |
| 2026-10-19 | measurement, P4 board (4 layers, HV rules, 40-minute cap) | Where the time goes in today's router | The default run reached its result, 25 unrouted, in about 9 minutes. Passes 11-20 then repeated the identical result ten times (about 2.5 minutes). The fallback stage spent the remaining 31 minutes rerunning the whole board and hit the cap without beating that result. About 80% of the run produced nothing, which is the user's experience of waiting hours. The M4 board looked the same: 10 unrouted after 40 minutes, most of it in fallbacks. This makes stopping on a repeated board, running fallbacks on leftovers only, and parallel fallbacks the largest time savings in phase 2. |
| 2026-10-19 | phase 1.4 reverted | The per-attempt undo is removed; the ledger update on undo stays. | Quick boards against the baseline (unrouted counts; wall times were skewed by other load on the machine): bm01 0 and 0, FastTest 2 and 2 (same SES), rpi_splitter 0 and 0, bm05 **3 against 9**. With the rip-up randomization also wired, bm05 reached 4, still worse. Reading: a failure that changes nothing makes the same connection fail the same way on every pass (bm05 repeated one board hash for three passes), while the old damage shook the board up. Restoring victims is only safe once something else moves the board: negotiated congestion (phase 5) or shove (phase 2.4). |
| 2026-10-20 | batch 2, 2fc84bc03 | Trace normalization stops when a net comes back to a trace layout it already had (order-independent geometry hash per iteration) instead of running to its 2000-iteration cap | Issue070: one pass took 446 s for 2 open connections, and again in each fallback; the default run now takes 40 s with the same result. The cap was also hit on the HV cards (21 times in one run). |
| 2026-10-20 | batch 2, 00927898c | Slow connections: each one that takes 10 s or more is logged with pin, net, result and rip count; the status file names the net being routed and gives `current_item_elapsed_s` and `slowest_item` | Logging only. |
| 2026-10-20 | batch 2, 9ae356795 | Progress statistics are rebuilt only for an event that is sent, at most every 5 s; the headless live session copy at most every 10 s (the final output is still written after the pipeline) | S6 board, 2 passes: same board hashes, CPU 933 -> 871 s. |
| 2026-10-20 | batch 2, b0890562f | Fallback work budget: `router.autorouter.fallback_work_ratio` (default 1.0) limits the fallback stage to that many times the default run's connection attempts, counted not timed; new stop reason `WORK_BUDGET`. fanout-retry and short-escape run first. The manifest's `phases.fallback` lists every attempt and the chosen one (it was never filled in). | In the earlier runs no alternative finished a board, and five full reroutes could take five times the default run. |
| 2026-10-20 | batch 2, 3c20e618c | The pass loop stops when 3 passes in a row leave the board unchanged | Replayed on all earlier logs: 44 loops stop sooner, 490 passes saved, one later improvement lost (in an attempt that was not chosen). |
| 2026-10-20 | batch 2, 34075a6f6, f21b13506 | `--router.stop_file=<path>`: the CLI stops the job when the file appears and writes the best board; the CLI waits up to 5 minutes for the routing thread before saving after a timeout | bm05 stopped within a second with SES and manifest. The S6 board had saved an attempt's half-built board (210 unrouted instead of 6) when the timeout fell in a fallback fanout. |
| 2026-10-20 | batch 2, a87c0f1c5 | Pull-tight loops are capped (100 rounds, 64 smoothing rounds), first 5 cap hits logged | An optimizer worker spent 8 hours in `TraceTightener45` corner smoothing on bm01 with no job timeout set; the caps fire on several bm01 traces. |
| 2026-10-20 | batch 2, 324c5f2a8 | Optimizer candidates are ranked on the unclamped V2 score; below zero a pass's improvement is measured against the penalty | The clamp at 0 made every candidate on every real board equal to the incumbent, so every pass was rejected (3-6 minutes lost per completed board, baseline included). bm01: 4 passes accepted, penalty 1239 -> 1049, run 261 s. |
| 2026-10-20 | measurement, batch 2a (jar before 3c20e618c) | Board set with defaults, 30-minute cap | Issue070 184 s, 2 unrouted (baseline 994 s, 90; batch 1 hit the cap). StickHub 175 s, 3, same SES as batch 1 (560 s). P4 board 1171 s, 5 (batch 1 about 41 min, 5). M4 board 695 s, 9 (batch 1 about 40 min, 7: fanout-retry reached 7 here too but was not chosen, see below). bm05 229 s, 5. FastTest 16.5 s, 2. bm01 421 s, 0, before the optimizer fix. S6 board: timed out, 6 in the default run, and the result file had the half-built attempt (fixed in f21b13506). |
| 2026-10-20 | batch 2, 21988027e, 92aa887ef, this commit | The input-board clearance baseline survives deepCopy, the strict-DRC rollback and BoardHistory restores, and a piece of a split pre-existing trace keeps its exemption; the fallback warns when a board has lost its baseline | M4 board: the default reported "730 new violations (730 total)", then 6 new after the copy fixes (pre-existing traces split by fanout T-joins), now 0 new. The fallback chose fanout-retry: 7 unrouted instead of 9, 456 s. |
| 2026-10-20 | batch 2, add21ca5e | `-mt N` sets the optimizer pool and `-mt 0` turns the optimizer off, as documented | The flag set the flat `router.max_threads`, which never reached the optimizer. The bench harness no longer passes `-mt 0`, so runs stay on defaults. |
| 2026-10-20 | batch 2, c97c43183 | The fanout stops at the first pass whose outcome (fanouted, not routed, vias) repeats the previous pass | S6 board: passes 5 and 6 repeated pass 4 (14 fanouted, 3 not routed), about 45 s. |
| 2026-10-20 | batch 2, b238d1b0d | A piece of a split baseline trace stays exempt when its violation is no closer than the original's (it was exempt only at exactly the same distance) | M4 board with planned fanout: 4 "new" violations, all pieces of locked input traces that sat farther from the pin than the whole trace, now 0. |
| 2026-10-20 | batch 2, f95bfd74b | `--router.fanout.strategy=planned` (opt-in, `PlannedFanout`): before the fanout passes, every SMD pin that still needs a connection gets an unfixed escape via on a short stub, as close to the pad as fits: outward, to either side, behind under the part, then diagonally, widening only when no closer site fits. Densest components first; deterministic. The user's manual method on dense boards, where late pins found no room for a via | `PlannedFanoutTest` (no new violations, one via per escape, same board on two runs). With defaults otherwise, default against planned: S6 board 6 -> 3 unrouted (both at the 30-minute cap), L6 board 179 -> 158 after pass 1 (both timed out), Issue070 2 -> 0 unrouted in 56 s instead of 88 s wall, M4 board 7 -> 8, bm05 5 -> 8. No new violations on any board. 89-94% of candidate pins escaped, most outward; side and behind took 40 of 324 on L6. Opt-in because it depends on the board. On M4 the fanout-retry and short-escape fallbacks rebuild the same board under planned (REPEATED) and waste its fallback time. |
| 2026-10-20 | batch 3, f146e1549 | Search work per pass: `TimeLimit` counts its checks (one per maze expansion step) and each fanout and autorouter pass logs `Search work: N searches, S steps, H stopped at the search limit; routed ones took median/p99/max steps`. Opt-in step budgets `router.fanout.max_search_steps_per_pin` and `router.autorouter.max_search_steps_per_connection` replace the wall-clock limits (10 s per pin times the pass, 100 s per connection doubling each pass), which make results depend on machine load | `TimeLimitStepsTest`; `SearchStepBudgetTest` routes FastTest twice with small budgets and gets the same board and the same step counts. Defaults unchanged. L6 board: fanout passes 3 and 4 took 181 s and 264 s for 13 pins, a sign that failing pins use up their whole time allowance. Calibration runs follow, to set default step budgets. |
| 2026-10-20 | phase 2.12, this commit | The toolbar's Display All button stays enabled while the router runs (it only changes the view and reads the board the display already draws). Display Region stays disabled while routing, since it switches the editor state; the mouse wheel and scroll bars already worked | `BoardMenuToolbarA11yTest` passes. The layer-toggle findings in the GUI row are still open. |
| 2026-10-20 | batch 3, da3841581, 3f61663bc | Fanout bookkeeping: the board statistics asked every SMD pin for its unconnected set (one connected-set walk per pin, and on a net with a copper pour each walk covers the whole pour); they now compute one set per group of connected pins. The fanout pass no longer counts the target pins (granular trace only) or all vias (progress update only) after every pin. Found by a Flight Recorder profile of the S6 fanout: 61% of samples were spatial-tree overlap queries, 41% of all samples from the statistics loop and 37% from the per-pin counts | S6 board, fanout only: 232 -> 46 CPU s, identical SES. `FanoutConnectedCountTest` compares the count with the per-pin count on six fixtures. Calibration runs with the step logging: no search hit its limit on any board; the longest routed connection search took about 322,000 steps (S6), the longest routed fanout search about 2,700. |
| 2026-10-20 | phase 2.12, 41312c5c3 | While the router runs, copper pours are drawn as solid fills. The window ignored resizes and zooms during routing on a board with pours on both outer layers: half of the event-thread samples were rebuilding the detailed pour fill (`ConductionArea.ensureDetailedFillCache`, one `java.awt.geom.Area` subtraction per nearby item), whose cache every board change invalidates. The detailed fill returns when routing ends; the design-rules checker still uses the exact fill | Renderer tests pass. Open from the same GUI run: the GUI does not honour `--router.job_timeout` (a 10-minute cap was still routing after 16 minutes; the same jar headless stopped at 10:00). |
| 2026-10-20 | measurement tools | Short, hard measurements instead of hour-long runs. `scripts/benchmark/gen_hard_board.py` writes a synthetic 72 x 60 mm board (160 nets, 476 pins, 6 or 8 layers): a 0.5 mm QFP-100 4 mm from a 0.5 mm QFP-64, a 0.8 mm BGA-144, a 0.5 mm FPC connector and a through-hole header, buses in reverse or shuffled order, a wide 12 V net and 0.5 mm-clearance nets across the fine-pitch area, split inner planes, decoupling and series parts in the escape areas, and full ground pours on both outer layers (or pours over the channel between the parts). The bench folder gets `quick.sh` (the hard board with and without `plane_as_obstacle`, fanout-only L6, S6 and M4) and `pcbench.sh` (24 boards from the PCBench set used by the PCBWorld benchmark, arXiv 2607.05915, 2-minute cap each) | Hard board, channel pours, defaults: 6 layers 13 open after 14 passes (GUI run, still improving), 8 layers 18 open at a 10-minute cap; no clearance violations before routing. PCBWorld reports Freerouting as the strongest rule-based router (clean pass 0.80 and 0.78 on its real-board splits), so the gap to fast routers is time and repeated work, not quality. |
| 2026-10-20 | plan | Keep the original router and add new methods beside it: `router.autorouter.algorithm` selects a registered router (today only `freerouting-router`, and `NamedAlgorithm` is sealed); the first new one is a negotiated-congestion global planner (PathFinder, as in Altium Situs and FPGA routers) on a coarse grid with the existing maze search as the detail router. Shared speed-ups stay in common code: per-net cached connectivity (pour nets make every connected-set query cover the whole pour) and parallel fallback attempts | Measured with the quick set and the PCBench subset at a fixed time cap. |
| 2026-10-20 | batch 3, 86cb6dae1 | `--router.max_search_steps`: a deterministic cap on the maze search steps of the whole job (fanout, autorouter, fallbacks), so capped benchmark runs stop at the same point on any machine and load. Profile of the full-pour hard board: connected-set queries are under 1% of autorouter time; the maze search is 87% (`expandToRoomDoors` 47%, `completeExpansionRoom` 29%, `ForcedPadRouter.checkForcedPad` 24%, overlapping) | `MaxSearchStepsTest`: FastTest capped at 50,000 stops after 50,270 steps twice, same board. PCBench reference (24 boards, 10 M steps each, optimizer off, `bench/overhaul/pcbench.sh`): clean 16/24, 739 open connections, 2,606 CPU s, 109.5 M steps. Every board that finished used the same steps as an earlier wall-clock-capped run made while another program loaded the machine; that run's 2-minute cap had left LimeSDR at 104 open (0 now) and the Teensy layout at 516 (51). |
| 2026-10-20 | open, next | Fanout design: the fanout escapes pins one at a time to a via 2.5-4.5 mm away, so later pins get pinched on dense boards; the per-pin limit is wall-clock time | Planned fanout strategy (all escapes planned per component, via near the pad, cheap to rip, unused fanout removed at the end), opt-in first, measured on LV and HV boards. Make the per-pin limit deterministic. S6 pass speed is batch 3. |
| 2026-10-20 | batch 3, d0f568c9b + parallel fallbacks | `PolygonShape.splitToConvex` drew from one shared static `Random`, so splits on two threads (the optimizer, parallel attempts) could change each other's result; each split now has its own generator with the same seed. `--router.autorouter.fallback_threads` (default 1, opt-in): the alternative strategies and the continuation of a residual board run side by side, each in its own child job, stop state and board copy, merged in the sequential order; each may use the whole work budget left and an equal share of the job's step budget left, so the choice does not depend on the thread count | `ParallelFallbackTest`: 2 and 5 threads give the same attempts, choice and board on FastTest. Sequential path unchanged (same SES on M4, bm05, FastTest, Issue070, S6). Optimizer off, `fallback_work_ratio=0`: M4 539 to 272 s wall, bm05 636 to 235 s, Issue070 95 to 74 s, same boards, about the same CPU; the full-pour synthetic board finished all five attempts in 1574 s (sequential hit the 30-minute cap in the third). With the default ratio 1.0 the first sequential attempt uses the whole budget, so parallel is slower there (M4 160 to 243 s): kept opt-in. PCBench under the 10 M step cap is unaffected: its open boards use the cap in the default run, before the fallback. |
| 2026-10-20 | batch 3, cccadfaaa + trace guards | Via-fit check (`checkForcedPad`, 23% of autorouter samples on the full-pour synthetic board): a per-search cache of the drill checks by drill, layer and room shape gave the same boards but only 10-23% hits and no time saved, so it was dropped; the cost is the first check (half in the clearance-aware search tree query, a third in `TraceShover`). The same profile showed trace messages built with nothing to see them: `ClearanceMatrix.getValue` formatted doubles into a string on every lookup (3.2% of samples for one array read), each granular `FRLogger.trace` looked up `Freerouting.globalSettings` by reflection (1.4%), and `TraceShover`, `MazeRipupResolver`, `Polyline.split` and the fanout diagnostics built strings eagerly. The field lookup now happens once, and those sites build messages only when `FRLogger.isGranularTraceWanted()` (granular tracing, or GUI single stepping or delay) or `isTraceEnabled()` says a trace can be seen | Same SES on FastTest, M4 and the synthetic board (20 M step cap, optimizer and fallback off). CPU: synthetic board 380 to 321 s (wall 307 to 259 s), M4 108.5 to 95.1 s, FastTest 17.9 to 17.6 s. `GranularTraceWantedTest`. |
| 2026-10-20 | batch 3, per-layer leaf index | The search tree's overlap queries (`MinAreaTree.overlapsUnlocked`, 30% of autorouter samples on the full-pour synthetic board) walk one tree of all layers and drop the other layers' leaves afterwards; only 22% of the leaves found were on the layer asked for, and a query visited about 255 nodes. The tree is never rebalanced (average leaf depth 42, maximum 90 at 4,000 leaves). `ShapeSearchTree` now also keeps one small tree per layer (plus one for shapes without a single layer), rebuilt balanced after a quarter of its size in inserts, and its two layer-specific overlap queries use it. Those queries sort their result, so the index shape never changes what they return. The main tree is unchanged, because the 45-degree room completion visits it in tree order and its rooms depend on that order: rebalancing the main tree instead (tried) was 14% faster per step but changed the routes | Same SES on FastTest, M4 and the synthetic board (20 M step cap, optimizer and fallback off). CPU: synthetic board 356 to 297 s (wall 289 to 227 s), M4 108 to 95 s, FastTest (2 layers) unchanged. `LayerLeafIndexTest`: per-layer query equals the filtered full query through inserts, removals and rebuilds. |
| 2026-10-20 | batch 3, layer masks | Room completion (`ShapeSearchTree45Degree.completeShape`, 30% of samples after the per-layer index, three quarters of it the tree walk) visits the main tree in tree order and skips leaves on other layers. Each tree node now carries a bit mask of the layers below it, kept on insert and removal, and the 45- and 90-degree walks skip children without the room's layer; the leaves that matter are visited in the same order. The gain is small because insertion by area mixes layers in nearly every subtree; grouping the tree by layer would reorder the walk and change routes | Same SES on FastTest, M4, the synthetic board and bm01 (20 M step cap). CPU: synthetic board 299 to 274 s, M4 101 to 97 s, bm01 (2 layers) 92 to 94 s (noise). |
| 2026-10-20 | tried, dropped | (1) The maze open list is a `TreeSet` (`TreeMap.put` was 13% of samples); a binary heap with a key set for the set's duplicate rule gave the same boards but no measurable gain (synthetic board 241 vs 239 CPU s). (2) `-Dfreerouting.benchmark.retain_autoroute_database=true` keeps the expansion rooms between connections: M4 ran out of a 4 GB heap, and the synthetic board took 523 instead of 308 CPU s with 50 instead of 13 open at the 20 M step cap; the batch router does not keep retained rooms valid. Measurement note: the same jar varies by about 10% between runs as the machine load changes, so only jars run side by side are compared | Current jar run to completion (optimizer off, default fallback): synthetic board 747 s wall, 5 open, same board as earlier in the day (921 s with the trace guards alone); M4 92 s, 7 open (160 s at the start of the day). |
| 2026-10-20 | cb712afb8, 9688a7fa5, c16b10f49 | Algorithm registry (`router.autorouter.algorithm`: `freerouting-router`, default; `freerouting-planned`, routes like the original for now), the global planner design (`docs/research/global-planner-design.md`, reviewed), and the oracle corridor experiment: a run writes its final copper as per-net corridors, a later run of the same board pays (k - 1) times the move cost outside them | A perfect plan saves no time: M4 65 s and 9 open without, 67-85 s and 8 open with; the synthetic board 268 s and 5 open without, 304-341 s and 3-5 open with (optimizer and fallback off). Pass 1 is a quarter of the synthetic board's time and passes 1-5 half; corridors make pass 1 leave 98 open instead of 68. The planner is on hold; the time is in the cost of each maze search. |
| 2026-10-20 | batch 3, trace tightener checks | PCBench with the current jar: 16/24 clean and 739 open as the reference, CPU 2,606 to 2,317 s (-11%), wall 1,491 to 1,222 s (-18%); but LimeSDR differed by 213 steps between runs of the same jar. Cause: the pull-tight after each routed connection (`optChangedArea`, and the other `TraceTightener` callers) had a 1,000 ms wall-clock guard against endless loops; under load it stopped a tightening after 392 checks that ran to 1,900 on an idle machine, so the copper depended on machine load. The milliseconds are now a fixed number of checks (10 per ms, so 10,000 for the 1-second guard) | LimeSDR: four runs, one alone and three side by side, give the same board, which is the idle-machine result. Earlier same-jar comparisons in this log could differ by this effect on boards where a tightening is slow; the per-pin fanout and per-connection limits are still wall clock by default (step budgets are opt-in). |
| 2026-10-20 | batch 3, fanout connected-set check | Before each pin search the fanout built the pin's whole connected set only to ask whether one item leaves the pin's layer; on a pour net that set is the whole pour with all its vias, and each pour's contact query covers the pour's area. `Item.connectedSetLeavesLayer` walks the same contacts and stops at the first item off the layer. Found by a Flight Recorder profile of the L6 fanout: 82% of fanout samples were this `getConnectedSet` call | Fanout only, identical SES: L6 77 -> 48 CPU s, S6 46 -> 31 s, M4 16 -> 14 s. `ConnectedSetLeavesLayerTest` compares the answer with the full set for every pin and layer on five fixture runs. |
| 2026-10-21 | tried, dropped | Weighted A* (destination-distance heuristic times 1.5, 2 or 3, experiment property). It cut pass 1 on the synthetic board from 96 s and 9.3M steps to 16-29 s and 1.5-2.5M steps, and the main loop there reached 4 open connections at 201 s where plain A* stayed at 5 or more. But the detours it accepts cost more in the rip-up passes: PCBench (4-minute cap, optimizer off) went from 16/24 clean and 717 open (CPU 3,784 s) to 13/24 and 1,262 (3,938 s) at 1.5 and 14/24 and 772 (4,014 s) at 2; M4 also got slower and worse | Code reverted. |
| 2026-10-21 | batch 3, pour contacts from net items | `ConductionArea.getNormalContacts` collected every item of every net overlapping the pour and kept those of its own net. It now tests only the items of its nets (the per-net index), finds the tiles containing each contact point through a cached grid of tile bounding boxes (`TileBoxGrid`), and checks the overlap with `ShapeSearchTree.overlapsObject`, which applies the overlap query's own tests to one item's leaves. A first version that tested every item against every tile was 6 times slower on the L6 fanout | Identical SES. Fanout only, total CPU: L6 79 -> 64 s, S6 70 -> 61 s, M4 46 -> 42 s; full runs of the synthetic board and M4 unchanged. `ConductionAreaContactsTest` compares with the old query on nine fixture runs (52 pours). |
| 2026-10-21 | batch 3, layer index rebuild by selection | A Flight Recorder profile of M4 routing put 7.4% of samples in `LayerLeafIndex.rebuild`: the autoroute tree gains and loses rooms all the time, and each rebuild sorted the leaves by leaf order and then fully at every level. The rebuild now breaks ties by the order of a walk of the old index and finds each median by selection, O(n log n) instead of O(n log^2 n). The index shape never changes a query result (callers sort). Also measured and dropped: caching the via checks (`ForcedViaInserter.checkLayer`, 13% of hard6f routing) within one search, since only 9% of them repeat (M4: 90,288 of 969,935) | Identical SES on hard6f, M4 and L6 (6-minute cap); M4 CPU 164 -> 158 s; hard6f did 6% more search steps in the same time. |
| 2026-10-21 | batch 3, step budgets by default | The last wall-clock limits in routing are replaced by default step budgets: `router.autorouter.max_search_steps_per_connection` = 10,000,000 (doubling each pass) and `router.fanout.max_search_steps_per_pin` = 1,000,000 (times the pass), about the 100 s and 10 s the time limits allowed at about 100,000 steps per second. The longest routed searches measured were about 635,000 and 2,700 steps, so the budgets only stop searches that would have failed. A negative value restores the time limit. With the trace tightener fix (fffe7817b), a run no longer depends on machine load unless it hits the job timeout | Identical SES to the time-limited jar on M4, LimeSDR and the L6 fanout; settings, step budget and bm01 tests pass. |
| 2026-10-21 | tried, dropped | Room completion (`ShapeSearchTree45Degree.completeShape`, about 25% of routing) folds the obstacles into the room in the order a depth-first walk of the main tree visits them; leaves the walk prunes cannot change the result, so any structure that yields the same leaves in the same order gives the same rooms. A walk-order index (a B+ tree of blocks in walk order with the leaves' octagons as plain ints, kept through inserts and removals) did that: a check mode that ran both walks found no difference on five boards and the SES was identical, but CPU did not move (M4 181-188 s for block sizes 4 to 64 against 183 s). The probe behind it: per completion the tree walk pops about 300 nodes, 31 of them leaves, 20 candidate obstacles and 15 restraints; the cost is in visiting the obstacles before the room shrinks, which an exact fold has to do either way | Code removed. |
