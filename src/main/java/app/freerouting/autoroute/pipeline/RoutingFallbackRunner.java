package app.freerouting.autoroute.pipeline;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.core.RoutingStatus;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.drc.ClearanceBaseline;
import app.freerouting.settings.RouterSettings;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
      byte[] input,
      Race race) {
    ClearanceBaseline inputBaseline = job.board.clearanceBaseline;
    RoutingFallback.Outcome best =
        RoutingFallback.Outcome.of("default", job.board, job.routerSettings);
    if (best.isCompleteAndClean()) {
      cancelRace(job, race, "the default run is complete");
      return defaultRouter;
    }
    BatchAutorouter.StopReason reason = defaultRouter.lastStopReason;
    if (!RoutingFallback.isRetryable(reason)) {
      cancelRace(job, race, "the default run stopped: " + RoutingFallback.describe(reason));
      job.logInfo(
          "Routing fallback skipped (the pass loop stopped: "
              + RoutingFallback.describe(reason)
              + ") with "
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
    if (race != null) {
      alternatives.clear();
    }
    boolean residual = RoutingSupervisor.residual(job.routerSettings, best.unrouted);
    if (race == null && alternatives.isEmpty() && continuations.isEmpty() && !residual) {
      return defaultRouter;
    }
    int alternativeCount = race != null ? race.streams.size() : alternatives.size();
    job.status().fallbackPlanned(alternativeCount + continuations.size());
    recordAttempt(job, best, reason, 0);
    job.logInfo(
        "Routing fallback: the default configuration stopped at "
            + RoutingFallback.describe(reason)
            + " with "
            + best
            + "; "
            + (race != null ? "merging " : "trying ")
            + alternativeCount
            + " alternative "
            + (alternativeCount == 1 ? "strategy" : "strategies")
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
    boolean continueFirst = residual && !alternatives.isEmpty();
    int threads =
        fallbackThreads(job.routerSettings, alternatives.size() + (continueFirst ? 1 : 0));
    if (race != null) {
      bestAttempt = mergeRace(job, race, bestAttempt);
    } else if (threads > 1) {
      ParallelResult parallel =
          runParallel(job, input, inputBaseline, bestAttempt, continueFirst, alternatives, threads);
      bestAttempt = parallel.best();
      continuedFrom = parallel.continuedFrom();
    } else {
      if (continueFirst) {
        bestAttempt =
            runContinuations(job, bestAttempt, automaticContinuations(job.routerSettings));
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
        bestAttempt = merge(job, strategy, attempt, bestAttempt);
        if (stopAlternatives(bestAttempt.outcome, attempt.router.lastStopReason)) {
          break;
        }
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

  /** Alternatives started beside the default run ({@code fallback_race}), merged after it. */
  static final class Race {
    private final List<Stream> streams;
    private final ExecutorService executor;

    private Race(List<Stream> streams, ExecutorService executor) {
      this.streams = streams;
      this.executor = executor;
    }
  }

  /**
   * Starts the alternative strategies on copies of the input board beside the default run, when
   * {@code fallback_race} is on and more than one fallback thread is allowed; returns null
   * otherwise. Each attempt gets its own settings copy, no share of the default run's work (it is
   * not known yet), and an equal share of the search step budget left.
   */
  static Race startRace(RoutingJob job, List<RoutingFallback.Strategy> strategies, byte[] input) {
    if (input == null
        || job.routerSettings.autorouter == null
        || !Boolean.TRUE.equals(job.routerSettings.autorouter.fallbackRace)) {
      return null;
    }
    boolean fanoutRuns = job.routerSettings.isFanoutEnabled() && !job.board.getSmdPins().isEmpty();
    List<RoutingFallback.Strategy> alternatives = new ArrayList<>();
    for (RoutingFallback.Strategy strategy :
        RoutingFallback.applicable(strategies, job.routerSettings, fanoutRuns)) {
      if (!strategy.continueBest) {
        alternatives.add(strategy);
      }
    }
    int threads = fallbackThreads(job.routerSettings, alternatives.size());
    if (alternatives.isEmpty() || threads <= 1) {
      return null;
    }
    ClearanceBaseline inputBaseline = job.board.clearanceBaseline;
    long stepBudget = job.thread.searchStepBudget();
    long stepShare =
        stepBudget > 0
            ? Math.max(1, (stepBudget - job.thread.searchSteps()) / (alternatives.size() + 1))
            : 0;
    List<Stream> streams = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (RoutingFallback.Strategy strategy : alternatives) {
      Stream stream = new Stream(strategy.name, strategy);
      stream.thread = new StreamThread(job.thread, stepShare, false);
      stream.job = new StreamJob(job, stream.name, stream.thread, false);
      stream.job.routerSettings = job.routerSettings.clone();
      stream.runner = new RoutingFallbackRunner(null);
      streams.add(stream);
      names.add(stream.name);
    }
    job.logInfo(
        "Routing fallback race: starting "
            + streams.size()
            + " alternative attempts beside the default run on "
            + threads
            + " threads ("
            + String.join(", ", names)
            + ")"
            + (stepShare > 0 ? "; each may make " + stepShare + " search steps" : "")
            + ".");
    AtomicInteger threadNo = new AtomicInteger();
    ExecutorService executor =
        Executors.newFixedThreadPool(
            threads,
            task -> {
              Thread thread = new Thread(task, "fallback-race-" + threadNo.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    Race race = new Race(streams, executor);
    for (Stream stream : streams) {
      stream.future =
          executor.submit(() -> stream.runner.runStream(stream, input, inputBaseline, null, job));
    }
    return race;
  }

  /** Stops the racing attempts that are no longer needed and waits for them. */
  static void cancelRace(RoutingJob job, Race race, String why) {
    if (race == null) {
      return;
    }
    for (Stream stream : race.streams) {
      stream.thread.requestStop();
    }
    for (Stream stream : race.streams) {
      await(job, stream);
    }
    race.executor.shutdownNow();
    job.logInfo("Routing fallback race: stopped the alternative attempts (" + why + ").");
  }

  /**
   * Merges the racing attempts into the incumbent in the sequential order, as the parallel stage
   * does; once the incumbent is complete and clean the later attempts are stopped.
   */
  private Attempt mergeRace(RoutingJob job, Race race, Attempt incumbent) {
    Attempt best = incumbent;
    long mergedSteps = 0;
    try {
      for (int i = 0; i < race.streams.size(); i++) {
        Stream stream = race.streams.get(i);
        final Attempt attempt = await(job, stream);
        mergedSteps += stream.thread.searchSteps();
        copyAttemptRecords(stream.job, job);
        job.logInfo(
            "Routing fallback '"
                + stream.name
                + "' finished: "
                + stream.thread.searchSteps()
                + " search steps, "
                + String.format(Locale.ROOT, "%.1f", stream.cpuSeconds)
                + " s CPU, "
                + String.format(Locale.ROOT, "%.1f", stream.wallSeconds)
                + " s.");
        if (attempt != null) {
          best = merge(job, stream.strategy, attempt, best);
        }
        if (best.outcome.isCompleteAndClean()) {
          cancelAfter(job, race.streams, i);
          break;
        }
      }
    } finally {
      race.executor.shutdownNow();
    }
    job.thread.addSearchSteps(mergedSteps);
    job.board = best.board;
    return best;
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

  /** Merges one alternative's attempt into the incumbent and returns the new incumbent. */
  private Attempt merge(
      RoutingJob job, RoutingFallback.Strategy strategy, Attempt attempt, Attempt bestAttempt) {
    if (attempt.router.lastStopReason == BatchAutorouter.StopReason.REPEATED) {
      RoutingFallback.Outcome repeated = this.passLoopStarts.get(attempt.router.passLoopStartHash);
      job.logInfo(
          "Routing fallback '"
              + strategy.name
              + "': its fanout left the board an earlier pass loop started on; stopped after the fanout ("
              + (repeated != null ? repeated : attempt.outcome)
              + ").");
      return bestAttempt;
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
      return attempt;
    }
    job.board = bestAttempt.board;
    return bestAttempt;
  }

  /**
   * Runs the continuation of a residual board and the alternative strategies side by side, each on
   * its own board copy, job and stop state, and merges their results in the sequential order. Each
   * stream may use the whole work budget left and an equal share of the job's search step budget
   * left. Once a stream is complete and clean, the later ones are stopped, since the merge would
   * discard them; their steps do not count toward the job's step budget, so later stages see the
   * same count on every run.
   */
  private ParallelResult runParallel(
      RoutingJob job,
      byte[] input,
      ClearanceBaseline inputBaseline,
      Attempt incumbent,
      boolean continueFirst,
      List<RoutingFallback.Strategy> alternatives,
      int threads) {
    job.thread.resetAutoRouterStopRequest();
    List<Stream> streams = new ArrayList<>();
    if (continueFirst) {
      streams.add(new Stream("continue", null));
    }
    for (RoutingFallback.Strategy strategy : alternatives) {
      streams.add(new Stream(strategy.name, strategy));
    }
    long stepBudget = job.thread.searchStepBudget();
    long stepShare =
        stepBudget > 0 ? Math.max(1, (stepBudget - job.thread.searchSteps()) / streams.size()) : 0;
    long workBudgetAtStart = this.workBudgetLeft;
    List<String> names = new ArrayList<>();
    for (int i = 0; i < streams.size(); i++) {
      Stream stream = streams.get(i);
      names.add(stream.name);
      stream.thread = new StreamThread(job.thread, stepShare);
      stream.job = new StreamJob(job, stream.name, stream.thread, i == 0);
      stream.runner = new RoutingFallbackRunner(i == 0 ? this.listenerSource : null);
      stream.runner.workBudgetLeft = workBudgetAtStart;
      stream.runner.passLoopStarts.putAll(this.passLoopStarts);
    }
    job.logInfo(
        "Routing fallback: running "
            + streams.size()
            + " attempts side by side on "
            + threads
            + " threads ("
            + String.join(", ", names)
            + "); each may make "
            + (workBudgetAtStart >= 0 ? workBudgetAtStart : "unlimited")
            + " connection attempts"
            + (stepShare > 0 ? " and " + stepShare + " search steps" : "")
            + ".");
    AtomicInteger threadNo = new AtomicInteger();
    ExecutorService executor =
        Executors.newFixedThreadPool(
            threads,
            task -> {
              Thread thread = new Thread(task, "fallback-" + threadNo.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    try {
      for (Stream stream : streams) {
        stream.future =
            executor.submit(() -> runStream(stream, input, inputBaseline, incumbent, job));
      }
      Attempt best = incumbent;
      RoutingBoard continuedFrom = null;
      long mergedSteps = 0;
      long mostWork = 0;
      for (int i = 0; i < streams.size(); i++) {
        Stream stream = streams.get(i);
        Attempt attempt = await(job, stream);
        mergedSteps += stream.thread.searchSteps();
        if (workBudgetAtStart >= 0) {
          mostWork = Math.max(mostWork, workBudgetAtStart - stream.runner.workBudgetLeft);
        }
        copyAttemptRecords(stream.job, job);
        job.logInfo(
            "Routing fallback '"
                + stream.name
                + "' finished: "
                + stream.thread.searchSteps()
                + " search steps, "
                + String.format(Locale.ROOT, "%.1f", stream.cpuSeconds)
                + " s CPU, "
                + String.format(Locale.ROOT, "%.1f", stream.wallSeconds)
                + " s.");
        if (stream.strategy == null) {
          if (attempt != null) {
            best = attempt;
          }
          continuedFrom = best.board;
        } else if (attempt != null) {
          best = merge(job, stream.strategy, attempt, best);
        }
        if (best.outcome.isCompleteAndClean()) {
          cancelAfter(job, streams, i);
          break;
        }
      }
      if (workBudgetAtStart >= 0) {
        this.workBudgetLeft = Math.max(0, workBudgetAtStart - mostWork);
      }
      job.thread.addSearchSteps(mergedSteps);
      job.board = best.board;
      return new ParallelResult(best, continuedFrom);
    } finally {
      executor.shutdownNow();
    }
  }

  private Attempt runStream(
      Stream stream,
      byte[] input,
      ClearanceBaseline inputBaseline,
      Attempt incumbent,
      RoutingJob job) {
    if (stream.thread.isStopRequested()) {
      return null;
    }
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    long cpuStart = bean.getCurrentThreadCpuTime();
    long start = System.nanoTime();
    try {
      if (stream.strategy == null) {
        return stream.runner.runContinuations(
            stream.job, incumbent, automaticContinuations(job.routerSettings));
      }
      return stream.runner.runAttempt(stream.job, input, stream.strategy, false, inputBaseline);
    } finally {
      stream.cpuSeconds = (bean.getCurrentThreadCpuTime() - cpuStart) / 1e9;
      stream.wallSeconds = (System.nanoTime() - start) / 1e9;
    }
  }

  private static Attempt await(RoutingJob job, Stream stream) {
    try {
      return stream.future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stream.thread.requestStop();
      return null;
    } catch (ExecutionException e) {
      job.logError(
          "Routing fallback attempt '" + stream.name + "' failed and was discarded.", e.getCause());
      return null;
    }
  }

  /** Stops the streams after {@code index}, waits for them, and logs the work they discarded. */
  private static void cancelAfter(RoutingJob job, List<Stream> streams, int index) {
    if (index + 1 >= streams.size()) {
      return;
    }
    for (int j = index + 1; j < streams.size(); j++) {
      streams.get(j).thread.requestStop();
    }
    List<String> names = new ArrayList<>();
    long steps = 0;
    for (int j = index + 1; j < streams.size(); j++) {
      Stream stream = streams.get(j);
      try {
        stream.future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (ExecutionException e) {
        // Discarded anyway.
      }
      names.add(stream.name);
      steps += stream.thread.searchSteps();
    }
    job.logInfo(
        "Routing fallback: '"
            + streams.get(index).name
            + "' finished the board, so "
            + String.join(", ", names)
            + " stopped early and "
            + (names.size() == 1 ? "was" : "were")
            + " discarded ("
            + steps
            + " search steps, not counted toward the job's step budget).");
  }

  private static void copyAttemptRecords(RoutingJob from, RoutingJob to) {
    if (from.resultPhaseMetrics == null
        || from.resultPhaseMetrics.fallback == null
        || to.resultPhaseMetrics == null) {
      return;
    }
    if (to.resultPhaseMetrics.fallback == null) {
      to.resultPhaseMetrics.fallback = new RoutingResultManifest.FallbackDetail();
    }
    to.resultPhaseMetrics.fallback.attempts.addAll(from.resultPhaseMetrics.fallback.attempts);
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
      if (this.listenerSource != null) {
        router.copyListenersFrom(this.listenerSource);
      }
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

  /** Threads for the fallback stage; 1 or less runs the attempts in order. */
  private static int fallbackThreads(RouterSettings settings, int streams) {
    Integer configured = settings.autorouter != null ? settings.autorouter.fallbackThreads : null;
    int threads = configured != null ? configured : 1;
    if (threads <= 0) {
      threads = Runtime.getRuntime().availableProcessors();
    }
    return Math.min(threads, streams);
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

  private record ParallelResult(Attempt best, RoutingBoard continuedFrom) {}

  /** One parallel attempt: the continuation of a residual board (no strategy) or an alternative. */
  private static final class Stream {
    final String name;
    final RoutingFallback.Strategy strategy;
    StreamThread thread;
    StreamJob job;
    RoutingFallbackRunner runner;
    Future<Attempt> future;
    volatile double cpuSeconds;
    volatile double wallSeconds;

    Stream(String name, RoutingFallback.Strategy strategy) {
      this.name = name;
      this.strategy = strategy;
    }
  }

  /**
   * Stop and step state of one parallel attempt. It is never started; the attempt runs on a pool
   * thread. A stop of the job reaches it, while its own stops (the pass loop ending, its step share
   * used up, a later attempt discarded) stay with it.
   */
  private static final class StreamThread extends StoppableThread {
    private final StoppableThread jobThread;
    private final boolean followsAutoRouterStop;

    StreamThread(StoppableThread jobThread, long stepShare) {
      this(jobThread, stepShare, true);
    }

    /**
     * A racing attempt does not follow the job's autorouter-only stop, which the default run's pass
     * loop requests when it ends; it follows a stop of the whole job.
     */
    StreamThread(StoppableThread jobThread, long stepShare, boolean followsAutoRouterStop) {
      this.jobThread = jobThread;
      this.followsAutoRouterStop = followsAutoRouterStop;
      setSearchStepBudget(stepShare);
    }

    @Override
    protected void threadAction() {}

    @Override
    public boolean isStopRequested() {
      return super.isStopRequested() || this.jobThread.isStopRequested();
    }

    @Override
    public boolean isStopAutoRouterRequested() {
      return super.isStopAutoRouterRequested()
          || (this.followsAutoRouterStop
              ? this.jobThread.isStopAutoRouterRequested()
              : this.jobThread.isStopRequested());
    }
  }

  /**
   * The job a parallel attempt routes in: its own board, settings, stop state and result metrics.
   * Log lines go to the parent job with the attempt's name. The first attempt reports to the
   * parent's live status; the others keep a private one.
   */
  private static final class StreamJob extends RoutingJob {
    private final RoutingJob parent;
    private final String prefix;
    private final RoutingStatus privateStatus;

    StreamJob(RoutingJob parent, String name, StreamThread thread, boolean reportsStatus) {
      this.parent = parent;
      this.prefix = "[" + name + "] ";
      this.privateStatus = reportsStatus ? null : new RoutingStatus();
      this.name = parent.name;
      this.shortName = parent.shortName;
      this.state = parent.state;
      this.routerSettings = parent.routerSettings;
      this.drcSettings = parent.drcSettings;
      this.resourceUsage = parent.resourceUsage;
      this.timeoutAt = parent.timeoutAt;
      this.board = parent.board;
      this.thread = thread;
    }

    @Override
    public synchronized RoutingStatus status() {
      return this.privateStatus != null ? this.privateStatus : this.parent.status();
    }

    @Override
    public void logInfo(String message) {
      this.parent.logInfo(this.prefix + message);
    }

    @Override
    public void logWarning(String message) {
      this.parent.logWarning(this.prefix + message);
    }

    @Override
    public void logError(String message, Throwable ex) {
      this.parent.logError(this.prefix + message, ex);
    }

    @Override
    public void logDebug(String message) {
      this.parent.logDebug(this.prefix + message);
    }
  }
}
