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
 *   <li>{@code standard}: the most complete board in the least time; the best measured set.
 *   <li>{@code thorough}: {@code standard} plus everything that may close the last connections, at
 *       more CPU; meant to continue from an earlier result.
 *   <li>{@code classic}: the original router's behaviour, every overhaul gate off.
 * </ul>
 *
 * <p>Profiles set passes, budgets and strategies, never time limits, so a run stays deterministic.
 * Their contents change only with a board-set measurement (router-overhaul-roadmap.md, 5.4).
 */
public final class RouterProfiles {

  public static final String QUICK = "quick";
  public static final String STANDARD = "standard";
  public static final String THOROUGH = "thorough";
  public static final String CLASSIC = "classic";

  /** The profile of a run that names none. */
  public static final String DEFAULT = STANDARD;

  public static final List<String> NAMES = List.of(QUICK, STANDARD, THOROUGH, CLASSIC);

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
      }
      case STANDARD -> applyStandard(defaults);
      case THOROUGH -> {
        applyStandard(defaults);
        // Alternatives race beside the default run on all cores, without an attempt budget.
        defaults.autorouter.fallbackRace = true;
        defaults.autorouter.fallbackThreads = 0;
        defaults.autorouter.fallbackWorkRatio = 0.0;
        // The connections still open then get one fine-grid search each, within a step budget.
        defaults.autorouter.gridFinisher = true;
      }
      case CLASSIC -> {}
      default -> throw new IllegalArgumentException("Unknown profile: " + name);
    }
  }

  /**
   * The batch A set that measured best (19 boards: 1239 -> 854 open, 1 -> 4 clean, less CPU):
   * planned BGA escapes, both escape gates on grid balls only, via cost by span, via-in-pad as the
   * DSN says.
   */
  private static void applyStandard(RouterSettings defaults) {
    defaults.fanout.strategy = "bga";
    defaults.fanout.minEscapeByPitch = true;
    defaults.fanout.minEscapeByPitchGridOnly = true;
    defaults.fanout.protectEscapes = true;
    defaults.fanout.protectEscapesGridOnly = true;
    defaults.autorouter.viaSpanCostFactor = 0.5;
    defaults.autorouter.forceViaInPad = false;
  }
}
