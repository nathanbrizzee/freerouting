# Router overhaul roadmap

Status: in progress on branch `router-overhaul` of the fork (branched from `perf-fixes`, which is upstream master
`5aa1b24` plus the logging fix). Started 2026-10-19. Each fix is its own commit, so the history shows the route from
the baseline to the end state. Code paths are relative to `src/main/java/app/freerouting/`.

Goal, from the user: speed and completion. Simple and medium boards must not take hours, and the router must finish
every connection without leaving danglers. AI agents should be able to steer the router through APIs and read its
output.

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
| Danglers on incomplete boards | Final tail and via cleanup is skipped whenever the router stops itself: every stagnation, no-improvement or max-passes stop sets the stop flag first (`AutorouteBatchLoop.java:661` against `:747-752`). The cleanup is a single sweep. | Run it on every exit and repeat until nothing changes. Never neck below the minimum width. |
| Router vias ignore `class_class` | A rule without a type list sets only the matrix entry named after the net class (`io/specctra/parser/Network.java:656-663`); via and pad sub-classes miss it. Likely cause; a test board will prove it. | Propagate the rule to the sub-classes. Rebuild via infos after class pairs are read. |
| Later passes worse than pass 1 | A strict-DRC rejection (from pass 3) leaves its victims ripped (`AutorouteConnectionRouter.java:299-314`). A partial insert does the same (`FoundConnectionInserter.java:97-106`). A 2-pin net that fails twice rips its whole self (`AutoroutePassRunner.java:175-180`). | Restore the victims. Limit the self-rip to a region. |
| Rip-up loops | The randomization meant to break them never runs: `ripupPassNo` stays 1 (`AutorouteControl.java:189`, read at `MazeRipupResolver.java:158`). | Wire it to the pass number, then measure. |
| Stagnation fires on real progress on big boards | On a board with more than about 1300 connections, closing one connection moves the score by less than the 0.5 threshold (`BatchAutorouter.java:64`). | Judge stagnation on unrouted count and violations. |
| Hangs | Via-site splitting has no cap and does not see the time limit (`geometry/planar/PolylineArea.java:168-204`). A failed polygon split is not cached and returns null, so it repeats and logs again (`PolygonShape.java:552-613`). A shape that fails to split disappears from the index. | Work cap and time limit; cache the failure; treat the shape as an obstacle. |
| SMD pin will not join a plane | Plane layers are inactive for routing, so plane targets are skipped (`AutorouteControl.java:155-161`, `MazeSearchEngine.java:654`). | A plane layer is a target for its own net only. |
| Will not join a fixed track | Joining needs a split, and fixed traces refuse it (`board/trace/PolylineTrace.java:727`). | Topology-only split; both halves stay fixed. |
| Overlapping same-net pads not connected | `Pin.java:771`, `DrillItem.java:333` | Copper overlap counts as a contact. |
| Wire crossing the board bounds dropped | `Wiring.java:498-509` | Keep it. |
| Preferred direction has no effect | It biases the room sequence only, not the final geometry (section 6, question 13 of the brief). | Later phase. |
| False clearance violations | Octagon geometry and binary-search DRC (`Item.java:446-563`). | Exact circle and segment distance, in a later phase. |

## 5. Would a grid router help?

Three possible roles, three different answers.

### 5.1 A grid router instead of the shape router: no

- **Pins.** Fine-pitch pins are off any practical grid. At 0.5 mm pitch with 0.2 mm rules, a 0.25 mm grid cannot
  reach every pin, and a 0.05 mm grid on the 404 x 286 mm carrier is about 46 million cells per layer.
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
direction per layer, exact segment checks) closed every connection Freerouting left open across five full carrier
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
| 0. Harness | Benchmark script (one row per run), SES diff, baseline. The user's stage DSNs are the goal set, so they join as soon as they arrive. KiCad DRC as the clearance oracle in the harness, because Freerouting's own count rests on the octagon DRC. |
| 1. Bugs | (1) dangler cleanup on every exit, minimum neck width; (2) `class_class` for vias and pads, with a test board; (3) hangs; (4) a failed attempt (failed insert or strict-DRC rejection) restores the board, including ripped victims and partial copper; (5) 2-pin self-rip; dead randomization as its own measured commit; (6) stagnation on unrouted count, best-session write; starting later passes from the best board as an option that is off by default and benchmarked against the recoveries upstream documented; (7) plane targets (target doors open on the plane layer, expansion stays closed), fixed-track targets, overlapping pads, board-bounds wires; (8) the optimizer, or a cleanup subset, on partial boards. |
| 2. Speed and control | (1) a work budget in search steps instead of time, first, because every later measurement depends on it and it makes results the same on every machine; (2) profile, then rank the search costs (start-room size, layer test order, per-layer filtering, unused clearance trees); (3) per-layer-pair via cost and an admissible heuristic, the likeliest cause of long runs left open on empty layers; (4) shove before rip; (5) deterministic net ordering (airline length, pin density, bounding box); (6) a tolerance in `enforceStrictDrc` so it stops rejecting routes over 2-4 um octagon artefacts; (7) the master pass-1 slowdown (111 s against 9 s on v2.5.0); (8) route only these nets or this region; (9) fallback on the leftovers only; (10) live status file with an estimated finish time, best session on improvement, an agent-triggered stop file (section 6.1a), sub-logs, and pause and stop at connection boundaries as well as pass boundaries; (11) parallel fallback portfolio, only if (9) leaves enough fallback time to matter. Everything must work headless (`--gui.enabled=false`), which is how WSL and remote sessions run it. |
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
