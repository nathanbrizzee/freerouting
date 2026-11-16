package app.freerouting.settings;

import java.util.List;
import java.util.Locale;

/**
 * The run profiles ({@code router.profile}). A profile replaces the hardcoded defaults, the lowest
 * settings layer, so it can set any value, including {@code false}, and every other source still
 * overrides it. The settings merge copies only values that differ from the Java default, so a
 * profile placed as a layer above the defaults could never switch a setting off.
 *
 * <ul>
 *   <li>{@code quick}: a first look in the least wall-clock time; {@code standard} routing without
 *       fallbacks or optimizer.
 *   <li>{@code standard}: the most complete board in one run: every finishing method, each within a
 *       step budget, with parallel work bounded by the cores and the heap.
 *   <li>{@code classic}: the original router's behaviour, every overhaul gate off.
 * </ul>
 *
 * <p>{@code thorough} is accepted and runs {@code standard}, which now includes what it added.
 *
 * <p>Profiles set passes, budgets and strategies, never time limits, so a run stays deterministic.
 * Their contents change only with a board-set measurement (router-overhaul-roadmap.md, 5.4).
 */
public final class RouterProfiles {

  public static final String QUICK = "quick";
  public static final String STANDARD = "standard";

  /** The former finishing profile, now an alias of {@link #STANDARD} (see SettingsMerger). */
  public static final String THOROUGH = "thorough";

  public static final String CLASSIC = "classic";

  /** The profile of a run that names none. */
  public static final String DEFAULT = STANDARD;

  public static final List<String> NAMES = List.of(QUICK, STANDARD, CLASSIC);

  private RouterProfiles() {}

  /** The profile's canonical name, or null if the name is not a profile. */
  public static String normalize(String name) {
    if (name == null) {
      return null;
    }
    String lower = name.trim().toLowerCase(Locale.ROOT);
    return NAMES.contains(lower) ? lower : null;
  }

  /**
   * Turns the hardcoded defaults into the defaults of the named profile.
   *
   * @param name a canonical profile name ({@link #normalize})
   * @param defaults the hardcoded defaults, changed in place
   */
  public static void apply(String name, RouterSettings defaults) {
    defaults.profile = name;
    switch (name) {
      case QUICK -> {
        applyStandard(defaults);
        defaults.autorouter.fallbackStrategies = "none";
        defaults.optimizer.enabled = false;
        defaults.autorouter.gridFinisher = false;
        defaults.autorouter.fallbackRace = false;
        defaults.autorouter.fallbackThreads = 1;
      }
      case STANDARD, THOROUGH -> applyStandard(defaults);
      case CLASSIC -> defaults.autorouter.differentialPairs = "off";
      default -> throw new IllegalArgumentException("Unknown profile: " + name);
    }
  }

  /**
   * The batch A set that measured best (19 boards: 1239 -> 854 open, 1 -> 4 clean, less CPU):
   * planned BGA escapes, both escape gates on grid balls only, via cost by span, via-in-pad as the
   * DSN says; and a continued board's routes movable.
   */
  private static void applyStandard(RouterSettings defaults) {
    defaults.fanout.strategy = "bga";
    defaults.fanout.minEscapeByPitch = true;
    defaults.fanout.minEscapeByPitchGridOnly = true;
    defaults.fanout.protectEscapes = true;
    defaults.fanout.protectEscapesGridOnly = true;
    defaults.autorouter.viaSpanCostFactor = 0.5;
    defaults.autorouter.forceViaInPad = false;
    // A continued board (-de board.dsn earlier.ses) keeps its earlier routes movable.
    defaults.sessionRoutesMovable = true;
    // Fail fast, then finish on the grid: the longest routed maze search measured took about
    // 0.8 million steps, so a search past 2 million is almost always one that fails, and a
    // connection that failed once keeps that budget instead of doubling it. What is still open
    // after the passes gets the grid finisher, which may lift other nets' movable copper; the
    // optimizer stage then reworks its routes like any others.
    defaults.autorouter.maxSearchStepsPerConnection = STANDARD_SEARCH_STEPS;
    defaults.autorouter.maxSearchStepsAfterFailure = STANDARD_SEARCH_STEPS;
    defaults.autorouter.gridFinisher = true;
    defaults.autorouter.gridFinisherRipup = true;
    // A connection that copper the router cannot move walls off is found after the first pass and
    // not searched again: each such search spent its whole budget in every pass.
    defaults.autorouter.skipUnreachable = true;
    // The passes stop once the search work since the fewest open connections exceeds the work that
    // reached it (from pass 8): replayed over the benchmark logs, no board lost a connection.
    defaults.autorouter.stagnationWorkRatio = 1.0;
    // A board that still gains, but slowly, would otherwise spend hours on passes that each take
    // minutes: from pass 8 the passes also stop once the last two cut the fewest open connections
    // by less than a fifth. The fallbacks that follow route the board again in another order.
    defaults.autorouter.stagnationGainRatio = 0.2;
    // The race waits for its slowest lane, and a lane that gains quickly never meets the rules
    // above. No pass loop may spend more than this many search steps: on the 1000-connection
    // benchmark board that is about eight passes, two hours; on the others, no stop at all.
    defaults.autorouter.passMaxSteps = PASS_MAX_STEPS;
    // The optimizer reads every candidate of a pass and keeps the best one: on the 1000-connection
    // board one pass cost 1.6 billion steps and 2.5 hours for two connections, where the other
    // benchmark boards use at most 118 million steps in all.
    defaults.optimizer.maxSteps = OPTIMIZER_MAX_STEPS;
    // The alternative strategies race beside the default run, each within its share of the step
    // budget, on up to one thread per core and no more than the heap holds (each attempt has a
    // board of its own; see RoutingFallbackRunner.fallbackThreads).
    defaults.autorouter.fallbackRace = true;
    defaults.autorouter.fallbackThreads = 0;
  }

  /** Maze search steps per connection in pass 1 of {@code standard}, and after a failure. */
  static final long STANDARD_SEARCH_STEPS = 2_000_000L;

  /** The search steps a pass loop may spend in the standard profile ({@code pass_max_steps}). */
  static final long PASS_MAX_STEPS = 250_000_000L;

  /** The search steps the optimizer stage may use in the standard profile ({@code max_steps}). */
  static final long OPTIMIZER_MAX_STEPS = 500_000_000L;
}
