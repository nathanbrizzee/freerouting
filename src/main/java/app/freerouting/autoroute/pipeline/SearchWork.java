package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.core.StoppableThread;
import app.freerouting.datastructures.TimeLimit;
import java.util.Arrays;
import java.util.Locale;

/**
 * Search steps spent in one pass: how many searches ran, how many stopped at their limit, and the
 * steps that the routed searches needed. The log line lets a person or an agent see whether the
 * per-search limit is cutting searches short and what a step budget would need to allow. Items
 * found already connected when their turn comes (routing another item of the net connected them)
 * are counted apart, not as searches that failed.
 */
final class SearchWork {

  private long[] routedSteps = new long[64];
  private int routedCount;
  private int searches;
  private int limitHits;
  private long totalSteps;
  private long routedTotal;
  private int alreadyConnected;

  void reset() {
    this.routedCount = 0;
    this.searches = 0;
    this.limitHits = 0;
    this.totalSteps = 0;
    this.routedTotal = 0;
    this.alreadyConnected = 0;
  }

  /**
   * Records one finished attempt under {@code limit} with its result {@code state} and adds its
   * steps to the job's search step budget on {@code thread}, which may be null.
   */
  void record(TimeLimit limit, AutorouteAttemptState state, StoppableThread thread) {
    if (thread != null) {
      thread.addSearchSteps(limit.steps());
    }
    if (state == AutorouteAttemptState.ALREADY_CONNECTED
        || state == AutorouteAttemptState.NO_UNCONNECTED_NETS
        || state == AutorouteAttemptState.CONNECTED_TO_PLANE) {
      this.alreadyConnected++;
      return;
    }
    boolean routed = state == AutorouteAttemptState.ROUTED;
    this.searches++;
    this.totalSteps += limit.steps();
    if (limit.wasExceeded()) {
      this.limitHits++;
    }
    if (routed) {
      if (this.routedCount == this.routedSteps.length) {
        this.routedSteps = Arrays.copyOf(this.routedSteps, this.routedCount * 2);
      }
      this.routedSteps[this.routedCount++] = limit.steps();
      this.routedTotal += limit.steps();
    }
  }

  int limitHits() {
    return this.limitHits;
  }

  /** The share of the steps of {@code sorted} (ascending) used by its costliest tenth, in %. */
  private static long costliestTenthPercent(long[] sorted) {
    long total = 0;
    for (long steps : sorted) {
      total += steps;
    }
    if (total == 0) {
      return 0;
    }
    long top = 0;
    int count = Math.max(1, sorted.length / 10);
    for (int i = sorted.length - count; i < sorted.length; i++) {
      top += sorted[i];
    }
    return Math.round(100.0 * top / total);
  }

  /** One sentence for the pass log, or null when nothing was searched. */
  String describe(String what) {
    if (this.searches == 0) {
      return null;
    }
    String connectedPart =
        this.alreadyConnected == 0
            ? ""
            : String.format(Locale.US, "; %d more were already connected", this.alreadyConnected);
    long[] sorted = Arrays.copyOf(this.routedSteps, this.routedCount);
    Arrays.sort(sorted);
    String routed =
        sorted.length == 0
            ? "none routed"
            : String.format(
                Locale.US,
                "routed ones took median %d, p99 %d, max %d steps,"
                    + " the costliest tenth %d%% of them",
                sorted[sorted.length / 2],
                sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1)],
                sorted[sorted.length - 1],
                costliestTenthPercent(sorted));
    int failed = this.searches - this.routedCount;
    String failedPart =
        failed == 0
            ? ""
            : String.format(
                Locale.US,
                "; %d did not route and took %d steps (%d%%)",
                failed,
                this.totalSteps - this.routedTotal,
                Math.round(100.0 * (this.totalSteps - this.routedTotal) / this.totalSteps));
    return String.format(
        Locale.US,
        "Search work: %d %s searches, %d steps, %d stopped at the search limit; %s%s%s.",
        this.searches,
        what,
        this.totalSteps,
        this.limitHits,
        routed,
        failedPart,
        connectedPart);
  }
}
