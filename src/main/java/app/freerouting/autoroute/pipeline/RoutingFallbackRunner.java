package app.freerouting.autoroute.pipeline;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.drc.ClearanceBaseline;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-routes an unfinished board under the configured fallback strategies and keeps the best result.
 * A residual board (a few open connections) is continued first. A fanout-only strategy whose fanout
 * leaves a board an earlier pass loop already started on stops after that fanout.
 *
 * <p>Ported from freerouting.net (Erik Gavriluk, Experimental Television Center).
 */
final class RoutingFallbackRunner {

  private final Map<String, RoutingFallback.Outcome> passLoopStarts = new LinkedHashMap<>();
  private final BatchAutorouter listenerSource;

  /** Connection attempts the fallback stage has left, or a negative value for no limit. */
  private long workBudgetLeft = -1;

  RoutingFallbackRunner(BatchAutorouter listenerSource) {
    this.listenerSource = listenerSource;
  }

  BatchAutorouter run(
      RoutingJob job,
      BatchAutorouter defaultRouter,
      List<RoutingFallback.Strategy> strategies,
      byte[] input) {
    ClearanceBaseline inputBaseline = job.board.clearanceBaseline;
    RoutingFallback.Outcome best =
        RoutingFallback.Outcome.of("default", job.board, job.routerSettings);
    if (best.isCompleteAndClean()) {
      return defaultRouter;
    }
    BatchAutorouter.StopReason reason = defaultRouter.lastStopReason;
    if (!RoutingFallback.isRetryable(reason)) {
      job.logInfo(
          "Routing fallback skipped: the pass loop stopped for "
              + RoutingFallback.describe(reason)
              + " with "
              + best
              + ".");
      return defaultRouter;
    }
    boolean fanoutRuns = job.routerSettings.isFanoutEnabled() && !job.board.getSmdPins().isEmpty();
    List<RoutingFallback.Strategy> applicable =
        RoutingFallback.applicable(strategies, job.routerSettings, fanoutRuns);
    List<RoutingFallback.Strategy> continuations = new ArrayList<>();
    List<RoutingFallback.Strategy> alternatives = new ArrayList<>();
    for (RoutingFallback.Strategy strategy : applicable) {
      if (strategy.continueBest) {
        continuations.add(strategy);
      } else {
        alternatives.add(strategy);
      }
    }
    boolean residual = RoutingSupervisor.residual(job.routerSettings, best.unrouted);
    if (alternatives.isEmpty() && continuations.isEmpty() && !residual) {
      return defaultRouter;
    }
    job.status().fallbackPlanned(alternatives.size() + continuations.size());
    recordAttempt(job, best, reason, 0);
    job.logInfo(
        "Routing fallback: the default configuration stopped at "
            + RoutingFallback.describe(reason)
            + " with "
            + best
            + "; trying "
            + alternatives.size()
            + " alternative "
            + (alternatives.size() == 1 ? "strategy" : "strategies")
            + (residual ? ", then continuing the best board" : "")
            + ".");
    double workRatio = workRatio(job.routerSettings);
    if (workRatio > 0) {
      this.workBudgetLeft = Math.max(1, Math.round(workRatio * defaultRouter.totalItemsRouted));
      job.logInfo(
          "Routing fallback work budget: "
              + this.workBudgetLeft
              + " connection attempts ("
              + workRatio
              + " times the default run's "
              + defaultRouter.totalItemsRouted
              + ").");
    }
    if (defaultRouter.passLoopStartHash != null) {
      this.passLoopStarts.put(defaultRouter.passLoopStartHash, best);
    }
    ClearanceBaseline defaultClearance =
        RoutingFallback.captureClearance(job.board, best.violations);
    Attempt bestAttempt = new Attempt(best, job.board, defaultRouter, defaultClearance);
    RoutingBoard continuedFrom = null;
    if (residual && !alternatives.isEmpty()) {
      bestAttempt = runContinuations(job, bestAttempt, automaticContinuations(job.routerSettings));
      continuedFrom = bestAttempt.board;
    }
    for (RoutingFallback.Strategy strategy : alternatives) {
      if (stopped(job) || bestAttempt.outcome.isCompleteAndClean() || budgetSpent(job)) {
        break;
      }
      Attempt attempt = runAttempt(job, input, strategy, false, inputBaseline);
      if (attempt == null) {
        continue;
      }
      if (attempt.router.lastStopReason == BatchAutorouter.StopReason.REPEATED) {
        RoutingFallback.Outcome repeated =
            this.passLoopStarts.get(attempt.router.passLoopStartHash);
        job.logInfo(
            "Routing fallback '"
                + strategy.name
                + "': its fanout left the board an earlier pass loop started on; stopped after the fanout ("
                + (repeated != null ? repeated : attempt.outcome)
                + ").");
        if (stopAlternatives(bestAttempt.outcome, attempt.router.lastStopReason)) {
          break;
        }
        continue;
      }
      if (strategy.fanoutOnly
          && attempt.router.passLoopStartHash != null
          && attempt.router.lastStopReason != BatchAutorouter.StopReason.TIMED_OUT) {
        this.passLoopStarts.putIfAbsent(attempt.router.passLoopStartHash, attempt.outcome);
      }
      job.logInfo(
          "Routing fallback '"
              + strategy.name
              + "': "
              + attempt.outcome
              + " after "
              + RoutingFallback.describe(attempt.router.lastStopReason)
              + ".");
      if (replaces(attempt, bestAttempt)) {
        bestAttempt = attempt;
      } else {
        job.board = bestAttempt.board;
      }
      if (stopAlternatives(bestAttempt.outcome, attempt.router.lastStopReason)) {
        break;
      }
    }
    List<RoutingFallback.Strategy> after = new ArrayList<>(continuations);
    if (continuedFrom != null && bestAttempt.board == continuedFrom) {
      if (!bestAttempt.outcome.isCompleteAndClean()) {
        job.logInfo(
            "Routing fallback: no alternative replaced the board the continuations ran on; not continuing it again.");
      }
    } else {
      after.addAll(automaticContinuations(job.routerSettings));
    }
    if (!after.isEmpty() && !budgetSpent(job)) {
      bestAttempt = runContinuations(job, bestAttempt, after);
    }
    job.board = bestAttempt.board;
    if (job.resultPhaseMetrics != null && job.resultPhaseMetrics.fallback != null) {
      job.resultPhaseMetrics.fallback.chosen = bestAttempt.outcome.strategy;
    }
    job.logInfo(
        "Routing fallback chose '"
            + bestAttempt.outcome.strategy
            + "': "
            + bestAttempt.outcome
            + ".");
    return bestAttempt.router;
  }

