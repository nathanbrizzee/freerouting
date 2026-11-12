package app.freerouting.settings;

import app.freerouting.autoroute.BoardUpdateStrategy;
import app.freerouting.autoroute.ItemSelectionStrategy;
import com.google.gson.annotations.SerializedName;
import java.io.Serializable;

/**
 * Settings for the route optimizer which runs after auto-routing to reduce vias and trace length.
 */
public class OptimizerSettings implements Serializable, Cloneable {

  /** Whether the route optimizer is enabled. */
  @SerializedName("enabled")
  public Boolean enabled;

  /** The identifier of the optimization algorithm to use (e.g., "freerouting-optimizer"). */
  @SerializedName("algorithm")
  public String algorithm;

  /** The maximum number of full optimization passes (sweeps over the board's items) to run. */
  @SerializedName("max_passes")
  public Integer maxPasses;

  /**
   * The maximum number of item optimization attempts allowed. If this limit is reached, the
   * optimization stage will stop early.
   */
  @SerializedName("max_items")
  public Integer maxItems;

  /** The maximum number of threads to use for parallel route optimization. */
  @SerializedName("max_threads")
  public Integer maxThreads;

  /**
   * Relative pass-improvement threshold expressed directly as a percentage (e.g., 2.5 = 2.5% of the
   * incumbent optimizer score). {@code BatchOptimizer} stops when the relative percentage
   * improvement is below this value. Default is 2.5 (2.5%), providing optimal balance between via
   * elimination and runtime.
   */
  @SerializedName("improvement_threshold")
  public Float optimizationImprovementThreshold;

  /** Whether pre-flight optimization guards are enabled to skip un-improvable boards. */
  @SerializedName("enable_preflight_guards")
  public Boolean enablePreflightGuards;

  /**
   * The maximum number of consecutive item optimization failures allowed before aborting the
   * current pass.
   */
  @SerializedName("max_consecutive_failures")
  public Integer maxConsecutiveFailures;

  /**
   * The maximum number of consecutive item optimization failures allowed before aborting pass 1
   * early (canary limit). Defaults to 12.
   */
  @SerializedName("max_consecutive_failures_pass1")
  public Integer maxConsecutiveFailuresPass1;

  /**
   * A multiplier applied to the base ripup cost at the start of optimization. Higher values make
   * ripping up existing traces more expensive, prioritizing routing speed.
   */
  @SerializedName("additional_ripup_cost_factor_at_start")
  public Integer additionalRipupCostFactorAtStart;

  /**
   * A cost discount factor applied when ripping up trace items (as opposed to vias). Typically less
   * than 1.0 to make traces easier to rip up and reroute than vias.
   */
  @SerializedName("trace_ripup_cost_factor")
  public Float traceRipupCostFactor;

  /**
   * The maximum number of autoroute passes allowed when ripping up and rerouting a single item (and
   * its connected items) during optimization.
   */
  @SerializedName("max_autoroute_passes")
  public Integer maxAutoroutePasses;

  /**
   * The most maze search steps one connection may use while the optimizer re-routes a ripped item,
   * in every one of its passes (it does not double per pass as the router's budget does). The
   * optimizer re-routes connections that are already routed, so a search far longer than the
   * router's own searches rarely gives a shorter route, and each running candidate holds its search
   * rooms until it ends. A value of zero or below uses the router's budget.
   */
  @SerializedName("max_search_steps_per_connection")
  public Long maxSearchStepsPerConnection;

  /**
   * The most maze search steps one candidate may use in all: every connection it routes, in all of
   * its passes, including the connections its searches rip. A candidate that rips a crowded area
   * can otherwise route many connections at the per-connection cap. When the budget runs out the
   * candidate stops routing and is judged as it stands, so it is kept only if it is still better. A
   * value of zero or below means no total budget.
   */
  @SerializedName("max_search_steps_per_candidate")
  public Long maxSearchStepsPerCandidate;

  // -------------------------------

  /**
   * The strategy to update the board: GLOBAL_OPTIMAL (calculate updates and apply the single best
   * improvement).
   */
  @SerializedName("board_update_strategy")
  public transient BoardUpdateStrategy boardUpdateStrategy;

  /**
   * The strategy for selecting and ordering the items to be optimized (e.g., SEQUENTIAL or
   * PRIORITIZED).
   */
  @SerializedName("item_selection_strategy")
  public transient ItemSelectionStrategy itemSelectionStrategy;

  /** Timeout for the optimizer stage (e.g., "5m", "300s"). Default is null (no timeout). */
  @SerializedName("timeout")
  public String timeoutString;

  /** Creates optimizer settings without source-specific values. */
  public OptimizerSettings() {}

  /**
   * Creates a deep copy of this RouterOptimizerSettings object. All fields including transient ones
   * are cloned.
   *
   * @return A new RouterOptimizerSettings instance with the same values
   */
  @Override
  public OptimizerSettings clone() {
    try {
      OptimizerSettings result = (OptimizerSettings) super.clone();
      // Primitive wrappers and Strings are immutable, so no need to clone them
      // But we need to ensure transient fields are copied
      result.boardUpdateStrategy = this.boardUpdateStrategy;
      result.itemSelectionStrategy = this.itemSelectionStrategy;
      return result;
    } catch (CloneNotSupportedException e) {
      // This should never happen since we implement Cloneable
      throw new AssertionError("Clone not supported", e);
    }
  }
}
