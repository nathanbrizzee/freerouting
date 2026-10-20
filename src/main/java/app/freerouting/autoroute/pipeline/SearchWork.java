package app.freerouting.autoroute.pipeline;

import app.freerouting.core.StoppableThread;
import app.freerouting.datastructures.TimeLimit;
import java.util.Arrays;
import java.util.Locale;

/**
 * Search steps spent in one pass: how many searches ran, how many stopped at their limit, and the
 * steps that the routed searches needed. The log line lets a person or an agent see whether the
 * per-search limit is cutting searches short and what a step budget would need to allow.
 */
final class SearchWork {

  private long[] routedSteps = new long[64];
  private int routedCount;
  private int searches;
  private int limitHits;
  private long totalSteps;

  void reset() {
    this.routedCount = 0;
    this.searches = 0;
    this.limitHits = 0;
    this.totalSteps = 0;
  }

  /**
   * Records one finished search under {@code limit} and adds its steps to the job's search step
   * budget on {@code thread}, which may be null.
   */
  void record(TimeLimit limit, boolean routed, StoppableThread thread) {
    if (thread != null) {
      thread.addSearchSteps(limit.steps());
    }
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
    }
  }

  int limitHits() {
    return this.limitHits;
  }

  /** One sentence for the pass log, or null when nothing was searched. */
  String describe(String what) {
    if (this.searches == 0) {
      return null;
    }
    long[] sorted = Arrays.copyOf(this.routedSteps, this.routedCount);
    Arrays.sort(sorted);
    String routed =
        sorted.length == 0
            ? "none routed"
            : String.format(
                Locale.US,
                "routed ones took median %d, p99 %d, max %d steps",
                sorted[sorted.length / 2],
                sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.99) - 1)],
                sorted[sorted.length - 1]);
    return String.format(
        Locale.US,
        "Search work: %d %s searches, %d steps, %d stopped at the search limit; %s.",
        this.searches,
        what,
        this.totalSteps,
        this.limitHits,
        routed);
  }
}