  /**
   * Exploration attempts after fallback. Each attempt is a full route of the best board with a
   * lighter fallback list. Later tries of a kind go further. Stops after {@code explore_patience}
   * attempts without an improvement, or when the board is complete.
   */
  BatchAutorouter explore(RoutingJob job, BatchAutorouter incumbent) {
    if (!RoutingSupervisor.explores(job.routerSettings) || stopped(job)) {
      return incumbent;
    }
    // Exploration is opt-in and has its own patience; the fallback budget does not apply to it.
    this.workBudgetLeft = -1;
    RoutingSupervisor supervisor = new RoutingSupervisor();
    RoutingFallback.Outcome best =
        RoutingFallback.Outcome.of("incumbent", job.board, job.routerSettings);
    Attempt bestAttempt = new Attempt(best, job.board, incumbent, null);
    RouterSettings jobSettings = job.routerSettings;
    int round = 0;
    while (!bestAttempt.outcome.isCompleteAndClean()
        && !supervisor.explorationExhausted(jobSettings)
        && !stopped(job)) {
      round++;
      RoutingSupervisor.Attempt planned =
          supervisor.nextAttempt(round, jobSettings, bestAttempt.board);
      RouterSettings settings = jobSettings.clone();
      RoutingSupervisor.apply(planned, jobSettings, settings);
      byte[] input;
      try {
        input = bestAttempt.board.serialize(false);
      } catch (RuntimeException e) {
        job.logWarning("Exploration stopped; the best board could not be saved: " + e.getMessage());
        break;
      }
      RoutingBoard copy = (RoutingBoard) BasicBoard.deserialize(input);
      if (copy.clearanceBaseline == null) {
        copy.clearanceBaseline = bestAttempt.board.clearanceBaseline;
      }
      if ("neighbourhood".equals(planned.kind())) {
        int ripped = RetryNeighbourhood.ripNearOpenPins(copy, planned.tryNumber());
        job.logInfo(
            "Exploration '"
                + planned.name()
                + "' ripped "
                + ripped
                + " item(s) within "
                + RetryNeighbourhood.Reach.of(planned.tryNumber()).radiusMm()
                + " mm.");
      }
      job.logInfo("Exploration attempt '" + planned.name() + "' from " + bestAttempt.outcome + ".");
      int openBefore = bestAttempt.outcome.unrouted;
      Attempt attempt = runPrepared(job, copy, settings, false, "explore-" + planned.kind());
      boolean kept = false;
      if (attempt != null && replaces(attempt, bestAttempt)) {
        bestAttempt = attempt;
        kept = true;
      }
      int openAfter = bestAttempt.outcome.unrouted;
      supervisor.attemptDone(planned, openBefore, openAfter, kept);
    }
    job.board = bestAttempt.board;
    job.routerSettings = jobSettings;
    return bestAttempt.router;
  }

