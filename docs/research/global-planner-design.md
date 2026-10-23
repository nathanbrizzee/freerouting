# Global planner: design (draft)

Status: on hold after the oracle experiment (2026-10-20, results below). Reviewed 2026-10-20 (changes from
the review are folded in). Roadmap sections 7.1 and 7.2, phase 5.

## Oracle result (step 2)

Each board was routed to completion with the original router (optimizer and fallback off), its final copper
written as corridors, and the same board routed again with a cost for leaving them (1 mm cells, margin 1).

| Board | Run | CPU s | Wall s | Passes | Search steps | Open |
|---|---|---|---|---|---|---|
| M4 | original | 107 | 65 | 18 | 3.5 M | 9 |
| M4 | corridors, k 1.5 / 2 / 3 | 141 / 122 / 131 | 85 / 67 / 77 | 18 / 26 / 23 | 5.4 / 2.8 / 3.7 M | 8 |
| synthetic, 6 layers, full pours | original | 333 | 268 | 27 | 30.1 M | 5 |
| synthetic | corridors, k 2 / 3 | 425 / 377 | 341 / 304 | 33 / 25 | 36.1 / 33.6 M | 3 / 5 |

A perfect plan saves no time on either board and finishes at most two more connections. On M4 the last
connections are blocked locally (the open count reaches 9 by pass 7 and then swings between 9 and 14). On
the synthetic board pass 1 alone is 69 of 268 s and passes 1 to 5 are half the run; with corridors pass 1
leaves 98 open instead of 68, because each net is pushed onto its final path before the routes it bent
around exist. The time goes into the cost of each maze search, not into conflicts a plan would avoid, so the
planner below is not the next step. The corridor experiment code (`OracleCorridors`, `CorridorMap`) was removed
on 2026-10-23, since nothing used it; it is in the git history (before the commit that removed it).

## Why

The detailed router (the maze search over expansion rooms) routes connections one at a time, in an order
that knows nothing about where the board will be crowded. A connection routed early takes the short path
through a channel that ten later connections need, and the pass loop then spends most of its time ripping
and rerouting. On the full-pour synthetic board (`scripts/benchmark/gen_hard_board.py`) pass 1 routes
most connections and the next 25 passes fight over the last 5 to 20. Commercial routers (Altium Situs,
TopoR) and FPGA routers plan first: a coarse global route with negotiated congestion (PathFinder), then
detailed routing inside the plan. The plan is cheap (a grid of a few thousand cells), and it removes most
of the late conflicts before any copper exists.

## What stays

- The original router stays the default (`router.autorouter.algorithm=freerouting-router`), unchanged.
- The planner is a new algorithm, `freerouting-planned`: the same pipeline (fanout, pass loop, fallback,
  optimizer) with a planning stage after fanout and a corridor cost in the maze search. It is a stage
  inside `BatchAutorouter` (run in `AutorouteBatchLoop.run` right after the fanout), not a new algorithm
  class, so every fallback attempt, which reruns the fanout on its own board copy, replans on that board.
- Determinism: the planner uses no clock, no unseeded randomness, and a fixed iteration count. Ties are
  broken by net number and connection index.

## Stage 1: the grid

- **Cells.** A square grid over the board outline, per signal layer. Cell size from the board's dominant
  pitch: about 4 times the default trace pitch (width plus clearance), clamped to 0.5-2.5 mm, and raised
  further when the grid would exceed a cell budget (L6 is 404 x 286 mm: 115,000 cells per layer at 1 mm).
  A 72 x 60 mm board at 1 mm is 4,320 cells per layer. Millimetres convert through
  `board.communication.getResolution(Unit.UM)`.
- **Edge capacity.** For each pair of neighbouring cells on a layer, how many default-class traces fit
  through the shared edge, as a fraction (at 1 mm an HV edge holds 0 or 1 trace, so integer capacity
  would make the congestion term useless): the edge length minus the union of the obstacles' projected
  intervals, divided by the pitch. Obstacles come from the clearance-compensated tree
  (`SearchTreeManager.getAutorouteTree(class)`, `overlappingObjectsWithClearance`); the default tree is
  one clearance short per side. Fixed copper of a net is free for that net and an obstacle for others.
  Unfixed routes already on the board at replanning time seed their net's usage instead of being
  obstacles, so they are not counted twice.
- **Via capacity.** Per cell, the number of vias that fit (free area divided by via pitch squared, minus
  pads and existing vias), and only between layers the via rules and masks allow (`AutorouteControl`
  `viaInfos`, the same bounds `expandToOtherLayers` uses). A planned via also uses capacity on every layer
  of its span.
- **Planes and pours.** Use the items' own predicates (`ConductionArea.isTraceObstacle(net)`,
  `isDrillable(net)`), so the planner follows `plane_as_obstacle` exactly as the detailed router does.
  A group already touching its net's plane is done, as in `BatchAutorouter.getAutorouteItems`.
- **Classes.** A connection of a wider class (wider trace or larger clearance) uses
  `pitch(class) / pitch(default)` units of capacity, so HV nets with 0.5 mm clearance take more room. No
  special case for HV: the same rule for every class.

## Stage 2: what to plan

