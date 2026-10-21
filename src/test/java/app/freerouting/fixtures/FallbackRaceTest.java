package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * With fallback_race the alternative attempts start beside the default run; they are merged in the
 * sequential order once it is done, so the chosen board does not depend on the thread count or on
 * which attempt finishes first. This board ends the default run with open connections.
 */
class FallbackRaceTest extends RoutingFixtureTest {

  private RoutingJob route(int threads) {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setJobTimeoutString("00:05:00");
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies("short-escape,seed-1,seed-2");
    testSettingsSource.setFallbackWorkRatio(0.3);
    RoutingJob job = getRoutingJob("Issue313-FastTest.dsn", testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 4;
    job.routerSettings.autorouter.fallbackThreads = threads;
    job.routerSettings.autorouter.fallbackRace = true;
    return runRoutingJob(job);
  }

  @Test
  void sameBoardWithAnyThreadCount() {
    RoutingJob two = route(2);
    RoutingJob three = route(3);
    assertNotNull(two.board, "the two-thread run has a board");
    assertNotNull(three.board, "the three-thread run has a board");
    List<String> twoAttempts = attempts(two);
    assertTrue(
        twoAttempts.stream().anyMatch(a -> a.startsWith("seed-1/")),
        "the racing attempts were merged: " + twoAttempts);
    assertEquals(twoAttempts, attempts(three), "the same attempts in the same order");
    assertEquals(
        two.resultPhaseMetrics.fallback.chosen,
        three.resultPhaseMetrics.fallback.chosen,
        "the same attempt chosen");
    assertEquals(
        two.board.getHash(), three.board.getHash(), "the same routes with 2 and 3 threads");
  }

  private static List<String> attempts(RoutingJob job) {
    RoutingResultManifest.FallbackDetail fallback = job.resultPhaseMetrics.fallback;
    assertNotNull(fallback, "the default run leaves open connections, so the fallback runs");
    return fallback.attempts.stream()
        .map(a -> a.strategy + "/" + a.stopReason + "/" + a.unrouted)
        .toList();
  }
}