  private Attempt runContinuations(
      RoutingJob job, Attempt bestAttempt, List<RoutingFallback.Strategy> continuations) {
    for (RoutingFallback.Strategy strategy : continuations) {
      if (bestAttempt.outcome.isCompleteAndClean() || stopped(job) || budgetSpent(job)) {
        break;
      }
      if (!strategy.continueBest
          && !RoutingSupervisor.residual(job.routerSettings, bestAttempt.outcome.unrouted)) {
        break;
      }
      RoutingBoard copy = bestAttempt.board.deepCopy();
      RouterSettings settings = job.routerSettings.clone();
      strategy.apply(settings);
      settings.setFanoutEnabled(false);
      Attempt attempt = runPrepared(job, copy, settings, true, strategy.name);
      if (attempt == null) {
        break;
      }
      job.logInfo(
          "Routing fallback continuation '"
              + strategy.name
              + "': "
              + attempt.outcome
              + " after "
              + RoutingFallback.describe(attempt.router.lastStopReason)
              + ".");
      if (replaces(attempt, bestAttempt)) {
        bestAttempt = attempt;
      } else if (!strategy.continueBest) {
        job.board = bestAttempt.board;
        job.logInfo(
            "Routing fallback: stopping residual continuations after '"
                + strategy.name
                + "' did not improve the incumbent.");
        break;
      } else {
        job.board = bestAttempt.board;
      }
    }
    return bestAttempt;
  }

  private Attempt runAttempt(
      RoutingJob job,
      byte[] input,
      RoutingFallback.Strategy strategy,
      boolean keepFanoutVias,
      ClearanceBaseline inputBaseline) {
    RoutingBoard board = (RoutingBoard) BasicBoard.deserialize(input);
    if (board.clearanceBaseline == null) {
      board.clearanceBaseline = inputBaseline;
    }
    RouterSettings settings = job.routerSettings.clone();
    strategy.apply(settings);
    Attempt attempt = runPrepared(job, board, settings, keepFanoutVias, strategy.name);
    if (attempt != null
        && strategy.fanoutOnly
        && attempt.router.lastStopReason == BatchAutorouter.StopReason.REPEATED) {
      RoutingFallback.Outcome repeated = this.passLoopStarts.get(attempt.router.passLoopStartHash);
      if (repeated != null) {
        return new Attempt(
            new RoutingFallback.Outcome(
                strategy.name,
                repeated.unrouted,
                repeated.newOrChanged,
                repeated.violations,
                repeated.score,
                repeated.designBlocked),
            job.board,
            attempt.router,
            attempt.baseline);
      }
    }
    return attempt;
  }