- The maze search does not route an airline: it routes from one unconnected group of a net to any member
  of the net's connected set (`AutorouteConnectionRouter.routeWithin`), one item per group
  (`getAutorouteItems`). An airline pairing (the Delaunay and spanning tree of `NetIncompletes`) can pair
  groups A and B where the maze joins A to C. So the planner plans each net as a tree over its groups
  (multi-terminal PathFinder: each group searches to the net's growing tree), and every connection of the
  net gets the whole planned tree as its corridor.
- A group's cells are those of its items on the layers where they can be reached: an SMD pad on its
  layer, a through-hole pin or via on the layers within the via bounds.

## Stage 3: negotiated routing (PathFinder)

- A* on the cell graph: moves to the four neighbours on a layer, and via moves inside a cell between
  layers the via rules allow.
- Cost of using an edge: `(base + history) * present`. Here `base` is the length times the layer's
  trace cost in that direction, read from `settings.getTraceCosts()` (outer layers cost more by default),
  `history` grows by the overflow amount each iteration the edge is over capacity (a constant step would
  never clear a channel for wide nets), and `present = 1 + p * max(0, use + demand - capacity)`. Vias cost
  the detailed router's `minNormalViaCost` (lower for plane nets) in the same units, or the plan's layer
  choices will not match the detailed router's.
- Iterate: rip and reroute every connection that uses an overfull edge, raising `p` each iteration. Stop
  when no edge is overfull, or after a fixed number of iterations (default 30), and report the remaining
  overflow.
- Order inside an iteration: shortest airline first, ties by net number. This is deterministic and
  independent of thread count.
- Work is counted in A* expansions, added to the job's search steps (`thread.addSearchSteps`), so
  `router.max_search_steps` caps the planner too, and the planner has its own expansion cap.

## Stage 4: the detailed router follows the plan

- **Soft corridor cost.** In `MazeSearchEngine.expandToDoorSection` the expansion value gains
  `(k - 1) * the weighted length of the move outside the net's corridor on that layer`, where the corridor
  is the planned cells plus one ring. Nothing is forbidden, so a plan that is wrong in detail costs time,
  not completion. The term is non-negative, so the remaining-distance estimate stays a lower bound, but
  outside the corridor it underestimates by the factor k and the search widens there: keep k modest (1.5
  to 3) and watch the per-pass search steps. The term competes with rip-up costs, which grow with the pass
  number, so the corridor binds less in later passes. It is skipped in fanout searches, and attached to
  `AutorouteControl` per connection (also for the necked retry, which builds a second control), with an
  array lookup per cell: this code is most of the profile.
- **Layer assignment.** A via move between layers the plan did not use costs extra in the same way
  (`MazeExpansionEngine.expandToOtherLayers`).
- **Order.** The pass loop routes connections in plan order, most congested corridor first. The pass
  runner rebuilds the order each pass (`reorderSingleThreadItems`: persistent incompletes first after pass
  1, a seeded shuffle at stagnation), so plan order has to be merged with that rule explicitly.
- **Feedback (later step).** When a connection fails or leaves its corridor by more than a set share, the
  cells it failed in lose capacity, and only the affected connections are replanned before the next pass.

## Visibility and control

- Log per iteration: overfull edges, total overflow, connections rerouted, expansions used.
- The result manifest gets a `planner` phase: cells, iterations, final overflow, CPU and wall time.
- A congestion map (overflow per cell and layer) is written as JSON on request, so an agent or a person
  can see where the board is crowded before routing. The GUI can later draw it as an overlay.
- Settings, all under `router.planner`: `cell_size_mm`, `iterations`, `max_expansions`,
  `corridor_cost`, `corridor_margin_cells`. Each is documented in `docs/settings.md` with when it helps.

## Steps (one commit each, each measured)

1. Algorithm registry and the `freerouting-planned` entry point that, for now, runs the original router
   (done, cb712afb8).
2. **Oracle experiment, no planner code.** Route a board to completion with the original router, write
   per-net corridors from its final copper, then rerun the same board with only the corridor cost
   (stage 4) reading those corridors. A perfect plan is the best the planner can give; if it does not
   cut the late passes, negotiation will not either. This also tunes k and the margin on a plan known to
   be feasible.
3. The grid and capacity model, with the congestion map output and a test on a small board; then check
   standalone whether the overflow map predicts where the original router's late rip-ups and open
   connections fall.
4. Negotiated routing on the grid, with overflow logs; measured standalone (overflow, time) on the
   synthetic boards, PCBench and the user's boards.
5. Couple the plan to the detailed router (corridor cost, plan order); measured end to end against the
   original router.
6. Feedback and replanning.

## Risks

- **Capacity estimates are wrong near dense parts.** Fine-pitch escapes do not fit a coarse grid. The
  fanout runs first, and the planner starts from the fanout vias, so the plan covers the open board, not
  the pin field.
- **The plan pulls the detailed router into worse routes.** The cost is soft and tunable; corridor cost
  0 with the original item order gives the original router's routes, which is the first check.
- **Time.** The plan must cost a small share of a pass. At 4,000 cells per layer and a few hundred
  connections, an iteration is well under a second; large boards need the cell budget above.
- **Search widening.** A strong corridor cost makes the maze search expand more outside the corridor,
  since its estimate ignores the term. Measured by the search steps per pass.
