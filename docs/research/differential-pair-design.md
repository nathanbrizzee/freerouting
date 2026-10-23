# Differential pairs and impedance-matched traces: design

Written 2026-10-23 for the router-overhaul branch. It expands roadmap section 7.4a into a buildable plan. The
Specctra facts come from the reference in `docs/reference/` (v10, May 2000). The routing practice comes from vendor
application notes, Cadence, Altium, Siemens and KiCad documentation, and the routing literature; sources are listed at
the end. Numbers quoted from vendor notes are marked as such and are not read from the interface standards themselves.

## 1. What we are solving

The user's manual flow on a high-speed board, which the router should reproduce:

1. Route power and ground.
2. Route the differential pairs and the impedance-matched traces, and lock them. Power and ground may move if a
   pair is blocked.
3. Route everything else.
4. When another trace or via needs the space a pair holds: unlock that stretch of the pair, route the new trace and
   its vias, re-route the stretch of the pair, check its gap and length, lock it again.

The pairs get the most care because two things on them are failures, not warnings: a wrong gap (wrong impedance)
and a length difference between the two members (skew). Pairs must finish if the pins allow it, and may take more
time than ordinary nets, but not unbounded time.

Today Freerouting has none of this. It reads a per-class minimum and maximum net length and reports it in DRC and
interactive routing; the autorouter ignores even that. A pair is two unrelated nets. The parser skips `(pair ...)`
without a word. Hand-routed pairs survive a run only because imported routes used to be fixed; with
`session_routes_movable` on (every profile but classic) a continued board's pairs can now be ripped one member at a
time, which is worse than before for these nets.

## 2. Terms

| Term | Meaning here |
|---|---|
| Pair | Two nets routed side by side: P and N. |
| Coupled gap | Edge-to-edge distance between the two members where they run together. Sets the differential impedance together with width and the stackup. Held constant, with a small tolerance. |
| Uncoupled length | The total length over which a member runs farther than gap plus tolerance from its partner: pad breakouts, via necks, tuning bumps. Budgeted per pair. |
| Gather point | Where the two breakout stubs from the pads (or from a via pair) reach the coupled gap. |
| Skew (intra-pair) | Length, or delay, difference between P and N. The budget is a few mils on fast links. |
| Matched group | Several nets or pairs whose lengths (or delays) must agree within a tolerance: a byte lane, a clock against its data, lanes of a link. |
| Impedance-matched trace | A single-ended trace with a controlled width per layer, often also with a length rule. Everything below about length and protection applies to it; only the pairing does not. |
| Delay versus length | On FR-4 a stripline carries about 170 to 180 ps per inch, a microstrip about 140 to 150 ps per inch (vendor figures). Matching length across different layer types matches delay only to about 20 percent. Specctra's `length_factor` per layer exists for this. |

## 3. What the file formats carry

### 3.1 Specctra DSN (verified against the v10 reference)

Pairs, inside `(network ...)`:

```
(pair {[<wire_pair_descriptor> | <net_pair_descriptor>]})
(nets  <net_id> <net_id> {[(gap [<dim> | -1] {[(layer <layer_id>)]})]})       ; net pair
(wires <fromto> <fromto>  {[(gap [<dim> | -1] {[(layer <layer_id>)]})]})       ; pin-to-pin pair
(bundle <id> (nets {<net_id>}) {[(gap [<dim> | -1] {[(layer {<layer_id>})]})]}) ; n nets side by side
```

- A pair carries only `gap`, per layer if wanted. No gap means the wire-to-wire clearance; -1 resets to it.
- `?` wildcards pair many nets at once: `(nets sig?A sig?B)`.
- The pair descriptor has no rule slot. Skew, uncoupled length and width are not pair properties in v10. Later
  Allegro PCB Router versions may have added keywords; we have no sample that shows them.

Length and delay, inside `(circuit ...)`, allowed on a net, class, group, group_set or fromto:

