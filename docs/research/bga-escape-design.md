# BGA escape: design (2026-10-21)

Status: reviewed against the code at HEAD (747386622) and against the Specctra DSN conventions the parser follows.
Nothing in sections 4-6 is built yet. Roadmap section 7.3 ("Escape planning per component") and the opt-in
`--router.fanout.strategy=planned` (`PlannedFanout`) are the starting points.

Requirements from the user (2026-10-21):
- the escape from a centre ball may be longer than the fanout length limits;
- with blind or buried vias enabled and enough layers, escape to them, since not every ball can get out on the
  surface;
- without them, use small, tight vias so that every ball can get out and none is trapped.

## 1. What the measurements say

Six boards with BGAs, 6-minute cap, optimizer off, default settings. The open connections come from the
`open_connections` list in the result JSON.

| Board | Layers | BGAs | Open | Open at a BGA pin | State |
|---|---|---|---|---|---|
| logicbone | 8 | caBGA-381 0.8 mm, 2x FBGA-78 0.8 mm | 461 | 286 | timed out in fanout |
| sdi_mipi | 6 | BGA100 1.0 mm, BGA-80 0.65 mm | 80 | 57 | timed out |
| reworkctf | 4 | BGA-256 full array 1.0 mm | 6 | 6 | timed out |
| DoroidOscillo | 4 | TFBGA100 0.8 mm | 3 | 3 | completed, 110 s |
| ddr5_testbed | 6 | BGA-82 0.8 mm | 8 | 5 | completed, 354 s |
| hard6f (synthetic) | 6 | BGA-144 0.8 mm | 5 | 3 | timed out |

- BGA pins are 360 of the 563 open connections.
- logicbone never left the fanout stage: its 20 passes re-fanned the same pins (1377, 643, 479, ... 323 per pass),
  each pass ripping the escapes of the one before, and no routing pass ran.
- The opt-in planned fanout, same boards: ddr5 8 -> 4 open (354 -> 281 s), sdi_mipi 80 -> 64, reworkctf 6 -> 6 but
  completes, DoroidOscillo 3 -> 4, logicbone 461 -> 607. Its own placement is fast (1166 of 1396 logicbone pins in
  2.6 s). The eager passes that follow fanned 285, 208, 210, 218, 227, 249, 252, 229 pins per pass: the 230 pins
  that got no site rip their neighbours' escapes, which are re-fanned in the next pass (section 2, item 2).
- Escape window test on the same jar: `--router.fanout.min_escape_length_mm=0.3 --router.fanout.max_escape_length_mm=10`
  took logicbone from 461 to 340 open, and its fanout stage ended for the first time (6 passes, 207 s, 88.2% of
  pins escaped), so routing passes ran. sdi_mipi went from 80 to 73. The passes still re-fanned 439, 307, 266, 248,
  240 pins, which is the rip-up of section 2, item 2.
- A dog-bone via in the diagonal gap between four balls fits on every BGA here except one, using the smallest via
  in the board's rules: pad/2 + clearance + via/2 against pitch x 0.707. The exception is sdi_mipi's 0.65 mm BGA-80
  (needs 512 um, has 460): its smallest via is 625 um, so it needs a via in the pad or a smaller via than the DSN
  offers.
- Between two adjacent dog-bone vias exactly one trace fits on logicbone (pitch 800 - via 500 - 2 x clearance 80 =
  140 um for a 135 um trace). Each channel between two via columns carries one escape per layer.
- "Open at a BGA pin by ring" is not an escape measure. On logicbone the open pins sit in rings 0-2 (81, 93, 98 of
  135, 120, 96 pins) while rings 6-7 are nearly closed, because inner balls on plane nets connect to their
  neighbours pin to pin and count as routed. The planner's metric is "balls with a via" per ring, read from the
  SES, plus the open count.
- No board in the set defines a blind or buried via. Testing that part needs a variant DSN (logicbone, 8 layers).

## 2. What the code does today

1. **Fanout is pin by pin** (`BatchFanout`, `RoutingBoard.fanout`). A fanout via must lie between
   `min_escape_length_mm` and `max_escape_length_mm` from the pin centre (defaults 2.5 and 4.5 mm, enforced in the
   `MazeSearchEngine` open list). The minimum is a distance from the pin centre to the drill, so on a 0.8 mm pitch
   the nearest legal via is three pitches away and the diagonal gap at 0.57 mm is never tried. That, not the missing
   grid detection, is why the eager fanout cannot escape a BGA (measured in section 1). A pin whose connected set
   already leaves its layer (`connectedSetLeavesLayer`) is skipped as connected, so a pin with an escape is not
   re-fanned.
