package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parallel fallback attempts are merged in the sequential order, and each attempt's budget share
 * depends on the number of attempts, not on the number of threads, so two and five threads choose
 * the same board. This board ends the default run with open connections, so the fallback runs.
 */
class ParallelFallbackTest extends RoutingFixtureTest {

  private RoutingJob route(int threads) {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setJobTimeoutString("00:05:00");
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies(DefaultSettings.DEFAULT_FALLBACK_STRATEGIES);
    testSettingsSource.setFallbackWorkRatio(0.3);
    RoutingJob job = getRoutingJob("Issue313-FastTest.dsn", testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 4;
    job.routerSettings.autorouter.fallbackThreads = threads;
    return runRoutingJob(job);
  }

  @Test
  void sameBoardWithAnyThreadCount() {
    RoutingJob two = route(2);
    RoutingJob five = route(5);
    assertNotNull(two.board, "the two-thread run has a board");
    assertNotNull(five.board, "the five-thread run has a board");
    List<String> twoAttempts = attempts(two);
    assertTrue(twoAttempts.size() > 2, "the fallback ran several attempts: " + twoAttempts);
    assertEquals(twoAttempts, attempts(five), "the same attempts in the same order");
    assertEquals(
        two.resultPhaseMetrics.fallback.chosen,
        five.resultPhaseMetrics.fallback.chosen,
        "the same attempt chosen");
    assertEquals(two.board.getHash(), five.board.getHash(), "the same routes with 2 and 5 threads");
  }

  private static List<String> attempts(RoutingJob job) {
    RoutingResultManifest.FallbackDetail fallback = job.resultPhaseMetrics.fallback;
    assertNotNull(fallback, "the default run leaves open connections, so the fallback runs");
    return fallback.attempts.stream()
        .map(a -> a.strategy + "/" + a.stopReason + "/" + a.unrouted)
        .toList();
  }
}