  private Attempt runPrepared(
      RoutingJob job,
      RoutingBoard board,
      RouterSettings settings,
      boolean keepFanoutVias,
      String strategyName) {
    RouterSettings savedSettings = job.routerSettings;
    RoutingBoard savedBoard = job.board;
    job.thread.resetAutoRouterStopRequest();
    job.status().attemptStarted(strategyName);
    job.board = board;
    job.routerSettings = settings;
    long attemptStart = System.nanoTime();
    try {
      boolean removeUnconnectedVias = !keepFanoutVias && !settings.isFanoutEnabled();
      BatchAutorouter router =
          BatchAutorouter.createAttempt(job, board, settings, removeUnconnectedVias);
      router.copyListenersFrom(this.listenerSource);
      if (this.workBudgetLeft >= 0) {
        router.workBudget = (int) Math.min(Integer.MAX_VALUE, this.workBudgetLeft);
      }
      RoutingFallback.Strategy strategy = RoutingFallback.find(strategyName);
      if (strategy != null && strategy.fanoutOnly) {
        router.setRepeatsPassLoop(hash -> hash != null && this.passLoopStarts.containsKey(hash));
      }
      try {
        router.runBatchLoop();
      } finally {
        if (this.workBudgetLeft >= 0) {
          this.workBudgetLeft = Math.max(0, this.workBudgetLeft - router.totalItemsRouted);
        }
      }
      RoutingFallback.Outcome outcome =
          RoutingFallback.Outcome.of(strategyName, job.board, savedSettings);
      ClearanceBaseline baseline = RoutingFallback.captureClearance(job.board, outcome.violations);
      recordAttempt(job, outcome, router.lastStopReason, (System.nanoTime() - attemptStart) / 1e9f);
      return new Attempt(outcome, job.board, router, baseline);
    } catch (RuntimeException e) {
      job.board = savedBoard;
      job.logError("Routing fallback attempt '" + strategyName + "' failed and was discarded.", e);
      return null;
    } finally {
      job.routerSettings = savedSettings;
    }
  }

  private static boolean replaces(Attempt candidate, Attempt incumbent) {
    int changed =
        RoutingFallback.newOrChangedClearance(
            candidate.board, candidate.outcome.violations, incumbent.baseline);
    return RoutingFallback.Outcome.canReplace(candidate.outcome, incumbent.outcome, changed);
  }

  private static boolean stopAlternatives(
      RoutingFallback.Outcome best, BatchAutorouter.StopReason reason) {
    return best.isCompleteAndClean()
        || reason == BatchAutorouter.StopReason.TIMED_OUT
        || reason == BatchAutorouter.StopReason.CANCELLED;
  }

  /**
   * Adds an attempt to the result manifest, so an agent can see what the fallback stage tried and
   * what each attempt cost without reading the log.
   */
  private static void recordAttempt(
      RoutingJob job,
      RoutingFallback.Outcome outcome,
      BatchAutorouter.StopReason reason,
      float seconds) {
    if (job.resultPhaseMetrics == null) {
      return;
    }
    if (job.resultPhaseMetrics.fallback == null) {
      job.resultPhaseMetrics.fallback = new RoutingResultManifest.FallbackDetail();
    }
    RoutingResultManifest.FallbackAttempt attempt = new RoutingResultManifest.FallbackAttempt();
    attempt.strategy = outcome.strategy;
    attempt.stopReason = reason != null ? reason.name() : null;
    attempt.unrouted = outcome.unrouted;
    attempt.newOrChangedViolations = outcome.newOrChanged;
    attempt.clearanceViolations = outcome.violations;
    attempt.routerScore = outcome.score;
    attempt.durationSeconds = seconds;
    job.resultPhaseMetrics.fallback.attempts.add(attempt);
  }

  private boolean budgetReported;

  /** True once the work budget is used up; the first call logs it. */
  private boolean budgetSpent(RoutingJob job) {
    if (this.workBudgetLeft != 0) {
      return false;
    }
    if (!this.budgetReported) {
      this.budgetReported = true;
      job.logInfo(
          "Routing fallback: the work budget is used up; no further attempts start"
              + " (router.autorouter.fallback_work_ratio raises it, 0 removes it).");
    }
    return true;
  }

  private static double workRatio(RouterSettings settings) {
    Double ratio = settings.autorouter != null ? settings.autorouter.fallbackWorkRatio : null;
    return ratio != null ? ratio : 0;
  }

  private static boolean stopped(RoutingJob job) {
    return job.thread.isStopRequested() || job.state == RoutingJobState.TIMED_OUT;
  }

  private static List<RoutingFallback.Strategy> automaticContinuations(RouterSettings settings) {
    List<RoutingFallback.Strategy> result = new ArrayList<>();
    RoutingFallback.Strategy continueStrategy = RoutingFallback.find("continue");
    if (continueStrategy != null) {
      result.add(continueStrategy);
    }
    if (settings.getNeckWidthUm() > 0) {
      RoutingFallback.Strategy noNeck = RoutingFallback.find("continue-no-neck");
      if (noNeck != null) {
        result.add(noNeck);
      }
    }
    return result;
  }

  private record Attempt(
      RoutingFallback.Outcome outcome,
      RoutingBoard board,
      BatchAutorouter router,
      ClearanceBaseline baseline) {}
}