2. **Escapes of other pins are ripped during fanout.** `MazeRipupResolver.checkRipup` protects a fanout via (a via
   on a stub shorter than 400 half-widths ending at an SMD pin, `Item.isFanoutVia`) only outside fanout
   (`lookIfFanoutVia && !ctrl.isFanout`) and only while ripup costs are at most twice the start costs
   (`preserveFanoutProtection`). So a pin that found no site rips its neighbours' escapes at base cost, and the next
   pass re-fans them. This is the oscillation on logicbone, and it exists on boards without a BGA.
3. **Nothing detects a pin grid.** `PlannedFanout` knows only the component centroid; in a grid it tries outward
   and side sites before the diagonal gap, and takes the first via of the rule that leaves the pad layer
   (`escapeVia`), whatever its span.
4. **Blind vias were accepted only where a through via fits** (fixed 2026-10-21, batch A item 3). Two causes:
   `DrillPage.getDrills` cut the via sites by the copper on every layer before any layer check ran, so a site
   under a bottom-layer track never existed, and `expandToOtherLayers` returned early when the drillable run at
   the site did not reach the drill's first or last layer. Now the page computes its sites once per distinct via
   span of the net's rule, cut only by that span's layers (`AutorouteControl.viaSpans`), and the mask loop limits
   each via to the drillable run. A board with through vias only takes the old path (one span, same SES).
5. **Every via costs the same.** `addViaCosts[from][to]` is all zero, but a flat `minNormalViaCost` is added at
   every drill entry (`MazeExpansionEngine`, two places), and `DestinationDistance` uses the same flat cost as its
   lower bound. A microvia and a through via therefore cost alike.
6. **Via-in-pad is forced on by the maze.** `AutorouteControl.rebuildViaInfo` sets `attachSmdAllowed = true` for
   any net with an SMD pin on a multilayer board, whatever the DSN says, and `DrillPage` puts a drill at the pin
   centre when attach is allowed. `PlannedFanout.tryInsert` honours the raw DSN flag instead. The two disagree.
7. **Dangling escapes survive the passes under the fanout stage.** `removeTails(FANOUT_VIA)` between passes keeps
   fanout vias because `removeUnconnectedVias` is false when fanout is enabled (`BatchAutorouter`); the final
   `removeTails(NONE)` removes the unused ones. With `fanout.enabled=false` the escapes would be deleted after
   pass 1, so a BGA planner has to run as part of the fanout stage.
8. **Fixed states do not protect against rip-up.** `SHOVE_FIXED` only stops shoving (`Via.isRoutable` checks
   `isUserFixed`), and `USER_FIXED` would also block `removeTails` and be written to the SES as fixed.

## 3. Blind and buried vias: format and code

- **The DSN already carries them.** A padstack has a shape per layer, and `Padstack.fromLayer()/toLayer()` take
  the span from the layers that have a shape, so a padstack with shapes on layers 2-5 is a buried via. The SES
  writer puts the span back the same way. `(attach on|off)` on a padstack and `via_at_smd` in the rules are both
  parsed.
- **Per-net assignment is by net class, as in the spec.** `(via_rule name padstacks...)` in `network` and
  `(rule (via_rule name))` in a class are parsed and applied (`Network.insertViaRules`, class scope), and so is
  `(circuit (use_via ...))` inside a class, which is the form KiCad's export writes (`fixtures/FixedTrackJoin.dsn`).
  Every net in the class may use the rule's vias. There is no via-type setting: `allowed_via_types` is a Boolean
  (`RouterSettings.viasAllowed`). The only way a user "allows" a blind via is to list its padstack in a via rule.
- **KiCad's DSN export likely writes one through via per net class** (not verified in KiCad's source), so blind
  and buried padstacks reach Freerouting only when added to the DSN by hand or by a script. The variant DSN in
  section 6 is that script's first output.
- **What is missing is in the router** (section 2, items 3-5): acceptance at a partly blocked stack, a cost that
  prefers the shorter span, and a via choice by fit rather than list order.

## 4. The plan

### 4.1 Fanout fixes that need no planner

- **Escape window by pitch.** The minimum escape length becomes the smaller of the setting and a fraction of the
  pin's distance to its nearest neighbour pin on the component (so a BGA ball may drill in its diagonal gap and an
  isolated pad keeps today's window). The maximum does not apply to the search from the via outward (item 4.4).
- **No rip-up of another pin's escape during fanout.** While `ctrl.isFanout`, `checkRipup` returns -1 for a fanout
  via or its stub (`Item.isFanoutVia`, `calcFanoutViaRipupCostFactor` already identify them). After fanout the
  normal protection applies. A pin that still finds no site fails and is not retried in later passes unless its
  component changed (`retryFanout` already does this).

### 4.2 Grid detection (per component, from the board only)

A component is a BGA when its SMD pins on one layer sit on a regular lattice: at least 4 x 4 positions, at least
half of them occupied, one pitch in x and y. Record per pin its row, column and ring (distance in pitches to the
nearest edge of the lattice, 0 = outer). Depopulated centres and missing balls are allowed. Everything else keeps
the fanout of 4.1, so non-grid boards do not change beyond 4.1.

