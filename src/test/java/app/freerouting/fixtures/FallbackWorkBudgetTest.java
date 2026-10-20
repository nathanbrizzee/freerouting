package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.Freerouting;
import app.freerouting.core.RoutingJob;
import app.freerouting.core.results.RoutingResultManifest;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fallback stage stops when it has used its work budget, and the result manifest lists what it
 * tried. This board ends the default run with open connections, so the fallback runs; a budget of
 * 1% of the default run's connection attempts ends the first attempt early and starts no other.
 */
class FallbackWorkBudgetTest extends RoutingFixtureTest {

  @Test
  void fallbackStopsWhenItsWorkBudgetIsUsedUp() {
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;

    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setJobTimeoutString("00:02:00");
    testSettingsSource.setFallbackStrategies(DefaultSettings.DEFAULT_FALLBACK_STRATEGIES);
    testSettingsSource.setFallbackWorkRatio(0.01);

    RoutingJob job = getRoutingJob("Issue313-FastTest.dsn", testSettingsSource);
    runRoutingJob(job);

    RoutingResultManifest.FallbackDetail fallback = job.resultPhaseMetrics.fallback;
    assertNotNull(fallback, "the default run leaves open connections, so the fallback runs");
    List<RoutingResultManifest.FallbackAttempt> attempts = fallback.attempts;
    assertEquals("default", attempts.getFirst().strategy, "the default run is listed first");
    assertEquals(2, attempts.size(), "one attempt, stopped by the budget: " + names(attempts));
    assertEquals("WORK_BUDGET", attempts.getLast().stopReason);
    assertNotNull(fallback.chosen);
  }

  private static List<String> names(List<RoutingResultManifest.FallbackAttempt> attempts) {
    return attempts.stream().map(a -> a.strategy + "/" + a.stopReason).toList();
  }
}
