package app.freerouting.settings;

import com.google.gson.annotations.SerializedName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;

/**
 * Execution knobs for the batch autorouter stage (fanout and optimizer stay in their own objects).
 * All fields are nullable so {@code SettingsMerger} can tell "this source sets the field" from
 * "this source has no opinion".
 */
@Schema(name = "AutorouterSettings", description = "Execution settings for the autorouter stage")
public class AutorouterSettings implements Serializable, Cloneable {

  /** Whether the autorouter stage runs after fanout. */
  @SerializedName("enabled")
  @Schema(description = "Whether the autorouter stage runs after fanout")
  public Boolean enabled;

  /** Algorithm identifier (for example {@link RouterSettings#ALGORITHM_CURRENT}). */
  @SerializedName("algorithm")
  @Schema(description = "Algorithm identifier (e.g. 'freerouting-router')")
  public String algorithm;

  /** Maximum autorouter passes. {@code 0} means no limit. */
  @SerializedName("max_passes")
  @Schema(description = "Maximum autorouter passes (0 means unlimited)")
  public Integer maxPasses;

  /** Maximum items attempted in the autorouter stage. */
  @SerializedName("max_items")
  @Schema(description = "Maximum items attempted in the autorouter stage")
  public Integer maxItems;

  /**
   * Kept for the canonical CLI {@code --router.autorouter.max_threads}. The autorouter pass does
   * not read it. The optimizer pool uses {@code router.optimizer.max_threads}.
   */
  @SerializedName("max_threads")
  @Schema(description = "Maximum worker threads for the autorouter stage")
  public Integer maxThreads;

  /** When true, intermediate board snapshots are saved between autorouter passes. */
  @SerializedName("save_intermediate_stages")
  @Schema(description = "When true, intermediate board snapshots are saved between passes")
  public Boolean saveIntermediateStages;

  /** Net class names the autorouter should skip. */
  @SerializedName("ignore_net_classes")
  @Schema(
      description = "Array of exact net class names to skip/ignore during autorouting",
      example = "[\"kicad_default\"]")
  public String[] ignoreNetClasses;

  /**
   * Erik Gavriluk (freerouting.net): whether nets that required necking on earlier connections
   * route at the neck width directly on later connections (skipping redundant class-width
   * searches).
   */
  @SerializedName("start_necked")
  @Schema(description = "Whether nets requiring necking route at neck width directly")
  public Boolean startNecked;

  /**
   * Erik Gavriluk (freerouting.net): comma-separated routing fallback strategies, tried in order
   * when the pass loop gives up with unrouted connections or new clearance violations.
   */
  @SerializedName("fallback_strategies")
  @Schema(description = "Comma-separated routing fallback strategies")
  public String fallbackStrategies;

  /** Erik Gavriluk (freerouting.net): fallback alternatives an exploration round runs. */
  @SerializedName("explore_fallback_strategies")
  @Schema(description = "Fallback alternatives an exploration round runs")
  public String exploreFallbackStrategies;

  /**
   * Erik Gavriluk (freerouting.net): factor applied to the via cost of nets whose pins are all SMD.
   */
  @SerializedName("smd_net_via_cost_factor")
  @Schema(description = "Via cost factor for pure SMD nets")
  public Double smdNetViaCostFactor;

  /**
   * Erik Gavriluk (freerouting.net): a board with at most this many open connections is continued
   * from its best routing before the other fallback attempts. {@code 0} disables that continuation.
   */
  @SerializedName("residual_repair_max_open")
  @Schema(description = "Open connections at which the best board is continued before alternatives")
  public Integer residualRepairMaxOpen;

  /**
   * Deterministic budget per connection in pass 1, in maze search steps, doubling each pass like
   * the time budget it replaces when set (100 s in pass 1). Unlike the time budget, the same board
   * stops at the same point on every run, whatever the machine load.
   */
  @SerializedName("max_search_steps_per_connection")
  @Schema(
      description =
          "Maze search steps per connection in pass 1, doubling each pass; replaces the time"
              + " limit when set")
  public Long maxSearchStepsPerConnection;

  /**
   * The fallback stage may make at most this many times as many connection attempts as the default
   * run made, summed over its attempts; an attempt that reaches the limit stops and keeps its best
   * board. Counting attempts, not time, keeps the result deterministic. {@code 0} means no limit.
   */
  @SerializedName("fallback_work_ratio")
  @Schema(
      description =
          "Connection attempts the fallback stage may make, as a multiple of the default run's;"
              + " 0 means no limit")
  public Double fallbackWorkRatio;

  /**
   * Threads for the fallback stage. {@code 1} runs the attempts one after another, as before. More
   * runs the alternative strategies side by side, together with the continuation of a residual
   * board, each on its own copy of the board; {@code 0} uses one thread per attempt, up to the
   * number of processors. In parallel each attempt may make the whole fallback work budget of
   * connection attempts, and the job's search step budget left is split equally between them. The
   * shares depend on the number of attempts, not on the number of threads, so the chosen board is
   * the same with any thread count.
   */
  @SerializedName("fallback_threads")
  @Schema(
      description =
          "Threads for the fallback stage: 1 runs attempts in order, more runs them side by side,"
              + " 0 uses one per attempt up to the processor count")
  public Integer fallbackThreads;

  /**
   * Erik Gavriluk (freerouting.net): exploration attempts after the opening fallback. {@code 0}
   * disables them. Later tries of a kind raise via costs and widen the ripped neighbourhood.
   * Attempts are not cut short by elapsed time.
   */
  @SerializedName("explore_patience")
  @Schema(description = "Exploration attempts in a row without improvement before stopping")
  public Integer explorePatience;

  /**
   * Erik Gavriluk (freerouting.net): extra seed mixed into the autorouter item order. Null keeps
   * the pass loop's own order.
   */
  @SerializedName("item_order_seed")
  @Schema(description = "Extra seed for the autorouter item order")
  public Integer itemOrderSeed;

  @Override
  public AutorouterSettings clone() {
    try {
      AutorouterSettings copy = (AutorouterSettings) super.clone();
      if (this.ignoreNetClasses != null) {
        copy.ignoreNetClasses = this.ignoreNetClasses.clone();
      }
      return copy;
    } catch (CloneNotSupportedException e) {
      throw new AssertionError("Clone not supported", e);
    }
  }
}