### 4.3 Via sites and which balls get copper

- Every ball with a net gets a via site reserved in one of the four diagonal gaps next to it, by a matching per
  component (each gap serves one ball; prefer the gap pointing away from the part's centre, then the one that
  keeps a row of gaps free as a channel), not first-come. A reserved site is not inserted for a ball that can route
  on the surface, but it is not given away either, so no ball is trapped later.
- Copper (a stub to the via) is inserted up front for ring 1 and inward, and for balls on plane nets in any ring
  (they connect down to the plane and never need a surface channel). A ring-0 ball keeps its reservation and gets
  the via only when its surface route fails. Inserting ring-0 vias up front would cost inner-layer channels for
  routes that may never need them.
- The dog-bones are ordinary unfixed copper, as `PlannedFanout` places them now, protected during fanout by 4.1.

### 4.4 Which via, and on which layer

In order:
1. **Blind or buried vias**, when the net class's via rule lists a padstack whose span is a proper subset of the
   board. Ring k escapes on inner layer L(k), outer rings on the shallowest layers, and the ball gets the shortest
   via from its pad layer to L(k), so the layers below stay free for deeper rings. Layer assignment is a capacity
   check: a layer carries as many rings as its channels hold (one escape per channel between two vias when
   pitch - via - 2 x clearance >= width, two when it holds two), then the next ring goes one layer deeper. The
   escape direction (which side each ball leaves by) is chosen with the layer, as a ball-to-channel matching per
   layer, because two rings on one layer compete for the same channels. The site matching, the layer check and
   the direction matching are one small bipartite matcher.
2. **Otherwise the smallest through via** of the net's rule that fits the diagonal gap.
3. **If none fits** (the 0.65 mm case): report the balls as needing a smaller via or via-in-pad, with the required
   diameter. Via-in-pad is not used unless the DSN allows it (section 5, decision 4). An opt-in
   `--router.fanout.bga_via=<padstack>` names a padstack the planner may use only for BGA escapes. The router
   never invents a via size, since that changes what the board house must drill.

### 4.5 Router changes the plan depends on

- **Blind vias at a partly blocked stack:** delete the two early returns in `expandToOtherLayers`. A board with
  only through vias keeps its routes (no mask fits a partial run), which the SES diff checks.
- **Via cost by span:** keep the flat `minNormalViaCost` (it is the heuristic's lower bound) and add an increment
  in `addViaCosts[from][to]` proportional to the layers drilled, so a microvia is cheaper than a through via.
- **No escape window for the search from a planned via outward:** it is ordinary routing with no length limit,
  which covers the long run from a centre ball to outside the part.
- **Via-in-pad under one rule:** honour the DSN attach flag in the maze as the planner does, and make today's
  forced relaxation an opt-in setting. This changes routes on SMD boards and is measured on its own.
- Unused escapes are removed with the other dangling vias at the end, as today.

## 5. Decisions from the review

1. **Surface or via per ball:** reserve for every ball, insert copper from ring 1 inward and for plane nets (4.3).
2. **Direction with layer:** yes, one matcher for sites, layers and directions (4.4).
3. **Protecting the dog-bones:** by the fanout rip-up rule (4.1), not by a fixed state (section 2, item 8).
4. **Via-in-pad for the 0.65 mm case:** not by default; honour the DSN flag and report the need (4.4, 4.5).
5. **Skipping planned pins in the eager passes:** not needed, the skip exists (section 2, item 1).

## 6. Order of work and measurement

The steps form one batch (roadmap section 8, benchmark policy): each is one commit behind its own gate with a fast
test, and the board runs come once at the end of the batch, all gates on and all gates off, with the fast and
hard groups at their caps (`bench/overhaul/run-batch.sh`). The report per board is the open count, the open count
at BGA pins, and balls with a via per ring from the SES. A regression is bisected by gate on that board only.

1. Escape window by pitch (4.1). Routes change on boards with dense pins; expected to fix most of logicbone.
2. No rip-up of another pin's escape during fanout (4.1). Also targets the eager oscillation on non-BGA boards.
3. The two early returns in `expandToOtherLayers` (4.5). Same SES on all twelve boards.
4. Grid detection, site matching and dog-bones with through vias (4.2, 4.3, 4.4 item 2), opt-in first
   (`--router.fanout.strategy=bga`), default only after the measurements.
5. A logicbone variant DSN with a microvia padstack (layers 0-1) and a buried padstack (layers 2-7) in a via rule
   on a class, as a fixture test; then layer and direction assignment with blind and buried vias (4.4 item 1).
6. Via cost by span (4.5).
7. Via-in-pad under one rule (4.5), measured on the SMD boards of the set.
