package app.freerouting.datastructures;

import java.util.Date;

/**
 * Class used to cancel a performance critical algorithm after a time limit is exceeded.
 *
 * <p>Every check is counted. A limit made by {@link #ofSteps(long)} stops after that many checks
 * and never reads the clock, so the same board stops at the same point on every run and machine.
 * The maze search checks once per expansion step.
 */
public class TimeLimit {

  private final long timeStamp;
  private int timeLimit;
  private final boolean timed;
  private long maxSteps;
  private long steps;
  private boolean exceeded;

  /** Creates a new instance with a time limit of milliSeconds milliseconds. */
  public TimeLimit(int milliSeconds) {
    this(milliSeconds, true, 0);
  }

  private TimeLimit(int milliSeconds, boolean timed, long maxSteps) {
    this.timeLimit = milliSeconds;
    this.timed = timed;
    this.maxSteps = maxSteps;
    this.timeStamp = timed ? new Date().getTime() : 0;
  }

  /** A deterministic limit: exceeded after {@code maxSteps} checks, without reading the clock. */
  public static TimeLimit ofSteps(long maxSteps) {
    return new TimeLimit(0, false, Math.max(1, maxSteps));
  }

  /** Returns true, if the time limit provided in the constructor of this class is exceeded. */
  public boolean limitExceeded() {
    this.steps++;
    if (!this.exceeded) {
      if (this.timed) {
        this.exceeded = new Date().getTime() - this.timeStamp > this.timeLimit;
      } else {
        this.exceeded = this.steps > this.maxSteps;
      }
    }
    return this.exceeded;
  }

  /** Whether a check has found the limit exceeded. */
  public boolean wasExceeded() {
    return this.exceeded;
  }

  /** The number of checks so far, which for the maze search is its expansion steps. */
  public long steps() {
    return this.steps;
  }

  /** Multiplies this TimeLimit by factor. */
  public void multiply(double factor) {
    if (factor <= 0) {
      return;
    }
    double newLimit = factor * this.timeLimit;
    newLimit = Math.min(newLimit, Integer.MAX_VALUE);
    this.timeLimit = (int) newLimit;
    this.maxSteps = (long) Math.min(factor * this.maxSteps, Long.MAX_VALUE);
  }
}