```
(length <max> [<min>] [(type [ratio | actual])])        ; -1 = no maximum; ratio = routed / Manhattan
([max_delay | min_delay] <real>)                        ; in (time_resolution psec|nsec|... <n>) units
([max_total_length | min_total_length] <dim>)           ; groups: sum of the group's fromtos
([max_total_delay | min_total_delay] <real>)
(match_net_length [on|off] [(tolerance <dim>) | (ratio_tolerance <real>)])   ; class only; default tolerance one inch
(match_net_delay  [on|off] [(tolerance <real>)])                             ; class only
(match_fromto_length [on|off] [(tolerance ..) | (ratio_tolerance ..)])       ; nets, classes, groups
(match_group_length / match_group_delay ...)                                 ; group_set
(relative_length / relative_delay ... (fromto <pin> <pin> (delta <dim>)))    ; against a reference fromto
(priority <1..255>)   (use_layer {<layer>})   (use_via {<padstack>})
```

```
(group <id> {<fromto>} [<circuit>] [<rule>] [{<layer_rule>}])
(group_set <id> {<group_id>} [<circuit>] [<rule>] [{<layer_rule>}])
(fromto <pin> <pin> [same] [(type [fix|normal|soft])] [(net <net_id>)] [<rule>] [<circuit>] [{<layer_rule>}])
```

So a matched bus is a class with `match_net_length`, or a group of fromtos with `match_fromto_length`. Pair skew has
no keyword of its own in v10: it is a two-net class with `match_net_length`, or a group of the two fromtos.

Tuning geometry, inside `(rule ...)` at any level:

```
(length_gap <dim>)                 ; minimum gap between meander segments; below 3 x width the router uses 3 x width
(length_amplitude <max> [<min>])   ; peak-to-peak excursion of an accordion; 0 forbids meanders, -1 unsets
(length_factor <real>)             ; per-layer length weight, for controlled impedance across layer types
(time_length_factor <real>)        ; delay per unit length, for delay rules
(effective_via_length <dim>)       ; what a via adds to a connection's length
(parallel_segment (gap <dim>) (limit <dim>))   ; same-layer parallel run limit, stackable as a table
(tandem_segment   (gap <dim>) (limit <dim>))   ; adjacent-layer parallel run limit
```

Precedence, from the reference: pcb < layer < class < class layer < group_set < ... < net < net layer < group <
group layer < fromto < fromto layer < class_class < ... < region. Pairs are outside this chain; the pair gap is an
extra minimum between the two paired wires.

Impedance is out of scope for DSN. There is no stackup, dielectric or impedance keyword. The designer's tool computes
width and gap for the target ohms and the DSN carries them as class widths (per layer through `layer_rule`) and pair
gaps. The session file (SES) carries routes only, no rules.

### 3.2 KiCad

- A pair is two nets with a common base name and the suffixes `+`/`-` or `P`/`N` (`_P`/`_N` is the usual form);
  the styles may not be mixed. Each net class has a differential-pair width and gap, and `.kicad_dru` rules can set
  `diff_pair_gap` (min, max; checked only on coupled portions), `diff_pair_uncoupled` (max), `skew` and `length`.
- KiCad's Specctra exporter writes none of it: no `(pair)`, no pair width or gap, no length rule, no custom rule.
  The SES importer reads routes only. This is confirmed in the KiCad 9/10 sources (`specctra_export.cpp`).
- KiCad imports autorouted tracks as plain tracks. Meanders we draw are not KiCad tuning-pattern objects, so KiCad
  cannot re-tune them, but its DRC checks them.

Three ways the rules reach Freerouting from a KiCad board, all to be supported:

1. Pair detection by name, with widths and gaps from a sidecar. Upstream now does the name part (3.3).
2. A DSN with pair and length rules added. `bench/overhaul/dsn_pairs.py` already writes `(pair (nets P N (gap g)))`
   from the `.kicad_pro` net classes. The same script can write `match_net_length` classes and `length` rules from
   `.kicad_dru`. An agent runs it before the router, the way `dsn_hv.py` adds `class_class`.
