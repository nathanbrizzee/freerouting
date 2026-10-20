package app.freerouting.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live progress of a routing job, for agents and scripts that poll it while the job runs.
 *
 * <p>The router reports stages, passes and items here; readers take a {@link #snapshot()} from
 * another thread. Nothing in it feeds back into routing, so the wall-clock times it records cannot
 * change a route. The estimate of the time left is rough by nature and reported as a range.
 */
public final class RoutingStatus {

  private static final int MAX_PASS_HISTORY = 200;
  private static final int RECENT_PASSES_FOR_ESTIMATE = 3;

  private final Instant startedAt = Instant.now();
  private String stage = "starting";
  private Instant stageStartedAt = startedAt;
  private String attempt = "default";
  private int attemptNumber;
  private int attemptsPlanned = -1;
  private Instant defaultRunFinishedAt;
  private int pass;
  private Instant passStartedAt;
  private int passItemsTotal;
  private int passItemsDone;
  private String currentNet;
  private int unroutedStart = -1;
  private int unroutedNow = -1;
  private int unroutedBest = -1;
  private int violationsNow = -1;
  private int attemptBestUnrouted = -1;
  private int attemptPassOfBest;
  private Instant bestImprovedAt;
  private Instant lastRoutedAt;
  private int stagnationPassLimit;
  private int maxPasses;
  private Instant timeoutAt;
  private String stopReason;
  private String finalState;
  private final Deque<Map<String, Object>> passHistory = new ArrayDeque<>();
  private final List<Double> attemptPassSeconds = new ArrayList<>();

  /** Starts a stage: fanout, autoroute, optimizer. */
  public synchronized void stageStarted(String name) {
    this.stage = name;
    this.stageStartedAt = Instant.now();
  }

  /** The pass loop's stop rules, used for the upper end of the estimate. */
  public synchronized void passLimits(int stagnationPassLimit, int maxPasses, Instant timeoutAt) {
    this.stagnationPassLimit = stagnationPassLimit;
    this.maxPasses = maxPasses;
    this.timeoutAt = timeoutAt;
  }

  /** The fallback stage starts with this many attempts after the default run. */
  public synchronized void fallbackPlanned(int attempts) {
    this.attemptsPlanned = attempts;
    this.defaultRunFinishedAt = Instant.now();
  }

  /** A fallback attempt starts; its passes are counted from 1 again. */
  public synchronized void attemptStarted(String name) {
    this.attempt = name;
    this.attemptNumber++;
    this.attemptBestUnrouted = -1;
    this.attemptPassOfBest = 0;
    this.attemptPassSeconds.clear();
    this.pass = 0;
  }

  /** An autoroute pass starts with this many items to route and this many open connections. */
  public synchronized void passStarted(int passNo, int itemsTotal, int unrouted) {
    this.passStartedAt = Instant.now();
    if (!"autoroute".equals(this.stage)) {
      this.stage = "autoroute";
      this.stageStartedAt = this.passStartedAt;
    }
    this.pass = passNo;
    this.passItemsTotal = itemsTotal;
    this.passItemsDone = 0;
    if (this.unroutedStart < 0) {
      this.unroutedStart = unrouted;
    }
    this.unroutedNow = unrouted;
  }

  /** One item of the pass was attempted. */
  public synchronized void itemDone(boolean routed, String netName) {
    this.passItemsDone++;
    this.currentNet = netName;
    if (routed) {
      this.lastRoutedAt = Instant.now();
    }
  }

  /** A pass ended with this result. */
  public synchronized void passFinished(int passNo, int unrouted, int violations) {
    Instant now = Instant.now();
    double seconds =
        this.passStartedAt == null ? 0 : Duration.between(passStartedAt, now).toMillis() / 1000.0;
    this.unroutedNow = unrouted;
    this.violationsNow = violations;
    if (this.unroutedBest < 0 || unrouted < this.unroutedBest) {
      this.unroutedBest = unrouted;
      this.bestImprovedAt = now;
    }
    if (this.attemptBestUnrouted < 0 || unrouted < this.attemptBestUnrouted) {
      this.attemptBestUnrouted = unrouted;
      this.attemptPassOfBest = passNo;
    }
    this.attemptPassSeconds.add(seconds);
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("attempt", attempt);
    entry.put("pass", passNo);
    entry.put("unrouted", unrouted);
    entry.put("violations", violations);
    entry.put("seconds", round(seconds));
    passHistory.addLast(entry);
    while (passHistory.size() > MAX_PASS_HISTORY) {
      passHistory.removeFirst();
    }
  }

  /** The router stopped its pass loop for this reason. */
  public synchronized void stopped(String reason) {
    this.stopReason = reason;
  }

  /** The job ended in this state. */
  public synchronized void finished(String state) {
    this.finalState = state;
    this.stage = "finished";
    this.stageStartedAt = Instant.now();
  }

  /** A consistent copy of the status, as plain maps and numbers ready for JSON. */
  public synchronized Map<String, Object> snapshot() {
    Instant now = Instant.now();
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("schema", 1);
    s.put("time", now.toString());
    s.put("elapsed_s", seconds(startedAt, now));
    s.put("stage", stage);
    s.put("stage_elapsed_s", seconds(stageStartedAt, now));
    s.put("attempt", attempt);
    s.put("attempt_number", attemptNumber);
    if (attemptsPlanned >= 0) {
      s.put("attempts_planned", attemptsPlanned);
    }
    s.put("pass", pass);
    s.put("pass_items_done", passItemsDone);
    s.put("pass_items_total", passItemsTotal);
    if (currentNet != null) {
      s.put("current_net", currentNet);
    }
    s.put("unrouted_start", unroutedStart);
    s.put("unrouted_now", unroutedNow);
    s.put("unrouted_best", unroutedBest);
    s.put("violations_now", violationsNow);
    s.put("passes_since_attempt_best", Math.max(0, pass - attemptPassOfBest));
    if (bestImprovedAt != null) {
      s.put("since_best_improved_s", seconds(bestImprovedAt, now));
    }
    if (lastRoutedAt != null) {
      s.put("since_last_routed_connection_s", seconds(lastRoutedAt, now));
    }
    if (finalState == null) {
      s.put("estimate", estimate(now));
    } else {
      s.put("final_state", finalState);
    }
    if (stopReason != null) {
      s.put("stop_reason", stopReason);
    }
    s.put("passes", new ArrayList<>(passHistory));
    return s;
  }

  private Map<String, Object> estimate(Instant now) {
    Map<String, Object> e = new LinkedHashMap<>();
    if (!"autoroute".equals(stage) || passStartedAt == null) {
      e.put("available", false);
      e.put("reason", "estimates are made during autoroute passes");
      return e;
    }
    double avgPass = averageRecentPassSeconds();
    double passElapsed = seconds(passStartedAt, now);
    double restOfPass;
    if (passItemsDone > 0 && passItemsTotal > passItemsDone) {
      restOfPass = passElapsed / passItemsDone * (passItemsTotal - passItemsDone);
    } else if (passItemsDone >= passItemsTotal) {
      restOfPass = 0;
    } else {
      restOfPass = Math.max(0, avgPass - passElapsed);
    }
    int passesLeft = Math.max(0, stagnationPassLimit - Math.max(0, pass - attemptPassOfBest));
    if (maxPasses > 0) {
      passesLeft = Math.min(passesLeft, Math.max(0, maxPasses - pass));
    }
    double low = restOfPass;
    double high = restOfPass + passesLeft * avgPass;
    boolean fallbackIncluded = false;
    if (attemptsPlanned >= 0 && defaultRunFinishedAt != null) {
      double defaultRun = seconds(startedAt, defaultRunFinishedAt);
      high += Math.max(0, attemptsPlanned - attemptNumber) * defaultRun;
      fallbackIncluded = true;
    }
    boolean cappedByTimeout = false;
    if (timeoutAt != null) {
      double untilTimeout = Math.max(0, seconds(now, timeoutAt));
      if (high > untilTimeout) {
        high = untilTimeout;
        cappedByTimeout = true;
      }
      low = Math.min(low, untilTimeout);
    }
    e.put("available", true);
    e.put("low_s", round(low));
    e.put("high_s", round(high));
    e.put("rest_of_pass_s", round(restOfPass));
    e.put("passes_left_at_most", passesLeft);
    e.put("fallback_included", fallbackIncluded);
    if (!fallbackIncluded) {
      e.put("note", "a fallback stage may follow this run; it is not included until it starts");
    }
    e.put("capped_by_timeout", cappedByTimeout);
    e.put("confidence", attemptPassSeconds.size() >= 2 ? "medium" : "low");
    return e;
  }

  private double averageRecentPassSeconds() {
    int n = attemptPassSeconds.size();
    if (n == 0) {
      return passStartedAt == null ? 0 : seconds(passStartedAt, Instant.now());
    }
    double sum = 0;
    int from = Math.max(0, n - RECENT_PASSES_FOR_ESTIMATE);
    for (int i = from; i < n; i++) {
      sum += attemptPassSeconds.get(i);
    }
    return sum / (n - from);
  }

  private static double seconds(Instant from, Instant to) {
    return round(Duration.between(from, to).toMillis() / 1000.0);
  }

  private static double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
