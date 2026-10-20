package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.core.RoutingJob;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/** With step budgets in place of time limits, the same board routes the same way twice. */
class SearchStepBudgetTest extends RoutingFixtureTest {

  private RoutingJob route() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies("none");
    RoutingJob job = getRoutingJob("Issue313-FastTest.dsn", testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 3;
    // Small budgets, so that some searches stop at the limit.
    job.routerSettings.fanout.maxSearchStepsPerPin = 200L;
    job.routerSettings.autorouter.maxSearchStepsPerConnection = 2000L;
    return runRoutingJob(job);
  }

  @Test
  void sameBoardOnEveryRun() {
    RoutingJob first = route();
    RoutingJob second = route();
    assertNotNull(first.board, "the first run has a board");
    assertNotNull(second.board, "the second run has a board");
    assertEquals(first.board.getHash(), second.board.getHash(), "the same routes on both runs");
  }
}