3. The KiCad JSON input (`management/BoardLoader.java`), extended with net-class pair width and gap and the
   `.kicad_dru` constraints.

### 3.3 Upstream Freerouting since our branch point

Upstream master (2026-10-23, 102 commits ahead of `router-overhaul`) merged two relevant pull requests:

- #977 reads `length_amplitude` and `length_gap`, plus non-standard `corner_style`, `radius`, `single_sided` and a
  `meander_pattern` scope, into `rules/NetMeanderConstraint` with a four-level fallback (net, class, board preset,
  settings), and writes them back.
- #980 adds `rules/DifferentialPair` (positive net, negative net, base name, coupled spacing, max skew),
  `rules/DifferentialPairs` (index by base name and net) and `rules/DifferentialPairDetector` (suffixes `_P/_N`,
  `_DP/_DM`, `_D+/_D-`, `_+/_-`, `_POS/_NEG`, `.P/.N`, scoped `D+/D-`; power rails excluded), run on board load.
  Nothing routes with them yet; the architecture doc calls them the envelope for a future tuning and pair phase.

We build on these types instead of inventing our own, so a later upstream pull request stays small. Section 7 asks
whether to merge upstream master into the branch first.

## 4. Impedance: what a router can and cannot do

The width and the gap are the designer's. The router's job is to keep them exactly: same width on a layer, the
class's width for each layer, no neck-down on a controlled net, the coupled gap constant, the pair on one layer
between via pairs, no single-member layer change.

