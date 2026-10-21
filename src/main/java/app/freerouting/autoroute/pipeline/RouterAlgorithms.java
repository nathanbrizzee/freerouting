package app.freerouting.autoroute.pipeline;

import app.freerouting.settings.RouterSettings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The router algorithms {@code router.autorouter.algorithm} can select. The original router is the
 * default and stays unchanged; new methods are added here beside it, each with its own name.
 */
public final class RouterAlgorithms {

  /** The original Freerouting router: fanout, rip-up passes, fallbacks, optimizer. */
  public static final String ORIGINAL = RouterSettings.ALGORITHM_CURRENT;

  /**
   * The original pipeline with a global plan first (docs/research/global-planner-design.md). Under
   * construction: until the planner lands it routes exactly like {@link #ORIGINAL}.
   */
  public static final String PLANNED = "freerouting-planned";

  private static final Map<String, String> KNOWN = new LinkedHashMap<>();

  static {
    KNOWN.put(ORIGINAL, "the original router (default)");
    KNOWN.put(PLANNED, "global plan first, then the original router (in progress)");
  }

  private RouterAlgorithms() {}

  /** Whether {@code name} is a known algorithm. */
  public static boolean isKnown(String name) {
    return name != null && KNOWN.containsKey(name);
  }

  /** The known algorithms with a short description of each, in a stable order. */
  public static Map<String, String> known() {
    return Collections.unmodifiableMap(KNOWN);
  }

  /** The known names, comma-separated, for messages. */
  public static String names() {
    return String.join(", ", KNOWN.keySet());
  }

  /** Whether the settings select the planned router. */
  public static boolean usesPlanner(RouterSettings settings) {
    return settings != null
        && settings.autorouter != null
        && PLANNED.equals(settings.autorouter.algorithm);
  }
}
