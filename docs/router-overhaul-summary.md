# Router overhaul: what changed and how it was tested

For the maintainers of Freerouting. This is the write-up behind a fork branch that is about 150 commits ahead
of upstream `master` (`5aa1b24`). It lists every change, what each one was measured to do, what was tried and
dropped, and what is still open, so that a pull request can be cut from it in pieces.

- Fork: `nathanbrizzee/freerouting`, branch `router-overhaul`. Release jar: `freerouting-2.5.0-nb.1.jar`.
- Baseline for every comparison: the released v2.5.0 jar (built from `b647a2f`), and upstream `master` for the
  code.
- The branch is large (184 files, +17.5k / -0.5k lines, 51 new test files, about half of it docs), so section 7
  proposes a split into reviewable pieces and names what should not go upstream.
- The longer record, one row per change with its measurement, is `docs/research/router-overhaul-progress.md`.
  The plan and reasoning are in `docs/research/router-overhaul-roadmap.md`.

## 1. Result in one table

Same 19 boards (public: PCBench subset, two Antmicro designs, one synthetic generator board; plus two private
high-voltage boards), v2.5.0 against the new default (`standard` profile). Caps: 2 minutes for the fast group,
6 minutes for the hard group, five runs side by side on a 22-core machine, 3 GB heap each. "Open" is the number of
unrouted connections left at the end. Times are wall seconds. `COMP` means the run ended by itself, `TIME` that it hit the cap.

| Board | v2.5.0 open | v2.5.0 time | now open | now time |
|---|---|---|---|---|
| DoroidOscillo | 5 | 101 COMP | 3 | 124 COMP |
| EncoderBoard | 4 | 124 COMP | 2 | 80 COMP |
| Librecalc | 52 | 126 TIME | 36 | 124 TIME |
| OpenHardwareExG | 2 | 126 TIME | 0 | 36 COMP |
| Starling | 0 | 99 COMP | 0 | 30 COMP |
| TX5823 | 3 | 126 TIME | 1 | 112 COMP |
| ddr5_testbed (Antmicro) | 37 | 372 TIME | 6 | 366 TIME |
| LimeSDR | 141 | 127 TIME | 57 | 126 TIME |
| hard6f (synthetic) | 74 | 370 TIME | 8 | 365 TIME |
| Unifying | 17 | 365 TIME | 7 | 365 TIME |
| lpa96 | 15 | 366 TIME | 1 | 365 TIME |
| mppt-1210 | 0 | 126 TIME | 2 | 125 TIME |
| sensor | 0 | 124 TIME | 0 | 23 COMP |
| logicbone | 475 | 378 TIME | 387 | 366 TIME |
| logicbone_blind | 477 | 374 TIME | 417 | 367 TIME |
| reworkctf | 15 | 366 TIME | 10 | 366 TIME |
| sdi_mipi (Antmicro) | 339 | 397 TIME | 98 | 368 TIME |
| **Public boards, total** | **1,656** | CPU 7,337 s | **1,035** | CPU 5,107 s |

- Public boards: 1,656 open connections down to 1,035 (-38%), clean boards 1 -> 3, CPU time -30%. With the two
  private boards added: 2,066 -> 1,084 open (-48%). The two private boards went 372 -> 43 and 38 -> 6 open.
- One regression: mppt-1210. Its DSN forbids vias in pads (`(attach off)`), v2.5.0 finishes it only by drilling
  vias in its pads anyway, and `standard` honours the DSN. With `--router.autorouter.force_via_in_pad=true` it
  finishes in 82 s with the same board as v2.5.0. The reasoning is in the progress log (2026-10-22).
- Boards cut by the cap are noisy: a changed pass order moves the open count by a few connections either way, so
  single counts on the TIME rows should be read as a trend over the set, not per board.
- Long runs (no short cap, `standard` and `thorough`, 2 and 4 hours) are in section 5.3.

## 2. How the change is packaged

**The default changes.** A new setting `router.profile` selects a bundle of defaults. The default profile,
`standard`, is the best measured combination of the opt-in gates below. `classic` turns every overhaul gate off
and restores the original router's behaviour, so a reviewer can compare against it on any board:

| Profile | What it is |
|---|---|
| `classic` | The original router: every overhaul gate at its old default. |
| `quick` | `standard` without fallbacks and optimizer; a first look. |
| `standard` (default) | BGA escape planning, escape gates on grid balls, via cost by span, `force_via_in_pad=false` (honour the DSN), a continued board's routes movable. |
| `thorough` | `standard` plus the fallback race on all cores without a budget, and the grid finisher with lifting. Meant to continue from a `standard` result. |

Profiles replace the hardcoded defaults (the lowest settings layer), so every other source (file, environment,
command line, API) still wins, and a profile can switch a setting off. The settings merge copies only values that
differ from the Java default, so a profile placed above the defaults could never have switched anything off.

**Routing is deterministic.** Nothing in routing depends on elapsed time or machine load. The wall-clock limits
that did (the per-connection and per-pin search limits, the trace tightener's 1-second guard) were replaced by
step counts; a job timeout may only stop work. Section 3.3.

**Everything is documented** in `docs/settings.md` (each new setting with a "when it helps" paragraph), in
`docs/agent-guide.md` (shipped inside the jar, printed by `--agent-guide`), and a test
(`SettingsDocumentationTest`) fails the build when a router setting has no entry.

## 3. The changes

Commit ids are the fork's. Each change has its own commit and, where it changes behaviour, its own test.

### 3.1 Bug fixes (all on by default)

| Change | Commit | Evidence |
|---|---|---|
| Dangling tails and vias are removed on every pass-loop exit, repeated until nothing is removed | 0f9077281 | `DanglingTailCleanupTest` fails before, passes after |
| `class_class` clearance rules reach every clearance class both net classes use (the `smd` class from `(type default_smd)` was never updated: a HV trace got 0.20 mm against an SMD pad instead of 0.51 mm) | de8d8c425 | `ClassClassClearanceTest` |
| A route may end on a fixed track with a T-junction (the fixed trace is split) | c46569220 | `FixedTrackJoinTest`: 1 open -> 0. A first version that also split crossings broke 5 KiCad DRC comparison tests and was narrowed |
| A fixed via inside an SMD pad of its own net is not a clearance violation (KiCad exports `attach off`, which limits where the router places vias, not what already exists) | 34f8e40a5 | `OverlapContactsTest`; FastTest 40 violations -> 0 with the SES unchanged; the Mars-64 DRC fixture 67 -> 3 (all 64 dropped were test-point pads with their own via) |
| A wire with corners outside the board box is kept with one warning; it was dropped, so it was routed again and a KiCad session import deleted it | dc7319303 | `WireOutsideBoardTest` fails before |
| `RouterSettings.clone()` copied neither the result manifest path, the status file path nor `fix_kicad_footprints`; a timeout inside a fallback attempt (which runs on a clone) wrote no manifest | 0311d6c49 | `RouterSettingsCloneTest` sets every plain field by reflection |
| Trace normalization stops when a net returns to a layout it already had, instead of running to its 2000-iteration cap (issue 2 of the performance findings) | 2fc84bc03 | Issue070: one pass 446 s -> 40 s for the same result |
| Pull-tight loops are capped (100 rounds, 64 smoothing rounds) | a87c0f1c5 | An optimizer worker had spent 8 hours in `TraceTightener45` on bm01; the caps fire on several traces |
| Optimizer candidates are ranked on the unclamped score (the clamp at 0 made every candidate equal to the incumbent, so every pass was rejected: 3-6 minutes lost per completed board) | 324c5f2a8 | bm01: 4 passes accepted, penalty 1239 -> 1049, run 261 s |
| The clearance baseline of the input board survives board copies, undo and history restores, and pieces of split pre-existing traces stay exempt | 21988027e, 92aa887ef, 3604e5757, b238d1b0d | A board that reported "730 new violations" now reports 0 |
| `-mt N` sets the optimizer pool and `-mt 0` turns the optimizer off, as documented (it set a flat key that never reached the optimizer) | add21ca5e | |
| Each polygon convex split has its own seeded generator (a shared static `Random` made the result depend on thread timing) | d0f568c9b | `ParallelFallbackTest` |
| Pour contact tile grid published together with its tiles (a second thread could see a null grid) | 42d835263 | Found by `ConnectedSetLeavesLayerTest` |
| Via-site splitting uses the engine's stop check and does not redo a failed polygon split | 89e6afd13 | Geometry and autoroute tests; the original hang boards are private and were not available |
| A session loaded to continue routing (`-de board.dsn earlier.ses`) came in with every route user-fixed, so nothing could be ripped up; now movable (off in `classic`) | 50f45250b | `SesRoundTripTest`; 11 boards with leftovers continued: 46 open -> 33, worse on none |
| Launch options (`--router.job_timeout`, `-mt 0`, status file...) were saved into the user's `freerouting.json` when the GUI closed | 227f9bf92 | `LaunchOverridesTest` |

### 3.2 Speed with identical output

Each of these gives the same SES (compared by hash or by diff) on the boards named, and a lower CPU time:

| Change | Commit | Effect |
|---|---|---|
| Fanout bookkeeping: one connected set per group of pins instead of one per pin; no counting only for logs | da3841581, 3f61663bc | S6 fanout 232 -> 46 CPU s |
| Fanout's connected-set check stops at the first item off the pin's layer | 4ae9fdfa1 | L6 fanout 77 -> 48 s |
| Pour contacts found from the pour's own net items through a tile grid | b5eb0532a | L6 fanout 79 -> 64 s |
| Per-layer leaf index for the search tree's layer-specific overlap queries; rebuilt by median selection | 7b6b0b9c3, fa194a6fa | synthetic board 356 -> 297 CPU s |
| Layer masks on the tree nodes for room completion | e540f215b | synthetic board 299 -> 274 s |
| Trace messages are built only when a trace can be seen (`ClearanceMatrix.getValue` formatted strings on every lookup: 3.2% of samples) | cccadfaaa, 55ad28ba1 | synthetic board 380 -> 321 CPU s; `GranularTraceWantedTest` |
| Progress statistics rebuilt only for an event that is sent, at most every 5 s; headless live session copy at most every 10 s | 9ae356795 | S6 2 passes: CPU 933 -> 871 s |
| Fanout stops after one repeated pass outcome; the pass loop stops after 3 passes that leave the board unchanged | c97c43183, 3c20e618c | 44 loops stop sooner on the earlier logs, 490 passes saved, one later improvement lost (in an attempt not chosen) |

Together they took the PCBench reference (24 boards, fixed step cap) from 2,606 to 2,317 CPU s (-11%) with the
same boards. The profiles in the progress log show where the CPU goes now: the maze search is 89-95% of samples
and no single waste is left in it.

### 3.3 Determinism

- `router.max_search_steps`: a cap on the maze steps of the whole job (86cb6dae1), so capped benchmark runs stop at
  the same point on any machine. `MaxSearchStepsTest`: FastTest stops at 50,270 steps twice, same board.
- Step budgets replace the wall-clock limits per connection (10,000,000 steps, doubling each pass) and per fanout
  pin (1,000,000 times the pass) (4683a83e0). The longest routed searches measured were about 635,000 and 2,700
  steps, so the budgets only stop searches that would have failed. A negative value restores the time limit.
  Identical SES to the time-limited jar on the boards compared.
- The trace tightener's 1-second guard counted milliseconds, so under load it stopped after 392 checks that ran to
  1,900 on an idle machine and the copper depended on load; it now counts checks (fffe7817b). LimeSDR gives the same
  board alone and with three runs beside it.
- Unchanged and still wall-clock: the job timeout and the CLI's save wait, by design.

### 3.4 Routing quality (the part that changes results)

| Change | Commit | Evidence |
|---|---|---|
| Pins within clearance of fixed foreign copper at every pad corner count as blocked by the design (so the router stops ripping neighbours for connections that cannot be made) | 0dd888645 | `DesignBlocksTest`; M4 7 -> 6 open, all 6 reported as design-blocked |
| Insert along the pad axis: when the last segment into a fine-pitch pad fails (a 0.2 mm pad at 0.4 mm pitch: the gap equals the clearance), go to a point beyond the pad end on its long axis, then straight in | 2c9bd99b2 | Unifying refusals 237 -> 15 in a 60 s run; 12 boards open 63 -> 60, no new violations. The same insert for the first segment never changed a result and was removed |
| BGA fanout (`fanout.strategy=bga`): detect pin grids, reserve one diagonal gap per ball by bipartite matching, inner balls first; dog-bone per inner ball; each ring to its own layer where blind vias exist | 2a0d9fcfa, 99df4e452 | Test grid 8 x 8: 36 of 36 inner balls placed, no violations, the same hash every run. 6-minute logicbone smoke: open 461 -> 187 |
| Escape window scaled by pin pitch (`min_escape_by_pitch`): the default 2.5 mm minimum forbade a dog-bone on any BGA | 7ca702d20 | `FanoutEscapeWindowTest` |
| Other pins' escapes protected during fanout (`protect_escapes`) | 6ca1c9f43 | Pass 2 re-fans 6 pins instead of 31 on the test grid |
| Both gates scoped to balls of detected grids (`*_grid_only`) | 24c961ab9, e5da1098e | `EscapeGateScopeTest`. 19 boards: all gates on every pin 835 open, scoped 854 with 7142 -> 6681 CPU s and without the Starling/hard6f regressions |
| Blind vias where the stack is blocked (`DrillPage` cut via sites by copper on every layer before checking the via's own span) | be616e031 | New fixture `BlindViaStack.dsn`: open 1 -> 0 |
| Via cost by span (`via_span_cost_factor`) | dd82a96d6, e24b5607a | `ViaSpanCostTest`; through-only boards unchanged |
| Via-in-pad is a setting (`force_via_in_pad`) | e55afb94a | `ViaInPadSettingTest` |
| Fallback work budget (`fallback_work_ratio`, default 1.0, counted in connection attempts, not time) and a list of attempts in the manifest | b0890562f | Five full reroutes could take five times the default run |
| Grid finisher (`grid_finisher`, in `thorough`): for each remaining open connection, a fine-grid A* in a window with masks from the clearance matrix, kept only if the pieces connect and no new item has a violation | 9ff644f8b, 8eac0862c | `GridFinisherTest`. 11 boards with 59 open: 7 closed, no new violation |
| The finisher may lift other connections in its way (`grid_finisher_ripup`) | 3b8f74a7e | 4 more closed by lifting; `GridFinisherTest` |

### 3.5 Opt-in settings (board-dependent, off in every profile unless noted)

Each one helps some boards and hurts others, so it stays opt-in with a "when it helps" paragraph in `settings.md`.
This is also the list a reviewer may prefer to leave out of a first pull request.

| Setting | Helps | Hurts / why off |
|---|---|---|
| `fanout.strategy=planned` | Issue070 2 -> 0 open, S6 6 -> 3 | M4 7 -> 8, bm05 5 -> 8 |
| `autorouter.fallback_threads`, `fallback_race`, `fallback_race_strategies` (in `thorough`) | 8 hard PCBench boards at 8 min: 6 open in all against 26 with the defaults; lpa96 2 -> 0, Unifying 11 -> 1 | A board the default run finishes pays for the race (Librecalc 204 -> 1,387 CPU s); in `standard` it was 4x the CPU and worse at short caps, better only uncapped |
| `seed-N` fallback strategies | PCBench: the best of four item orders makes 18 of 24 boards clean against 16 | each seed costs a full run |
| `autorouter.spring_over_overlaps` | M4 164 -> 86 s, Starling 36 -> 21 s | TX5823 0 -> 1 open, mppt-1210 0 -> 2 |
| `autorouter.rip_history_cost` and the `history` race strategy | EncoderBoard 2 -> 0, reworkctf 4 -> 0, L6 39 -> 25 | full set 881 -> 1,151 open |
| `autorouter.pin_access_cost` | mixed (160 -> 154 open on six boards) | inert with fanout on |
| `autorouter.pad_neck` (as the last step on open pins only) | S6 `thorough` 17 -> 16 | applied from the start it lowered S6 from 18 to 25 open |
| `min_trace_width_um` | floors every neck | a floor from the narrowest DSN width cost dense boards routability |
| `fanout.protect_escapes` on every pin (without `_grid_only`) | LimeSDR 25 -> 0, reworkctf 4 -> 0 | Librecalc 9 -> 43, sdi_mipi 82 -> 106 |

### 3.6 Observability for agents and scripts

The router is often run by a person's AI agent, so a run now says what it did and what stopped it.

- `--router.status_file` (live JSON every 2 s: stage, pass, item, the net being routed, slow items, history) and
  `--router.stop_file` (create the file and the run saves its best board and the report) (7f923a872, 34075a6f6).
- `--router.result_json`: final state, per-phase metrics, the fallback attempts with the chosen one, and every open
  connection with end points in mm, airline length, the exit state of each pin end and a `placement_hints` list
  (747386622, dc6f55213).
- The log ends with the open connections, the crowded regions (board cells ranked by copper fill times the open
  connections crossing, with the parts whose pins lie in them) and a verdict that sorts the open connections into
  pins the design closes in, pins closed in by routes or nearby parts, and ends held only by space (285012a61).
- A `Search work:` line per pass, `Rip-up:` line (items ripped, back-and-forth net pairs), slow-connection lines
  (f146e1549, 1c0626941, 00927898c).
- GUI runs honour the same limits, files and exit code as headless runs (2dbf193eb); the GUI status bar shows the verdict counts (a2c540eb8).
- `docs/agent-guide.md` ships in the jar; `java -jar freerouting.jar --agent-guide` prints it, and the second log
  line points to it. `llms.txt` at the repo root points to it too. A test checks that the jar carries the guide.
- `JobRunSupport` holds what the CLI and GUI paths share.

### 3.7 GUI

Small and kept narrow: no per-update SES copy during a run and redraws spaced by draw time; copper pours drawn as
solid fills while the router runs (half of the event-thread samples had been rebuilding the detailed fill); Display
All and Display Region stay enabled while routing; a click during a run stops it except in zoom-region state. A
measured GUI run spent 3 of 162 sampled seconds drawing. New GUI text exists in the English bundle only.

## 4. Tried and dropped (so nobody repeats them)

Each has a row in the progress log with numbers. In short:

- Per-attempt undo of a failed connection: bm05 3 -> 9 open (a failure that changes nothing fails the same way every pass). Reverted.
- Weighted A* (1.5 to 3): pass 1 four to six times faster, but PCBench 16/24 -> 13/24 clean. Reverted.
- Open list as a binary heap, caching via checks, retaining expansion rooms (out of memory), a walk-order index for room completion (same SES, same CPU), speculative parallel searches (0-5% of the search time stays valid): no gain.
- Reverse retry of a failed search: some route, but boards ended no better. Removed.
- Global planner (negotiated congestion): an oracle experiment showed a perfect plan saves no time. On hold; the experiment code was removed.
- Stall stopping rule (stop after r times the steps to the best): loses Starling, reworkctf, Unifying and EncoderBoard, whose gains come after a flat stretch. A cap on the fallback's steps would save about 15 of 40 minutes on one board. Neither built.
- Race inside `standard`: 1,230 open against 759 at 3.9x the CPU at the set's caps. Kept in `thorough` only.
- Hard-wired `force_via_in_pad`: the mppt-1210 case above.

## 5. How it was tested

### 5.1 Unit and fixture tests

51 new test files and 3,851 new test lines (the suite result is in section 5.4). They cover each behaviour change
above. The ones a reviewer should read first: `DanglingTailCleanupTest`, `ClassClassClearanceTest`,
`FixedTrackJoinTest`, `OverlapContactsTest`, `RouterSettingsCloneTest`, `StagnationProgressTest`,
`SearchStepBudgetTest`, `MaxSearchStepsTest`, `ParallelFallbackTest`, `FallbackRaceTest`, `PlannedFanoutTest`,
`FanoutEscapeWindowTest`, `EscapeGateScopeTest`, `BlindViaStack` and `BgaEscapeLayersTest`, `GridFinisherTest`,
`RouterProfilesTest`, `SesRoundTripTest`, `LaunchOverridesTest`, `OpenConnectionsTest`, `CongestionTest`,
`AgentGuideTest`, `SettingsDocumentationTest`. New fixture DSNs are in `fixtures/`.

### 5.2 Board runs

- Method: same jar side by side with the comparison jar, same caps, five runs at a time, `-mt 0` unless the
  optimizer is the subject, one result file per run (`--router.result_json`). The comparison is on the log's pass
  lines (score, unrouted, violations); equivalence of two jars is judged on the SES (hash or diff) where the claim is
  "same output".
- A gate that changes nothing when off was checked on 19 boards: identical normalized pass lines up to the pass
  the cap cut.
- Boards: a PCBench subset (24 boards, from the benchmark of arXiv 2607.05915), the repository fixtures (bm01,
  bm05, FastTest, Issue070, StickHub...), two Antmicro designs (sdi_mipi, ddr5_testbed, Apache-2.0), a synthetic
  board from `scripts/benchmark/gen_hard_board.py`, and two private high-voltage boards (labels L6, S6, M4, P4 in the
  docs), because the work was done for dense low-voltage and for high-voltage boards alike.
- The harness (`run-set.sh`, `run-batch.sh`, summary and comparison scripts) is in `scripts/benchmark/overhaul/`.

### 5.3 Long runs

`standard` stops by itself, without a cap, on every board but the two logicbone designs:

| Board | `standard` | `thorough` |
|---|---|---|
| sdi_mipi (public) | 59 open in 79 min | 41 open in 104 min (`seed-1` won) |
| logicbone (public), 4 h | 25 open, stopped by the limit | 21 open, ended itself at 3.4 h |
| logicbone_blind (public), 4 h | 6 open, ended itself at 3.85 h | 1 open at the 4 h limit (`direct-first` won) |
| L6 (private) | 13 open in 69 min | 13 open in 16 min |

`thorough` costs about 3x the CPU of `standard` on the two logicbone boards. These four runs shared the machine,
so their wall times are longer than on a quiet machine.

### 5.4 Test suite

`./gradlew test` (the fast set, which excludes tests tagged `slow`) on the head of the branch when this file was
written: 1,075 tests in 238 classes, 0 failures, 0 errors, 3 skipped. `spotlessCheck`, `checkstyleMain` and
`checkstyleTest` pass (checkstyle reports only warnings that were already there), and
`scripts/i18n/extract-context.py --check` is clean.


## 6. Limits and what is not done

- **Differential pairs, matched lengths and impedance are not implemented.** They are planned
  (`docs/research/router-overhaul-roadmap.md`, 7.4a): read and protect pair rules, length-aware routing, then
  routing a coupled pair as one wide trace split at the gap. The test boards and Specctra syntax notes are set up;
  the parser still reads pair rules and ignores them.
- Boards where the remaining open connections are caused by placement (pins closed in by neighbouring parts,
  design-blocked pins) cannot be finished by the router; the verdict says which connections these are.
- The two logicbone boards need hours; the BGA planner does not choose the escape direction per layer.
- The run-to-run noise of boards cut by a time cap is a few connections (about 10% in CPU between runs on the same
  jar before the determinism fixes, much less after).
- Results on the private high-voltage boards cannot be reproduced by the maintainers; every claim above has a
  public-board counterpart or says so.

## 7. Suggested split into pull requests

Fork-only and best left out: `CLAUDE.md`, the `docs/research` plan and brief (kept as background), the fork
version label (c39798154).

1. **Bug fixes and logging** (3.1 and the status/stop files): independent commits with tests; no default routing
   behaviour changes except where a bug was fixed. Start here.
2. **Speed with identical output** (3.2): can be merged on the SES-identical evidence alone.
3. **Determinism** (3.3): step budgets and the tightener change. Output equals the time-limited jar on the boards compared.
4. **Routing quality and profiles** (3.4, 3.5): the default-changing piece. Land the gates off first, then the
   `standard` profile as a separate commit, so the maintainers can run `classic` against it.
5. **Agent guide, result file, verdict, GUI** (3.6, 3.7).

Reviewers who want a single number to check: `--router.profile=classic` against `--router.profile=standard` on the boards in section 1.

## 8. Reproducing

```
java -jar freerouting-2.5.0-nb.1.jar -de board.dsn -do board.ses --gui.enabled=false \
  --router.job_timeout=00:10:00 --router.result_json=board.json
# the original router's behaviour, same jar:
java -jar freerouting-2.5.0-nb.1.jar ... --router.profile=classic
```

The comparison against v2.5.0 uses the released v2.5.0 jar and the same command. The harness scripts are in `scripts/benchmark/overhaul/`.