A sanity check is possible when the stackup is known (KiCad's `.kicad_pcb` carries it; DSN does not). The common
closed forms, all with W trace width, T copper thickness, H height to the nearest plane, B plane-to-plane spacing, S
edge-to-edge gap, Er relative permittivity:

- IPC-2141 microstrip: `Z0 = 87 / sqrt(Er + 1.41) * ln(5.98 H / (0.8 W + T))`, for 0.1 < W/H < 2, about 5 to 7
  percent error on FR-4 below a few GHz; it fails badly for wide traces.
- IPC-2141 symmetric stripline: `Z0 = 60 / sqrt(Er) * ln(4 B / (0.67 pi (0.8 W + T)))`, W < 0.35 B.
- Edge-coupled differential (National AN-905 fits): microstrip `Zdiff = 2 Z0 (1 - 0.48 exp(-0.96 S/H))`, stripline
  `Zdiff = 2 Z0 (1 - 0.347 exp(-2.9 S/H))`, with Z0 the single trace from above. Treat as plus or minus 10 percent.
  For S > 3 W the coupling term is small and Zdiff is about 2 Z0.
- Better: Hammerstad-Jensen and Kirschning-Jansen coupled microstrip (under 1 percent, no metal thickness, about 40
  lines of code) and Wadell or Cohn for stripline. Not invertible in W, so solving for width is a search.
- Er: FR-4 nominal 4.2 to 4.5 and falling with frequency; the batch spread alone is about 5 percent.

The check would only warn when the supplied width, gap and stackup give a Zdiff more than about 15 percent from the
class's stated target (KiCad classes are often named `90ohm`, `100ohm`). The fabricator's field solver is the
authority. This is phase 5 and optional; nothing else depends on it.

Coupling choice, for defaults when a board gives a width but no gap: tight coupling (S about W) saves space and
rejects common-mode noise but makes Zdiff sensitive to etch; S = 2 W is the common compromise; S of 3 W or more is
loose, Zdiff about 2 Z0. A constant gap matters more than the tightest gap. Default gap when none is given: the
class clearance, as Specctra says, and a warning that the pair has no gap rule.

## 5. Practice, turned into router rules

| Practice | Router rule |
|---|---|
| Hold the gap; bends cannot hold it exactly | Gap is a target with a tolerance (default 10 percent of the gap, Allegro-style), checked on coupled stretches only. |
| Keep uncoupled stretches short | Per-pair uncoupled budget: breakouts, via necks, bumps and separations around obstacles all count. Default when no rule: 4 x (W + S) per end plus 2 x (W + S) per via pair; configurable. |
| Symmetric pad entry, gather point as close to the pads as possible | Plan the gather point before routing (Mentor's US6077309 sweeps eight directions for a clear slot; our `BgaEscapePlanner` already plans pin exits). Stubs equal in length and shape where the pads allow. |
| Via pairs, same count on both members, a return via nearby | A layer change is one object: a via pair at the via gap, perpendicular or staggered along the direction of travel. Each via pair starts a new matched segment. A ground return via is a later option (needs a plane net). |
| Stay on one layer; never cross a plane split | Layer change cost high for pairs; plane-split awareness comes with the plane model, later. |
| 45 degree bends; compensate skew where it is made | The inner member is shorter at every bend by about 2 (W + S)/2 tan(theta/2) per bend. Alternate bend directions when the path allows; otherwise add one bump on the shorter member right after the bend, inside the same via-to-via segment. |
| Tune within each pair first, then between pairs or across a group | Two passes: phase (intra-pair) tuning, then group tuning. |
| Meanders: segment spacing 3 W or more (4 W in Allegro guides), amplitude at least 3 W, 45 degree or filleted corners, not under parts, away from vias and pads, bump height under about 2 x gap on a pair | Use `length_gap` and `length_amplitude` when given, else these defaults. Sawtooth (45 degree bumps) for pairs, accordion for single traces, trombone never on pairs. |
| 3 W to single-ended neighbours, 5 W between a pair and other tracks, more for clocks | Already expressible as clearance classes (`class_class`); the agent or `dsn_hv.py` sets them. The pair's own class clearance applies to the virtual trace in 6.3. |
| Polarity swap is cheaper than a crossover | Report a crossed pair at the gather point; cross in the breakout only if the stubs allow it, never with a lone via. |
| Skew budgets (vendor figures) | PCIe, USB 3, MIPI about 5 mil (0.13 mm); USB 2, LVDS, Ethernet 20 to 50 mil; DDR4 strobe pairs about 15 mil, byte lanes tens of mils; inter-pair tolerances 10 to 100 times looser and often not required. Expose skew per pair in mm or ps; never hard-code these. |

## 6. Strategy

### 6.1 Detection and activation

On load, collect the critical nets: pairs from `(pair)`, `(bundle)` and the name detector; length and match rules
from `(circuit)`; pair width and gap from the KiCad side when present. Log one line: `Critical nets: 32 pairs (30
with a gap rule), 2 matched classes, 4 nets with a length rule`. Report them in `result_json`.

When any are present and the run named no profile, the run uses `thorough` and logs why; a named profile is kept. A
new setting `router.critical_nets=auto|on|off` (default `auto`: on when critical nets exist) switches the whole
machinery; `off` gives today's behaviour for comparison. Nothing depends on time, so determinism holds.

### 6.2 Order of work

The manual order was power, pairs, rest. For an autorouter the pairs go first, because an empty board gives them the
straightest paths, the fewest bends and the least skew to tune, and because tuning needs free space beside the pair,
which exists only before the rest of the board is routed. Power follows, with the pairs protected; power traces are
wide and prefer different corridors, and when a pair blocks one the transactional move in 6.6 applies. The order:

1. Fanout and planned escapes (as today), pairs escaping as pairs (6.4).
2. Critical nets: pairs, then single impedance-matched or length-ruled nets, each with a work budget several times
   an ordinary net's (setting `router.critical_work_factor`, default 4, counted in maze steps, deterministic).
   Hardest first: longest Manhattan length, then most crowded (the crowded-region map exists).
3. Phase tuning per pair, then group tuning (6.5), while the board is still mostly empty.
4. Power and ground, then every other net, with critical copper protected (6.6).
5. Final measurement and verdict (6.7).

### 6.3 Coupled routing: one centreline, split afterwards

This is the "wide trace then split" idea from the roadmap. Siemens Xpedition documents routing pairs "by
centerline"; it is the method that fits a shape-based maze router best, because the search stays the one we have.

- Route a virtual trace along the pair's centreline with half-width `(2 W + S) / 2` and the pair's clearance class.
  Its outer edges coincide with the outer edges of the finished pair, so every clearance check is exact.
- The virtual trace may not neck down, may not change width per layer except to the class's per-layer pair width,
  and changes layer only through a virtual via whose diameter covers both vias at the via gap.
- Split: offset the centreline by plus and minus `(W + S) / 2`. At a bend of angle theta the inner member is shorter
  by `(W + S) tan(theta / 2)`; the splitter records the skew each bend adds, by segment, for the tuner.
- Via pairs: the virtual via becomes two vias perpendicular to the direction of travel (or staggered along it if the
  perpendicular placement collides); the members swap sides at a stagger only if the gather logic asks for a crossover.
- At each end the virtual trace ends at the gather point, and the breakout from the gather point to the two pads is
  routed as two short ordinary traces, each limited to the uncoupled budget and checked for symmetry.
- Why not route P and let N shadow it: the shadow fails exactly where P hugged an obstacle, and fixing that is a
  second search with a worse start. The centreline search is one search of ordinary cost and guarantees room for
  both members. fastroute (a Freerouting port) uses the shadow method; the Mentor patent and Siemens use gather
  points and a centreline.

Where it lives: a `PairRouter` beside `DirectPinRouting` and `BgaEscapePlanner` in `autoroute/pipeline`, a
`CoupledTraceSplitter` in `autoroute/path`, and a `ViaPairPlacer`. The maze search gets a pair-aware
`AutorouteControl` (virtual width, no neck-down, via-pair padstack). Ripping during the pair search is allowed only of
non-critical copper.

### 6.4 Gather points and pad escapes

For each end of a pair: the two pad centres, their exit directions (pad shape, neighbours, keepouts), and the pair
direction of travel give a candidate gather point a few widths out, with the two stubs mirror images where possible.
Score candidates by stub length difference, total stub length and crowding; keep the best that the maze can reach.
A BGA pair escapes as a pair: both balls to the same escape row, via pair at the via gap, which the escape planner can
be taught (the roadmap already lists it).

### 6.5 Measuring and tuning length

Measure per connection, not per net: trace length plus `effective_via_length` per via, weighted by the layer's
`length_factor` when given, with delay through `time_length_factor` when a delay rule applies. A `NetLengthReport`
gives each net, pair and group its length, skew and the rule it must meet. This is also what DRC and the verdict
print, so the user sees skew and group mismatch even before any tuning exists.

Tuning, after the critical nets are routed and before the rest:

1. Phase (intra-pair): for each via-to-via segment of a pair, the recorded bend skew plus measured stub asymmetry gives
   the shortage of the shorter member. Add one sawtooth bump of that length on the shorter member just after the bend
   that caused it, height under 2 x gap, corners at 45 degrees, spacing `length_gap`. If the segment has no room,
   use the next segment toward the receiver and note it.
2. Group (inter-pair and matched classes): the target is the longest member of the group (or the rule's window); for
   each shorter net or pair add accordions (sawtooth for pairs) along the straightest coupled stretch with free space
   on one side. Each bump adds about `2 A - 2 (W + S) (1 - tan(22.5 deg))` for 45 degree corners; iterate until within
   tolerance, then confirm with the measurement, not the estimate.
3. Free space comes from the board's shape search tree, the same query the expansion rooms use; amplitude is cut to
   what is free, like Altium's tuner. Keep `3 W` from vias and pads and stay out of component courtyards when the
   board gives them.
4. The literature's view, which this follows: assign a region beside the short trace, then meander inside it
   (Ozdal and Wong; Yan and Wong's BSG-Route; the DAC 2024 any-angle method). Dense meanders couple to themselves and
   the signal arrives early, so spacing stays at least 3 W (ICCAD 2013).
5. A pair tuned for length moves as a pair: an accordion on a pair bends the centreline, and the splitter rebuilds
   both members.

`length_amplitude 0` forbids meanders, as the spec says; the net is then reported, not tuned.

### 6.6 Protection and the transactional move

Critical copper (pair members, their via pairs, length-ruled nets, their meanders) is not ordinary copper during the
rest of the run:

- The maze's rip-up may not rip a single member or a tuning bump, and the shover may not move one member.
- A critical net may move only as a whole and only inside a transaction, the router's version of the manual unlock,
  re-route, lock: when an ordinary connection fails and the blocker analysis (`LastMileBlockerRipup`,
  `RetryNeighbourhood`) names critical copper as the only blocker, snapshot the board, remove the pair's blocking
  via-to-via segment, route the ordinary net, re-route the segment with the coupled search, re-measure gap, uncoupled
  length, skew and group length, retune if room allows, and keep the result only if every rule still holds. Otherwise
  restore the snapshot and leave the ordinary net open with the reason recorded. The grid finisher's "kept only when
  fewer stay open" already uses this pattern.
- The optimizer treats a pair as one object: it may shorten the centreline and must re-split and re-check skew; a
  net with a length window may not leave the window. Until that is built, the optimizer skips critical nets.
- Locked (fixed) copper stays untouchable, as today.

### 6.7 Report and verdict

The verdict and `result_json` gain: pairs found, routed, open with reasons; per pair gap violations (length over
tolerance), uncoupled length against budget, skew against budget, bumps added; per group the spread against
tolerance; nets whose rules could not be read. The agent guide explains how an agent fixes each: add a gap rule,
loosen a tolerance, swap polarity at the transceiver, move a part.

### 6.8 Budgets and giving up

Pairs get `critical_work_factor` times an ordinary connection's work, plus the fallback race in `thorough`. A pair
that stays open after that is reported with its blocker and the run goes on; the user asked for ample effort, not
unbounded. A tuner that finds no room reports the shortfall in length and in ps.

## 7. Phases, tests and measurements

Each phase is its own batch with unit tests, gated by a setting, and measured on the pair boards once per batch.

| Phase | Builds | Tests | Measure |
|---|---|---|---|
| 0. Sync | Decide whether to merge upstream master into `router-overhaul` first (102 commits, among them #977 and #980). If not, port the three `rules` classes and the `length_gap`/`length_amplitude` parsing. | Existing suite | Set unchanged |
| 1. Read, measure, protect | DSN `(pair)` with per-layer gaps and `?` wildcards, `(wires)`, `(bundle)`, the `(circuit)` family including `type ratio`, `match_*`, groups, `length_factor`, `effective_via_length`; KiCad JSON pair width, gap and `.kicad_dru` constraints; `dsn_pairs.py` extended to write match and length rules; `NetLengthReport`; DRC checks for gap, uncoupled, skew, group; protection of critical copper from single-member rip and shove; verdict and `result_json` fields; `critical_nets=auto` and the automatic `thorough`. | Hand-written DSNs that use each construct once; a continued board with hand-routed pairs must come out byte-identical in those nets | Pair boards: today's open count must not get worse; hand-routed pairs survive |
| 2. Coupled routing | Centreline search, splitter, via pairs, gather points, pair escapes, critical-first order and work factor | Unit tests on the splitter (bend skew formula), the via pair placer, the gather chooser; fixtures from CM5 and StickHub | Pairs routed, gap violations, uncoupled length; open count on the set |
| 3. Tuning | Measurement with via and layer factors; phase bumps; group accordions; free-space query | Unit tests on bump geometry and length arithmetic; KiCad DRC with `skew`, `length`, `diff_pair_gap` rules on the test boards as the judge | Skew and group spread before and after; CPU |
| 4. Transactional move and optimizer | Unlock, re-route, re-check, relock; optimizer pair object | A board where an ordinary net can only finish through a pair's corridor | Open count and CPU on the set |
| 5. Impedance and delay | Optional Zdiff check from the KiCad stackup; delay rules through `time_length_factor` | Formula tests against published calculator values | None (advisory) |

Test boards, already prepared in `bench/overhaul/boards/*_pairs.dsn` (roadmap 7.4a): StickHub (2 layers, 8 USB
pairs), CM5_MINIMA (6 layers, 32 pairs, 50/90/100 ohm classes; also the upstream `Issue732` fixture), Antmicro
SDI-MIPI (13 pairs), Jetson Orin baseboard (104 pairs, 12 gap rules in its `.kicad_dru`), DDR5 testbed (clock and
strobe pairs, data groups for length matching), and the Jetson AGX Thor baseboard as the stretch test. Judge with
KiCad DRC after SES import, and with our own `NetLengthReport`, which must agree with KiCad's length and skew numbers.

Sizes to expect: phase 1 about 2,000 lines with tests, phase 2 about 3,000, phase 3 about 2,000, phase 4 about 1,000.

## 8. Decisions wanted from the user

1. Phase 0: merge upstream master into the branch before starting (one-time cost, keeps a future pull request small,
   may shift our benchmark numbers), or port only the pair and meander classes.
2. Defaults when a board gives a pair but no rule: gap tolerance 10 percent, uncoupled budget as in section 5, no
   skew tuning without a skew rule but always report skew. Agree, or set other defaults.
3. The automatic switch to `thorough` when critical nets exist: on by default, as asked, unless a profile is named.
4. Whether `dsn_pairs.py` becomes part of the shipped `scripts/`, so an agent can add KiCad's pair, skew and length
   rules to a DSN before a run, until KiCad exports them itself.

## 9. Sources

Specctra: `docs/reference/Cadence_SPECCTRA_Design_Language_Reference_v10_(2000-05).md`, sections `pair_descriptor`,
`net_pair_descriptor`, `wire_pair_descriptor`, `bundle_descriptor`, `circuit_descriptors`, `match_*`, `length_*`,
`group_descriptor`, rule precedence. KiCad: `pcbnew/specctra_import_export/specctra_export.cpp` (KiCad master) and the
KiCad 9 manual, "Routing differential pairs" and "Length tuning". Upstream Freerouting: pull requests #977 and #980,
`docs/architecture.md` on master; issue #358 for a user DSN with `(pair ...)`. Impedance: IPC-2141 as reproduced by
calculator sites; National Semiconductor AN-905; Hammerstad-Jensen and Kirschning-Jansen as given in the Qucs
technical documentation; Bogatin, Signal Integrity Journal, on coupling and loss. Practice: Cadence Allegro
differential pair notes (Parallel Systems, FlowCAD, EMA) and the Cadence forum on static and dynamic phase; Altium
documentation on interactive pair routing, ActiveRoute and tuning patterns; Siemens Xpedition "centerline" pair
routing; Mentor patent US6077309 (gather points); Zuken CR-8000 release notes; Toradex layout design guide; TI
SLLA414, SLAAE45, SNLA387; Microchip AN2054; Intel DDR4 skew guidelines; HilPCB on delay and serpentines.
Literature: Ozdal and Wong, length-matching routing (ICCAD 2003, TCAD 2006); Yan and Wong, BSG-Route (ICCAD 2008);
Yan, Wu, Ma and Wong, escape routing of differential pairs (ICCAD 2010); Yan, length-constrained escape routing of
differential pairs (Integration 2015); Yan and Chen, obstacle-aware length-matching bus routing (ISPD 2011); Fang
et al., obstacle-aware length matching for any-direction traces (DAC 2024); Tsai et al., dense meander alleviation
(ICCAD 2013).
