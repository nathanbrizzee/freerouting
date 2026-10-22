## Freerouting Settings Documentation

Freerouting uses a flexible settings system that allows users to customize various aspects of the application's behavior. These settings can be managed through a JSON configuration file, command-line arguments, or environment variables.

### Settings File (JSON)

The primary way to configure Freerouting is through a JSON settings file. This file contains key-value pairs for different settings and is usually located in the `%temp%\freerouting` directory (`%temp%` refers to your system's temporary folder). The file is created during Freerouting's first run if it doesn't already exist. The `"version"` field is set automatically to match your installed build.

```json
{
  "version": "<installed-version>",
  "profile": {
    "id": "09730e5f-4886-49f0-afba-76f459408907",
    "email": "info@freerouting.app"
  },
  "logging": {
    "console": {
      "enabled": true,
      "level": "INFO"
    },
    "file": {
      "enabled": true,
      "level": "INFO",
      "location": ""
    }
  },
  "gui": {
    "enabled": true,
    "input_directory": "",
    "dialog_confirmation_timeout": 5
  },
  "router": {
    "default_preferred_direction_trace_cost": 1.0,
    "default_undesired_direction_trace_cost": 2.5,
    "autorouter": {
      "enabled": true,
      "max_passes": 100
    },
    "max_threads": 11,
    "improvement_threshold": 2.5,
    "trace_pull_tight_accuracy": 500,
    "allowed_via_types": true,
    "via_costs": 50,
    "plane_via_costs": 5,
    "plane_nets": ["GND", "VCC"],
    "plane_as_obstacle": false,
    "start_ripup_costs": 100,
    "automatic_neckdown": true
  },
  "usage_and_diagnostic_data": {
    "disable_analytics": false,
    "analytics_modulo": 16
  },
  "feature_flags": {
    "multi_threading": false,
    "select_mode": false,
    "macros": false,
    "other_menu": false,
    "snapshots": false,
    "file_load_dialog_at_startup": false,
    "save_jobs": false
  },
  "api_server": {
    "enabled": false,
    "http_allowed": true,
    "endpoints": [
      "http://0.0.0.0:37864"
    ],
    "cors_origins": "",
    "rate_limit": {
      "enabled": false,
      "requests_per_window": 120,
      "window_seconds": 60
    },
    "max_parallel_jobs": 5
  },
  "mcp_server": {
    "enabled": false,
    "http_allowed": true,
    "endpoints": [
      "http://127.0.0.1:37964"
    ],
    "authentication": {
      "enabled": true,
      "providers": ""
    },
    "cors_origins": "",
    "rate_limit": {
      "enabled": false,
      "requests_per_window": 120,
      "window_seconds": 60
    },
    "target_api_base_url": "http://127.0.0.1:37864"
  }
}
```

#### **`version` Section**

- **`version`**: Specifies the version of the settings file.

#### **`profile` Section**

- **`id`**: A unique identifier for the user's profile. This is typically a UUID (Universally Unique Identifier).
- **`email`**: The user's email address (optional).

#### **`logging` Section**

- **`console`**:
    - **`enabled`**: Enables or disables console logging. Default is `true`.
    - **`level`**: Sets the console log level (OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE, ALL). Default is `INFO`.
- **`file`**:
    - **`enabled`**: Enables or disables file logging. Default is `true`.
    - **`level`**: Sets the file log level. Default is `INFO`.
    - **`location`**: Directory path for the log file. If empty, defaults to the user data path.

#### **`gui` Section**

- **`enabled`**: Enables or disables the graphical user interface (GUI). If set to `false`, Freerouting will run in
  headless mode.
- **`input_directory`**: Specifies the default directory for opening design files.
- **`dialog_confirmation_timeout`**: Sets the timeout in seconds for dialog confirmations. Default is `5`. `0` skips the auto-start dialog, so a GUI launched by a script with `-de` and `-do` starts routing at once (CLI: `-dct 0`).

#### **`router` Section**

- **`profile`**: The run profile (CLI `--router.profile=<name>`). A profile is a set of defaults; any setting given in a file, environment variable, the CLI, the GUI or the API still overrides it, and the log names the profile at the start of each job. Profiles set passes, budgets and strategies, never time limits, so runs stay repeatable.
    - `"standard"` (default): the most complete board in the least time. Today: `fanout.strategy=bga`, `min_escape_by_pitch` and `protect_escapes` with both `_grid_only` switches, `autorouter.via_span_cost_factor=0.5`, `autorouter.force_via_in_pad=false`. On the 19-board benchmark set this left 854 open connections against 1239 with `classic`, finished 4 boards against 1, and used less CPU.
    - `"quick"`: a first look in the least wall-clock time: `standard` routing without the fallback stage (`fallback_strategies=none`) and without the optimizer. Use it to see how far a board gets and where it is stuck.
    - `"thorough"`: for finishing a hard board, at more CPU: `standard` plus the fallback race on all cores without an attempt budget (`fallback_race=true`, `fallback_threads=0`, `fallback_work_ratio=0`) and the grid finisher (`grid_finisher=true`). To continue from an earlier result, pass both files: `-de board.dsn earlier.ses`.
    - `"classic"`: the original router's behaviour, with every option of the router overhaul at its hardcoded default.
- **`default_preferred_direction_trace_cost`**: Cost factor for routing traces in the preferred direction.
- **`default_undesired_direction_trace_cost`**: Cost factor for routing traces in undesired directions.
- **`autorouter`**: Batch autorouter stage knobs. Canonical CLI is
  `--router.autorouter.max_passes`. Flat keys (`--router.max_passes`, `-mp`,
  `FREEROUTING__ROUTER__MAX_PASSES`) still apply and warn until they are removed.
    - **`enabled`**: Whether the autorouter stage runs after fanout.
    - **`algorithm`**: Algorithm identifier (`freerouting-router` by default; legacy identifiers such as `freerouting-router-v19` safely normalize to `freerouting-router`, and an unknown name falls back to it with a warning that lists the known names). Known algorithms:
      - `freerouting-router`: the original router (fanout, rip-up passes, fallbacks, optimizer).
      - `freerouting-planned`: a global plan with negotiated congestion first, then the original
        router guided by the plan (`docs/research/global-planner-design.md`). Under construction:
        until the planner lands it routes exactly like `freerouting-router`.
    - **`max_passes`**: Maximum autorouter passes. `0` means no limit.
    - **`max_items`**: Maximum items attempted in the autorouter stage.
    - **`fallback_strategies`**: Comma-separated attempts tried in order when the pass loop stops
      with open connections or new violations. The default is
      `fanout-retry,short-escape,direct-first,open-escape,no-fanout`; `none` turns the fallback off.
      Most of these route the board again from the start, so each one costs about as much as the
      default run. `seed-N` (N a positive integer) routes the input board again with the item order
      of every pass shuffled by `item_order_seed` N; a different order gives a different board.
      When it helps: several seeds side by side (`fallback_threads=0`, `fallback_work_ratio=0`)
      give the best of several tries for about the wall time of one. On eight hard PCBench boards
      (8-minute cap, optimizer off), adding `seed-1,seed-2,seed-3` to the default list in parallel
      left 21 open connections instead of 26 and made one more board clean (EncoderBoard, through
      `seed-1`); on PCBench as a whole the best of four item orders made 18 of 24 boards clean where
      the default order made 16. It costs CPU time for every seed, and on a board the default run
      already finishes it can take longer, because every attempt runs to its end.
    - **`force_via_in_pad`**: Whether the router may drill a via into an SMD pad of its own net even when the DSN marks the via padstacks `(attach off)`. Default is `true`, the long-standing behaviour. `false` honours the DSN, as the `planned` and `bga` fanouts always do; via-in-pad needs filled and capped vias, so a board that does not allow it should route with `false`.
    - **`via_span_cost_factor`**: Extra cost for each layer a via drills beyond its first two, as a fraction of the via cost, so a microvia or a short buried via is cheaper than a through via and the search keeps the rest of the stack free. Default is `0`, which prices every via the same. Only matters on boards whose via rules include blind or buried vias.
    - **`fallback_work_ratio`**: The fallback stage may make at most this many times as many
      connection attempts as the default run made, summed over its attempts. The attempt that
      reaches the limit stops and keeps its best board, and no further attempt starts. Default
      `1.0`, so the fallback stage takes roughly as long as the default run at most; `0` means no
      limit. Connection attempts, not time, are counted, so the result does not depend on the
      machine.
    - **`fallback_threads`**: Threads for the fallback stage. Default `1` runs the attempts one
      after another. A higher value runs the alternative strategies side by side, each on its own
      copy of the board, together with the continuation of a residual board; `0` uses one thread
      per attempt, up to the number of processors. In parallel each attempt may make the whole
      `fallback_work_ratio` budget of connection attempts, and the search step budget left under
      `router.max_search_steps` is split equally between the attempts. The results are merged in
      the sequential order with the same rule for replacing the best board. Once an attempt is
      complete and clean, the later ones are stopped, because the merge would discard them. The
      chosen board is the same with any thread count above 1, but it can differ from `1`, because
      there each attempt only gets the budget its predecessors left. Each attempt's log lines carry
      its strategy name, and the live status follows the first attempt.
      When it helps: with `fallback_work_ratio` raised or `0`, so that several attempts run. The
      stage then takes about as long as its slowest attempt instead of the sum, for about the same
      CPU time (optimizer off, ratio `0`: M4 539 to 272 s, bm05 636 to 235 s, Issue070 95 to 74 s,
      the same boards; the full-pour synthetic board finished all five attempts in 1574 s where the
      sequential run reached the 30-minute cap in the third). With the default ratio of `1.0` the
      first sequential attempt usually uses the whole budget and no other starts, so parallel
      attempts try every strategy but take longer (M4 160 to 243 s). It needs one board copy of
      memory per thread, and it does not help when the default run already uses up the job's time
      or step budget.
    - **`fallback_race`**: Starts the alternative fallback strategies at the beginning of the
      routing stage, each on its own copy of the input board, beside the default run instead of
      after it. Needs `fallback_threads` other than `1`. Default `false`. The attempts cannot get a
      share of the default run's work, which is not known yet, so each may run to its end; they are
      merged in the usual order once the default run is done, so the chosen board does not depend
      on which attempt finishes first or on the thread count. A complete and clean default run
      stops them. They follow a stop of the whole job (timeout, stop file) but not the end of the
      default run's pass loop. The continuations of the best board run after the merge.
      When it helps: boards the default run does not finish, where the job timeout leaves no time
      for fallbacks after the default run. Eight hard PCBench boards, 8-minute cap, optimizer off,
      `fallback_strategies` = the defaults plus `seed-1,seed-2,seed-3`, `fallback_threads=0`,
      `fallback_work_ratio=0`: 6 open connections in all instead of 21 with the same attempts run
      after the default run, and 26 with the default settings (lpa96 2 -> 0, Unifying 11 -> 1, reworkctf 5 -> 2,
      EncoderBoard clean in 64 s instead of 123 s).
      It costs CPU time for every attempt, and on a board the default run finishes the attempts
      slow it down through shared cores and memory (LimeSDR 84 -> 122 s, TX5823 41 -> 84 s). At the
      job timeout the attempts are stopped and their boards merged as they are. The race runs the
      list in `fallback_race_strategies`, not `fallback_strategies`.
    - **`grid_finisher`**: When `true`, each connection still open after the passes and the
      fallbacks gets one A* search on a fine grid (about half a trace pitch per cell) in a window
      around its gap, crossing layers by the vias of the net's rule. The path is kept only if it
      joins the two pieces and adds no clearance violation; otherwise the board is restored.
      Default `false`; on in the `thorough` profile. Log line: `Grid finisher: N open
      connection(s), T tried, C closed, S grid states expanded of B, in X s.`
    - **`grid_finisher_max_steps`**: Grid states the finisher may expand in total, default
      `50000000`; one connection gets at most 2,000,000. The finisher tries each connection once
      and stops at this budget, at a stop request or at `job_timeout`, so a board that cannot be
      finished keeps its open connections instead of searching on. Counting states, not time,
      keeps the result the same on every machine.
    - **`fallback_race_strategies`**: The strategies a race starts, used in place of
      `fallback_strategies` when `fallback_race` is on and `fallback_threads` is other than `1`.
      Default `direct-first,no-fanout,seed-1`. `none` in `fallback_strategies` turns the race off
      too. A short list does as well as a long one for less CPU time: on the eight hard PCBench
      boards above (8-minute cap, optimizer off, `fallback_threads=0`, `fallback_work_ratio=0`) the
      default list and the eight attempts both left 6 open connections, but the default list took
      5,476 CPU s instead of 15,237 and 1,551 s instead of 2,066 in all, and Unifying, lpa96 and
      reworkctf finished before the cap. On L6 (10-minute cap) it ends with 15 open instead of 30
      with eight attempts (27 without a race), at 3,030 CPU s instead of 6,810, because eight
      attempts slow the default run; the synthetic board 4 open (eight attempts 3, no race 5).
    - **`spring_over_overlaps`**: Lets the insert spring over obstacles that overlap without
      either containing the other, such as a fixed trace on a foreign pin, as one octagon. Without
      it the insert refuses a path the search found past such a pair, after ripping neighbours for
      it, and the connection comes back in the next pass. Default `false`.
      When it helps: boards with locked tracks on pins. M4 (optimizer off): the same 6 open, but
      86 s instead of 164 s, and the default run finishes without fallbacks; Starling 36 -> 21 s;
      lpa96 (6-minute cap) 3 -> 2 open. On other boards it changes which connections fit and can
      leave more open (TX5823 0 -> 1, mppt-1210 0 -> 2), so it is opt-in.
    - **`max_search_steps_per_connection`**: Maze search steps one connection may use in pass 1,
      doubling each pass. Default `10000000`, about the 100 s the older time limit allowed; the
      longest routed search on the benchmark boards took about 635,000 steps. The step budget stops
      a connection at the same point on every run and machine, which a time limit does not under
      load. A negative value restores the time limit (100 s in pass 1, doubling each pass). Each
      pass logs `Auto-routing pass #N: Search work: ...` with the searches, the steps, how many
      stopped at the limit, the steps the routed connections needed, and how many searches did not
      route and what they cost, which shows what budget a board needs and how much of a pass goes
      into searches that fail. CLI: `--router.autorouter.max_search_steps_per_connection=<steps>`.
    - **`save_intermediate_stages`**: Save board snapshots between passes.
    - **`ignore_net_classes`**: Net class names the autorouter should skip.
    - **`max_threads`**: Canonical CLI is `--router.autorouter.max_threads`. The autorouter pass
      does not read it. Fanout is also single-threaded. The optimizer pool uses
      `--router.optimizer.max_threads`. The legacy flat `--router.max_threads` remains as a
      fallback / GUI knob and is still copied onto both pools.
- **`stop_file`**: Optional path that a headless CLI run checks every 2 seconds. When a file appears
  there, the job stops after the connection it is routing, skips the remaining fallback and
  optimizer stages, and writes its best board to the output file and the result manifest; the
  status file shows `stop_reason: STOP_FILE`. Use it to end a run that is good enough instead of
  killing the process, which loses the output. Equivalent CLI flag: `--router.stop_file=<path>`.
- **`result_json`**: Optional path for a machine-readable routing result manifest written at the
  end of a headless `-de`/`-do` run. Used by the benchmark and autopilot harnesses. Equivalent CLI
  flag: `--router.result_json=<path>`.
- **`status_file`**: Optional path for a live status file (JSON) for agents and scripts that poll a
  headless run. Rewritten every 2 seconds and once at the end, through a temporary file and a
  rename, so a reader never sees a partial file. It holds the stage, the fallback attempt, the pass
  and its progress, unrouted counts (at the start, now, best), a per-pass history, the time since
  the best result improved and since the last routed connection, how long the connection being
  routed has run (`current_item_elapsed_s`) and the slowest connection so far (`slowest_item`), and
  a rough estimate of the time left as a low-high range, capped by the job timeout. A connection
  that runs for more than a few seconds is not normal progress; each one that takes 10 seconds or
  more is also logged with its pin and net. It never changes routing. Equivalent CLI
  flag: `--router.status_file=<path>`.
- **`max_search_steps`**: Optional cap on the maze search steps of the whole job (fanout,
  autorouter and fallback searches; the optimizer is not counted). The job stops after the search
  that reaches the cap, writes its best board and ends as timed out, with a log line naming the
  cap. Unlike `job_timeout`, the steps depend only on the board and the settings, so a capped run
  stops at the same point on every run and machine, however busy it is. Use it for benchmarks and
  repeatable experiments; keep `job_timeout` as a safety net. Each pass logs its steps
  (`Search work: ...`). Not set by default. CLI: `--router.max_search_steps=<steps>`.
- **`max_threads`**: Legacy flat / GUI worker-thread cap. `setMaxThreads()` still copies this
  value into both `autorouter.max_threads` and `optimizer.max_threads`. Prefer the nested CLI
  flags (`--router.autorouter.max_threads`, `--router.optimizer.max_threads`) when the two pools
  must differ. `-mt N` sets **optimizer** threads only, and `-mt 0` turns the optimizer off.
- **`improvement_threshold`**: Minimum improvement required to continue routing.
- **`trace_pull_tight_accuracy`**: Accuracy for pulling traces tight.
- **`allowed_via_types`**: Enables or disables the use of different via types.
- **`via_costs`**: Cost factor for using vias.
- **`plane_via_costs`**: Cost factor for using vias on plane layers.
- **`plane_nets`**: Explicit array of net names to treat as power-plane nets, enabling plane-routing mode and discounted plane via costs for these nets.
- **`plane_as_obstacle`**: Boolean controlling whether conduction areas (copper pours) act as obstacles blocking foreign traces from passing through. Default is `false` (foreign traces may route through fills).
- **`start_ripup_costs`**: Cost factor for ripping up existing traces.
- **`automatic_neckdown`**: Enables or disables automatic neckdown of traces.
- **`min_trace_width_um`**: Narrowest trace the router may create, in micrometers. Necking down at
  pins, the fanout micro neckdown and the necked retry (`neck_width_um`) never go below it. Set it
  to the board's minimum track width (in KiCad: Board Setup, Constraints); Specctra DSN files do
  not carry that value. Absent or 0: no floor, so a pin taper goes down to the pad width and a
  fanout neck to half the trace width. Equivalent CLI flag: `--router.min_trace_width_um=<um>`.
- **`layers`**: An array of layer-specific settings (transient, typically set via CLI or loaded from board files). Each element contains:
    - **`routable`**: Boolean indicating if the layer is active/routable by the autorouter.
    - **`preferred_direction_horizontal`**: Boolean indicating if the preferred direction on this layer is horizontal.
- **`router_scoring`**: Autorouter board score (`V2_CONTINUOUS` by default; `V1_LEGACY`
  remains available). Used by the autorouter, board history, and API `normalized_score`.
  Incomplete connections are split at `unrouted_free_fraction` (0.5): the first half
  uses `unrouted_first_half_weight` (1000/3) and the last half uses
  `unrouted_second_half_weight` (2000/3), so a fully open board scores 0, a half-done
  board scores about 333, and a finished board scores 1000 before DRC. DRC adds
  `clearance_violation_count_weight` (25) times violation count / D plus
  `clearance_violation_depth_weight` (300) times stacked shortfall µm /
  `clearance_violation_depth_scale` (1000 µm) / D. D is max(1, pins × signal layers).
  `unrouted_connection_weight` is unused by V2.
  Equations and a glossary for every symbol are in [docs/scoring.md](scoring.md).
- **`optimizer_scoring`**: Optimizer board score (`V2_LOWER_BOUND` by default; `V1_LEGACY`
  remains available). Completeness and DRC count are keep/undo gates, not score terms.
  The score is 1000 minus excess wire length, vias, and bends versus placement lower
  bounds. Defaults: `excess_wire_length_weight` (1000), `excess_via_weight` (2000),
  `excess_bend_weight` (500), `length_floor` (1), `difficulty_scale_floor` (1). Via and
  bend excess divide by D; length excess divides by Lmin.
  Equations and a glossary for every symbol are in [docs/scoring.md](scoring.md).

The router and optimizer versions are independent. CLI aliases are
`--router-scoring-version=v1|v2`, `--optimizer-scoring-version=v1|v2`, and
`--scoring-version=v1|v2` to select both.

##### **`optimizer` Sub-section**

Configures the optional route-optimization stage that runs after autorouting.

- **`enabled`**: Whether to run the optimizer. Default is `true`.
- **`max_passes`**: Maximum number of optimizer passes.
- **`max_items`**: Maximum number of item optimization attempts.
- **`max_threads`**: Optimizer worker-thread cap. Canonical CLI is
  `--router.optimizer.max_threads`. The default is the JVM-visible processor count minus one
  (`Runtime.getRuntime().availableProcessors() - 1`), with a minimum of one worker. Headless,
  API, and GUI all use this pool (`BatchOptimizer`). Independent of
  `feature_flags.multi_threading` (that GUI flag does **not** disable optimizer workers) and of
  `--router.autorouter.max_threads`. Each in-flight worker `deepCopy()`s the board, so peak heap
  scales with this value.
- **`improvement_threshold`**: Minimum **relative** optimizer-score percentage gain required to
  continue after a pass (default `2.5`, representing 2.5%). `BatchOptimizer` compares
  `((scoreAfter - scoreBefore) / scoreBefore) * 100`. Benchmark calibration across golden fixtures
  demonstrates that `2.5` saves ~25% optimizer runtime while retaining >80% of via reductions.
  - *Practical Ranges:*
    - `0.5 – 1.0` (0.5% – 1.0%): Precision mode. Runs full multi-pass tail to eliminate every possible via on complex designs.
    - `2.0 – 2.5` (2.0% – 2.5%): Balanced default. Retains >80% via reductions while pruning low-yield late passes.
    - `3.5 – 5.0` (3.5% – 5.0%): Fast mode. Cuts optimizer time by ~45%, retaining ~65% via cuts.
    - `> 5.5` (> 5.5%): Rapid prototyping. Stops after 1–2 passes; not recommended for production boards.
- **`enable_preflight_guards`**: Whether pre-flight optimization guards are evaluated before starting the
  optimizer loop. If enabled, bypasses the optimizer stage when the board has unrouted connections, has no vias
  to eliminate (with trace length already near optimal), has an initial score at or near theoretical maximum,
  or all vias are mandatory layer transitions between SMD pins that cannot be eliminated. Default is `true`.
- **`max_consecutive_failures`**: Maximum consecutive candidate failures allowed before concluding the
  current pass. Default is `50`.
- **`max_consecutive_failures_pass1`**: Maximum consecutive candidate failures allowed in pass 1 before
  early-terminating the pass (canary probe). Default is `12`.

##### **`fanout` Sub-section**

Configures the SMD-pin fanout pre-pass stage.

- **`enabled`**: Whether to run the fanout pre-pass at all. Default is `true`.
- **`max_passes`**: Maximum number of fanout passes. Default is `20`.
- **`timeout`**: Optional wall-clock budget for the fanout stage (`HH:MM:SS`). When unset, fanout has no stage timeout of its own. `router.job_timeout` still covers fanout, autorouting, and optimization together.
- **`max_milliseconds_per_pin`**: Base time budget in milliseconds per SMD pin in pass 1. Scales with pass number. Default is `10000`. Used only when `max_search_steps_per_pin` is negative.
- **`max_search_steps_per_pin`**: Maze search steps one SMD pin may use in pass 1, scaling with the pass number like `max_milliseconds_per_pin`, which it replaces. Default `1000000`, about the 10 s time limit; the longest routed fanout search measured took about 2,700 steps. The time limit makes the fanout depend on machine load; the step budget stops a pin at the same point on every run. A negative value restores the time limit. Each fanout pass logs `Fanout pass #N: Search work: ...` with how many pins stopped at the limit and the steps the routed pins needed. CLI: `--router.fanout.max_search_steps_per_pin=<steps>`.
- **`ripup_allowed`**: Whether fanout can rip up existing traces. Default is `true`.
- **`min_escape_length_mm`**: The minimum physical escape trace length in millimeters. Default is `2.5`. Landing vias and escape stubs are not placed closer than this distance from the pin center.
- **`min_escape_by_pitch`**: When `true`, a pin's minimum escape length is at most half the distance to the nearest other pin of its component, so a ball in a pin grid may drill in the diagonal gap next to it. Default is `false`. Helps on BGAs and fine-pitch parts, where the default minimum puts the nearest legal via several pitches away.
- **`protect_escapes`**: When `true`, a fanout search never rips another pin's escape via or its short stub. Default is `false`. Stops the fanout passes from undoing each other on dense parts; a pin that finds no site then fails instead of taking a neighbour's.
- **`min_escape_by_pitch_grid_only`**: When `true`, `min_escape_by_pitch` acts only on the balls of pin grids the BGA planner detects (see `strategy`, `"bga"`); other pins keep `min_escape_length_mm`. Default is `false`. On every pin the window lets fine-pitch parts outside a BGA crowd their vias close in, which cost some boards without a BGA open connections.
- **`protect_escapes_grid_only`**: When `true`, `protect_escapes` acts only on the balls of detected pin grids; other pins may rip a neighbour's escape as without the gate. Default is `false`. Leave it off on a board whose fanout passes oscillate, where the pass log shows the same tens of pins re-fanned in every pass: protection on every pin stops that. Otherwise, on the boards measured, limiting both gates to grid balls gave fewer open connections than either gate on every pin.
- **`max_escape_length_mm`**: The maximum physical escape trace length in millimeters. Default is `4.5`. Enforced directly during maze expansion.
- **`start_via_diameter_mm`**: The diameter of starting/escape vias used inside the pins during the fanout/escape stage (in millimeters). Default is `0.25`.
- **`end_via_diameter_mm`**: The diameter of landing/end vias used at the end of escaping wires during the fanout/escape stage (in millimeters). Default is `0.25`.
- **`pin_sorting_order`**: Specifies the sorting order for pins within a component during fanout. Available strategies:
    - `"outer_first"` (Default): Pins further from the component center are processed first. This helps escape periphery pins early.
    - `"inner_first"`: Pins closer to the component center are processed first.
    - `"distance_to_closest_on_net"`: Pins are sorted by their distance to the closest item on the same net. Pins with closer targets are processed first.
    - `"surroundings_density"`: Measured by the count of SMD pins within a 20 mm proximity radius. Denser areas are processed first, allowing congested pins to escape before space is occupied.
    - `"unsorted"`: Pins are processed by pin number sequence.
- **`strategy`**: How the fanout escapes SMD pins. Default is `"eager"`.
    - `"bga"`: Before the fanout passes, every component whose SMD pads form a square lattice (at least 4 x 4, half occupied) is treated as a BGA. Each ball gets a via site reserved in one of the four diagonal gaps next to it, assigned by a matching so that no two balls share a gap and inner balls choose first. Balls from ring 1 inward, and balls on plane nets in any ring, get the dog-bone up front with the smallest via of the net's rule that fits; the outer ring routes on the surface. Pins the plan cannot place go to the fanout passes. Use with `protect_escapes` and `min_escape_by_pitch`. CLI: `--router.fanout.strategy=bga`.
    - `"eager"`: Fanout passes escape one pin at a time by maze search to a via inside the escape-length window.
    - `"direct_first"`: Same-layer pin connections are routed first, without vias; failed pins are left to the autorouter.
    - `"planned"`: Before the fanout passes, every SMD pin that still needs a connection gets an escape via on a short stub, so no pin is enclosed by copper before it can change layer. The via goes as close to the pad as fits: outward along the pad, then to either side of it, then behind it under the part, then diagonally; the stub grows only when no closer site fits, up to `max_escape_length_mm`. The densest components choose first. The escapes are unfixed, so the autorouter rips them up as needed. Pins with no room go to the eager passes. CLI: `--router.fanout.strategy=planned`.
      When it helps: dense boards whose pins get enclosed by copper before they can drop a via. Measured with defaults otherwise (2026-10-20): a dense 6-layer HV card went from 6 to 3 unrouted at a 30-minute cap, a dense 6-layer carrier from 179 to 158 after its first pass, and Issue070 from 2 unrouted to finished in 56 s instead of 88 s. On a less crowded 4-layer HV card (7 to 8) and on the DAC2020 bm05 board (5 to 8) it was slightly worse, so it is opt-in. The planned step itself takes 1-6 s on most boards (60 s on the large carrier).

#### **`usage_and_diagnostic_data` Section**

- **`disable_analytics`**: Disables sending anonymous usage and diagnostic data.
- **`analytics_modulo`**: Sends usage data after every Nth run, where N is the value of `analytics_modulo`.

#### **`feature_flags` Section**

- **`multi_threading`**: GUI opt-in for experimental multi-thread **autorouter** UI. It does
  **not** gate the optimizer worker pool (`optimizer.max_threads`) and does not change the
  production maze path, which still runs a single-thread pass.
- **`inspection_mode`**: Enables or disables inspection mode in the GUI.
- **`other_menu`**: Enables or disables the "Other" menu in the GUI.
- **`save_jobs`**: Enables or disables saving routing jobs to disk.

#### **`api_server` Section**

- **`enabled`**: Enables or disables the built-in API server.
- **`http_allowed`**: Allows or disallows HTTP connections to the API server.
- **`endpoints`**: A list of endpoints that the API server will listen on. Each endpoint is specified as
  `[protocol]://[host]:[port]`.
  When set via CLI or environment variable, provide a **comma-separated string** of endpoint URLs:
  - CLI: `--api_server.endpoints=http://0.0.0.0:37864,http://127.0.0.1:37864`
  - Env var: `FREEROUTING__API_SERVER__ENDPOINTS=http://0.0.0.0:37864,http://127.0.0.1:37864`
- *`cors_origins`*: A comma-separated list of origins for the `Access-Control-Allow-Origin` CORS header. Set to `*` to accept all origins (this can be a security risk). When CORS is enabled, the server automatically allows the following request headers in preflight responses: `Content-Type`, `Accept`, `Origin`, `X-Requested-With`, `Authorization`, `Freerouting-Profile-ID`, `Freerouting-Profile-Email`, and `Freerouting-Environment-Host`. This ensures browser-based clients (e.g. EasyEDA at `https://pro.lceda.cn`) can authenticate successfully without being blocked by CORS preflight checks.
- **`rate_limit`**: Fixed-window throttling for API requests.
  - `enabled`: Enable/disable API-side rate limiting.
  - `requests_per_window`: Maximum accepted requests per identity in each window.
  - `window_seconds`: Window duration in seconds.
- **`max_parallel_jobs`**: Maximum number of routing jobs the scheduler runs concurrently (default: `CPU cores - 1`, minimum `1`). Setting to `0` or omitting triggers auto-detection. Useful for self-hosted instances that size job concurrency to their hardware and JVM heap.
  - Env var: `FREEROUTING__API_SERVER__MAX_PARALLEL_JOBS=10`

#### **`mcp_server` Section**

- **`enabled`**: Enables or disables the dedicated MCP server.
- **`http_allowed`**: Allows or disallows HTTP connections to the MCP server.
- **`endpoints`**: A list of MCP listen endpoints (`[protocol]://[host]:[port]`).
- **`authentication`**: API-key authentication settings specific to MCP (`enabled`, `providers`, provider credentials).
- **`cors_origins`**: Optional CORS allowlist for browser-hosted MCP clients.
- **`target_api_base_url`**: Base URL of the REST API server used by MCP tools to execute operations.
  Must point to the REST API base URL (for example `http://127.0.0.1:37864`) and not to MCP paths such as `/v1/mcp` or `/.well-known/*`.
- **`rate_limit`**: Fixed-window throttling for MCP HTTP requests (for example `/v1/mcp`).
  - `enabled`: Enable/disable MCP-side rate limiting.
  - `requests_per_window`: Maximum accepted requests per identity in each window.
  - `window_seconds`: Window duration in seconds.

#### Recommended Rate-Limit Presets

Use these as practical starting points, then tune based on observed traffic and client retry behavior.

| Environment | API (`api_server.rate_limit`) | MCP (`mcp_server.rate_limit`) | Notes |
|---|---|---|---|
| Local development | `enabled=false` | `enabled=false` | Fast feedback loop, no throttling noise while debugging. |
| Staging / internal QA | `enabled=true`, `requests_per_window=120`, `window_seconds=60` | `enabled=true`, `requests_per_window=60`, `window_seconds=60` | Catches runaway polling while staying permissive for tests. |
| Production (default baseline) | `enabled=true`, `requests_per_window=180`, `window_seconds=60` | `enabled=true`, `requests_per_window=90`, `window_seconds=60` | Balanced baseline for mixed interactive + automation traffic. |
| Production (strict) | `enabled=true`, `requests_per_window=120`, `window_seconds=60` | `enabled=true`, `requests_per_window=45`, `window_seconds=60` | For public exposure or when abuse pressure is expected. |

Tuning guidance:

- If legitimate clients hit HTTP `429` frequently, raise `requests_per_window` first.
- Keep `window_seconds` at `60` unless you have a clear reason to use shorter bursts.
- MCP generally needs lower limits than REST because tool loops can burst quickly.
- Pair rate limits with authentication and correlation-ID logging for reliable incident analysis.

### Command Line Arguments

Freerouting can also be configured using command-line arguments. These arguments override the settings specified in the JSON configuration file. You must use `--{property-name}={property-value}` format, where `property-name` is the hierarchical definition of the property you want to change and the `property-value` is its desired value. You can use `.`, `-` and `:` characters to separate the hierarchical levels in the `property-name` parameter.

**Scalar example:**

```bash
java -jar freerouting.jar --gui.enabled=false --router.autorouter.max_passes=200
```

**List-valued settings** (e.g. `api_server.endpoints`, `mcp_server.endpoints`) must be passed as a **comma-separated string**; whitespace around commas is ignored:

```bash
java -jar freerouting.jar --api_server.endpoints=http://0.0.0.0:37864
java -jar freerouting.jar --api_server.endpoints=http://0.0.0.0:37864,http://127.0.0.1:37864
java -jar freerouting.jar --mcp_server.enabled=true --mcp_server.endpoints=http://127.0.0.1:37964 --mcp_server.target_api_base_url=http://127.0.0.1:37864
java -jar freerouting.jar --api_server.rate_limit.enabled=true --api_server.rate_limit.requests_per_window=120 --api_server.rate_limit.window_seconds=60
java -jar freerouting.jar --mcp_server.rate_limit.enabled=true --mcp_server.rate_limit.requests_per_window=60 --mcp_server.rate_limit.window_seconds=60
```

### Environment Variables

Environment variables provide another way to override settings. The environment variable names correspond to the keys in the JSON settings file, starting with `FREEROUTING__` and periods replaced by double underscores.

**Scalar example:**

```bash
FREEROUTING__GUI__ENABLED=false
FREEROUTING__ROUTER__AUTOROUTER__MAX_PASSES=200
java -jar freerouting.jar
```

**List-valued settings** use the same comma-separated format as CLI arguments:

```bash
FREEROUTING__API_SERVER__ENDPOINTS=http://0.0.0.0:37864,http://127.0.0.1:37864
FREEROUTING__MCP_SERVER__ENABLED=true
FREEROUTING__MCP_SERVER__ENDPOINTS=http://127.0.0.1:37964
FREEROUTING__MCP_SERVER__TARGET_API_BASE_URL=http://127.0.0.1:37864
java -jar freerouting.jar
```

## Settings Precedence

Settings are resolved by the `SettingsMerger` class, which collects all active `SettingsSource` implementations, sorts them by ascending priority, and applies each source on top of the previously accumulated result. The full priority ladder (lowest → highest) is:

| Priority | Source | Class |
|----------|--------|-------|
| 0 | Default Settings (hardcoded baseline) | `DefaultSettings` |
| 10 | JSON configuration file (`freerouting.json`) | `JsonFileSettings` |
| 20 | DSN file metadata | `DsnFileSettings` |
| 30 | SES file metadata | `SesFileSettings` |
| 40 | RULES file overrides | `RulesFileSettings` |
| 55 | Environment variables (`FREEROUTING__ROUTER__*`) | `EnvironmentVariablesSource` |
| 60 | CLI arguments (`--router.*`) | `CliSettings` |
| 65 | GUI (interactive user changes) | `GuiSettingsSource` / `WorkspaceSettings` |
| 70 | REST API caller — highest priority | `ApiSettings` |

If a setting is not defined in any source, the hardcoded default from `DefaultSettings` is used.

### Specctra `.rules` File Settings (Priority 40)

When a `.rules` file is provided (via CLI `-dr`, `-de board.dsn+board.rules`, API `POST /v1/jobs/{jobId}/rules`, or an adjacent `<designName>.rules` file), `RulesFileSettings` parses both general routing rules and the `(autoroute_settings ...)` block. Supported parameters include:
- `(autoroute on|off)` / `(postroute on|off)` / `(vias on|off)`
- `(via_costs <int>)`, `(plane_via_costs <int>)`, `(start_ripup_costs <int>)`
- Per-layer settings via `(layer_rule <layer_name> ...)`:
  - `(active on|off)`
  - `(preferred_direction horizontal|vertical)`
  - `(preferred_direction_trace_costs <float>)`
  - `(against_preferred_direction_trace_costs <float>)`

## Storage Locations

Freerouting stores configuration (`freerouting.json`), user data (`data/`), and logs (`freerouting.log`) in dedicated, platform-standard operating system directories:

| Platform | Configuration (`freerouting.json`) & Data (`data/`) | Logs (`freerouting.log`) | Cache (JREs, artifacts) |
|---|---|---|---|
| **Windows** | `%APPDATA%\freerouting` (e.g. `C:\Users\<User>\AppData\Roaming\freerouting`) | `%LOCALAPPDATA%\freerouting\logs` | `%LOCALAPPDATA%\freerouting\cache` |
| **macOS** | `~/Library/Application Support/freerouting` | `~/Library/Logs/freerouting` | `~/Library/Caches/freerouting` |
| **Linux / POSIX (XDG)** | `$XDG_CONFIG_HOME/freerouting` (`~/.config/freerouting`) | `$XDG_STATE_HOME/freerouting/logs` (`~/.local/state/freerouting/logs`) | `$XDG_CACHE_HOME/freerouting` (`~/.cache/freerouting`) |

You can override the base configuration/data root directory using `--user_data_path=<dir>` or the `FREEROUTING__USER_DATA_PATH` environment variable. On first launch, Freerouting automatically migrates any legacy configuration found in `<tmpdir>/freerouting/freerouting.json`.

## Settings Architecture — Why Fields Must Be Nullable

`RouterSettings` intentionally declares all its fields as nullable reference types (e.g. `Integer`, `Boolean`, `String`) **with no default initializers**. This is a deliberate architectural constraint required by the merge mechanism:

`SettingsMerger.merge()` calls `RouterSettings.applyNewValuesFrom(source)`, which delegates to `ReflectionUtil.copyFields()`. That method copies a field from the incoming source into the accumulated result **only when the source field is non-null and differs from the Java language default for that type**.

If any field were initialised to a non-null value inside the `RouterSettings` constructor (e.g. `public Integer maxPasses = 9999;`), every source's settings object would carry that value, and the merger would incorrectly treat it as an explicit override. A low-priority source (such as the JSON file) would then silently win over a higher-priority source (such as the API) whenever the user left that field unspecified in the high-priority source.

**Keep all `RouterSettings` fields null-initialised.** Concrete defaults belong exclusively in `DefaultSettings.getSettings()`, which is always the first source applied and therefore acts as the safe fallback for every field
