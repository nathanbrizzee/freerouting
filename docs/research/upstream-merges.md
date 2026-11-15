# Upstream merges

A ledger of every merge of `upstream/master` (freerouting/freerouting) into `router-overhaul`, so
the next merge only has to look at the commits upstream added since the last one.

## How to start the next merge

The last upstream commit merged is **01db81ca8** (PR #1134, merged 2026-11-14). Git records it as
the second parent of the merge commit, so the new commits are always the ones below:

```
git fetch upstream
git log --no-merges --reverse --format='%h %ad %s' --date=short router-overhaul..upstream/master
git merge-tree --write-tree --name-only router-overhaul upstream/master   # files that will conflict
```

`router-overhaul..upstream/master` keeps working after this file goes stale; `01db81ca8..upstream/master`
is the same list. After the merge, add a row below and a section with one line per upstream commit:
what was taken, what was kept from our side, what is opt-in, and what was not adopted.

## Merge history

| Date | Merge commit | Upstream head merged | Range | Commits | Notes |
|---|---|---|---|---|---|
| 2026-10-23 | 8959aece4 | c4755a887 | 5aa1b24dc..c4755a887 | 102 in all, 55 non-merge | PR #977 meander rules, #980 differential pairs; progress log row of that date |
| 2026-11-08 | 0cc997a42 | 099368c6d | c4755a887..099368c6d | 197 in all, 95 non-merge (2.6.0-RC10 line) | 34 files conflicted; progress log row of that date |
| 2026-11-14 | 4f913bf5a | 01db81ca8 | 099368c6d..01db81ca8 | 199 in all, 77 non-merge (PR #1039..#1134) | 18 files conflicted; per-commit table below |

The first two merges have no per-commit table; their progress log rows
(`router-overhaul-progress.md`) say what was resolved.

## 2026-11-14: 099368c6d..01db81ca8

Follow-up commits on our side: a57526a4b (unreachable check grid released), f52d89e3d (an item on
one of several planes of its net still routes), 4d44af413 (`skip_failed_pairs_in_pass` opt-in),
03277e61e (test timeout), and the neck-width change that put `continue-auto-neck` in the default
lists. "Applied as is" means the commit merged without a conflict and was not changed afterwards.

| Upstream commit | Date | Subject | Outcome |
|---|---|---|---|
| e565900a5 | 2026-10-31 | fix(gui): synchronize autoroute parameter dialog edits with WorkspaceSettings | Applied as is. |
| 6929f010e | 2026-10-31 | test(autoroute): add reproduction test for 2-layer oblong PCB routing | Applied as is. |
| 49b98ef9c | 2026-10-27 | feat(autoroute): implement FastPlaneConnectivityGrid using primitive bitset | Applied as is. |
| 3d4e8b0dc | 2026-10-27 | feat(autoroute): handle plane fracture connectivity, maze target resolution, and cycle protection | Taken. Plane fracture is the basis of our `connectedToItsPlanes` fix (f52d89e3d). |
| 82730073c | 2026-10-27 | feat(drc): integrate plane fracture tracking in NetRoutingLedger and BoardStatistics | Taken: the fracture filter in `BoardStatistics`; the rest of the file stays ours. |
| 89a7e131a | 2026-10-27 | feat(kicad): automatically trigger zone refill after importing routed tracks | Applied as is. |
| 9f8cfe2cf | 2026-10-27 | docs(architecture): document autoroute.plane package and KiCad automatic zone filling | Applied as is. |
| 7a8e09084 | 2026-10-27 | test(autoroute): verify Specctra SES export contains physical bridge traces for cross-EDA continuity | Applied as is. |
| 77de4f8b9 | 2026-11-03 | fix(autoroute): apply conservative obstacle dilation and reset connectivity grid in clearDerivedData | Taken. |
| ffb60b994 | 2026-11-05 | fix(gui): resolve maxThreads NullPointerException when optimizer is disabled | Applied as is. |
| 3268cc642 | 2026-11-06 | perf(board): reuse normal contacts in connected-item walks while the board is unchanged | Applied as is. |
| 50bf504f3 | 2026-11-06 | perf(settings): read Freerouting.globalSettings through a constant method handle | Applied as is. |
| b1d330973 | 2026-11-06 | perf(searchtree): skip enlarging clearance candidates whose bounding octagons stay apart | Taken. |
| defa4c011 | 2026-11-06 | perf(board): filter overlapping pins before the exact overlap test | Taken. |
| 0b7de2afa | 2026-11-06 | perf(optimizer): keep the worker threads fed between candidate batches | Taken (optimizer scheduler), see 56b806c18. |
| a4283e3b0 | 2026-11-06 | perf(autoroute): build event statistics only for listeners that read them | Taken, merged with our time interval for progress statistics; live output keeps our throttle. |
| 26e9fc199 | 2026-11-06 | perf(scoring): walk each item's contacts once for the fanout statistics | Not adopted: ours kept (one connected-set walk per group of pins via `getConnectedSet`, which respects the plane fracture). |
| 37f9115fa | 2026-11-05 | fix(rules): detect differential pairs written ETH_TXP / ETH_TXN | Applied as is. |
| 991c10616 | 2026-11-05 | fix(settings): ignore_net_classes finds KiCad's default and composite classes | Applied as is. |
| d12039d17 | 2026-11-07 | perf(board): read deep copies back outside the board lock | Taken in `RoutingBoardUndoFacade`; `PolygonShape` keeps ours (a generator per split) plus upstream's `convexSplitFailed`. |
| 16ef6de6b | 2026-11-07 | perf(board): reuse the convex tiles of the planes in deep copies | Taken (see d12039d17 for `PolygonShape`). |
| f361d0156 | 2026-11-07 | fix(autoroute): end a connection to a conduction area inside its copper | Applied as is. |
| b7af53875 | 2026-11-07 | fix(autoroute): keep the vias of found connections to plane nets | Applied as is. |
| 2e86733de | 2026-11-07 | perf(autoroute): take incomplete counts from the ledger, also in board copies | Taken: ledger counts and ledger seeding in copies; our duplicate clearance baseline line dropped. |
| 56b806c18 | 2026-11-07 | perf(optimizer): start a candidate whenever a thread is free | Taken: sliding-window scheduler. Kept ours: per-pass candidate stop, memory-sized threads, per-candidate step cap, out-of-memory guard. |
| ed97482b3 | 2026-11-07 | fix(outline): cut oblique outline slices again when trace widths go down | Applied as is. |
| b1c8cf227 | 2026-11-07 | fix(90-degree): complete rooms behind a 2-dim overlap door (follow-up to #1015) | Taken. |
| b0e8d6bf5 | 2026-11-07 | feat(autoroute): drop plane pads into their planes on power layers | Taken. The plane-only search counts as its own search in our search work and step budget. |
| 324f93290 | 2026-11-07 | feat(autoroute): priority_net_classes, routed first and not ripped by other nets | Taken beside our `first_nets`. Pass-1 order: plane items, priority classes, `first_nets`, the rest. |
| e15ba62b2 | 2026-11-05 | perf(board): filter plane contacts before the exact overlap test | Taken. |
| 68654f877 | 2026-11-07 | perf(board): look up plane contacts by their end points | Taken: plane contacts by end point. Our `TileBoxGrid` contact lookup removed. |
| f13c51b5b | 2026-11-07 | fix(drc): dead copper of a plane net is no incomplete connection | Taken. |
| 95f7648c7 | 2026-11-08 | fix(autoroute): no door between ripped traces that meet on a pin | Applied as is. |
| 0caf94341 | 2026-11-08 | fix(settings): skip user_data_path in applyNonRouterEnvironmentVariables | Applied as is. |
| 2afe84824 | 2026-11-09 | fix(rules): enforce case-sensitive net lookups and tie-break ordering | Applied as is. |
| 24d2f9381 | 2026-11-09 | test(rules): add comprehensive case-sensitivity tests for nets and Specctra DSN | Applied as is. |
| 267259e70 | 2026-11-09 | refactor(rules): modernize legacy elementAt and optimize subnet condition | Applied as is. |
| b4b7cc7ad | 2026-11-10 | feat(benchmark): introduce PCBench Tier T regression suite and record 2.6.0-RC11 results | Applied as is. |
| 323da1760 | 2026-11-10 | fix(benchmark): refine Tier T run ordering and update daily-ops protocol docs | Applied as is. |
| 84ccd7676 | 2026-11-10 | fix(rules): handle one-character base names and deduplicate candidates by style priority | Applied as is. |
| 9a1e4218c | 2026-11-10 | fix(board): read area entries and bounds under tree read lock | Taken. |
| d7b84929a | 2026-11-10 | fix(autoroute): do not neck a plane escape without its obstacles | Taken. |
| fd80a1dba | 2026-11-10 | fix(gui): resolve ignore_net_classes like the headless path | Applied as is. |
| 6e2f75468 | 2026-11-10 | fix(autoroute, plane): address review remarks, improve thin polygon rasterization and cache invalidation | Taken. |
| eb2e2b391 | 2026-11-10 | fix(autoroute): support bidirectional plane-via pricing and tag slow fixtures | Taken. |
| 84eea3404 | 2026-11-10 | build(deps): bump the all-dependencies group across 1 directory with 9 updates | Applied as is. |
| dbfc0d654 | 2026-11-10 | feat(autoroute): honor priority via costs in fanout and plane surcharges, clear stale flags | Taken. |
| 3bace31a2 | 2026-11-10 | fix(board): keep junction vias when removing the tail left by a cycle | Applied as is. |
| 96bb4098e | 2026-11-10 | Fanout: do not stop at a drill to a layer the net may not use | Applied as is. |
| 83d5d83af | 2026-11-10 | Fanout: do not stop at a via into another net's plane either | Applied as is. |
| 0781423f8 | 2026-11-11 | fix(dsn): drop corners where a polygon border steps back by a unit | Applied as is. |
| d64b6f95d | 2026-11-10 | fix(io): write an empty identifier as a quoted empty string | Applied as is. |
| 60e6ea767 | 2026-11-11 | feat(io): apply the clearance rules of a KiCad .kicad_dru to a DSN board | Taken; both sides kept in `CliRunner`. |
| 37cc75fc6 | 2026-11-11 | perf(optimizer): option to keep the increased ripup costs after a pass without gain | Taken: `keep_increased_ripup_costs` opt-in. |
| 6397dca16 | 2026-11-11 | fix(specctra): read KiCad rounded-rectangle pads at their real size | Applied as is. |
| 5836bb92d | 2026-11-11 | bench(pcbench): record 2.6.0-RC12 corpus benchmark results and update daily-ops skill | Applied as is. |
| 0f3a57dd0 | 2026-11-11 | fix(optimizer): stop after two passes in a row without gain when the increased ripup costs are kept | Taken (stop rules). |
| 583f1c3a0 | 2026-11-12 | fix(autoroute, plane): prevent false plane fracture on teardrops and unseeded contacts | Applied as is. |
| a05910be9 | 2026-11-12 | test(autoroute): remove unused wall-clock timing code in FastPlaneConnectivityGridTest | Applied as is. |
| 395ebb97b | 2026-11-12 | fix(autoroute, plane): address Copilot Lite review findings on memory, cycles, and clearance | Taken. |
| 50ff2cd25 | 2026-11-12 | docs(instructions): require CI check approval, execution, and progress monitoring during PR review | Applied as is. |
| b934ef6a8 | 2026-11-11 | fix(board): connect a pin to a plane its copper overlaps | Taken. `ConductionAreaContactsTest` reference now counts pins by copper overlap. |
| 4a7f15c44 | 2026-11-12 | Fanout: derive drill layer from section and require active signal escape | Applied as is. |
| 242aacb7f | 2026-11-12 | fix(optimizer): handle candidate vetoes and explicit stop on consecutive no-gain passes | Taken (stop rules); replay of the winner wrapped in our candidate stop. |
| 1ef2e2f7f | 2026-11-12 | fix(specctra): check the legacy outline and drop the corner segment cap | Applied as is. |
| 34c90f9df | 2026-11-12 | fix(datastructures): make MinAreaTree scratch stack thread-local static and re-entrant to prevent optimizer OOM (#1123) | Superseded by 908719c10. |
| 908719c10 | 2026-11-12 | fix(searchtree): eliminate per-instance ThreadLocals in ShapeSearchTree and harden re-entrancy and exception tests | Taken: acquire/release scratch-stack pool (replaces our per-thread stacks). Our per-layer subtree skipping restored in the 45- and 90-degree room completion. |
| b8a168fa3 | 2026-11-12 | ci(workflows): add merge_group event trigger for GitHub Merge Queue support | Applied as is. |
| 09353fa1d | 2026-11-12 | feat(io): require explicit specification for .kicad_dru design rules | Applied as is. |
| 15f2ebd2a | 2026-11-12 | feat(io): take the minimum track width of a KiCad .kicad_pro as the neck width | Taken. Note: sets `neck_width_um` for every KiCad-plugin run; the necked retry is now limited to signal-width classes (`neck_max_class_width_um`). |
| 1e655102f | 2026-11-13 | fix(cli,pcbench): guard AWT look-and-feel behind GUI mode and enable headless flag | Applied as is. |
| 84284fcc0 | 2026-11-13 | perf(autoroute): add pass profiling, strategy tags, scoped failed-pair memoization, and dense-first ordering | Taken: pass profiling, strategy tags (not on race lanes, which prefix their lines), dense-first tie-break (on). The same-pass skip of failed pairs is opt-in, `skip_failed_pairs_in_pass` (4d44af413): it cost lpa96 its clean finish. |
| e2c6a1241 | 2026-11-13 | fix(logging): use English messages instead of localized strings in logs and analytics | Applied as is. |
| 033d59e9c | 2026-11-13 | feat(autoroute): add continue-auto-neck fallback strategy with warning log | Taken. Opt-in at the merge; then in both default lists once wide classes were protected (`neck_max_class_width_um`). |
| 4ff18a311 | 2026-11-13 | fix(kicad): preserve project min_track_width in fallback continuations and address review comments | Taken. |
| c7804ed7c | 2026-11-13 | test(autoroute): add unit tests for pass runner ordering, scoped invalidation, and auto-neck warning | Taken; tests adjusted to our default lists. |
| c78208081 | 2026-11-13 | chore(i18n): update extract-context metadata | Applied as is. |
