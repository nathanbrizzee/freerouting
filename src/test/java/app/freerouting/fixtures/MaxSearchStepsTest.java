package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.core.RoutingJob;
import app.freerouting.core.RoutingJobState;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/** A job capped by router.max_search_steps stops as timed out, at the same board every run. */
class MaxSearchStepsTest extends RoutingFixtureTest {

  private RoutingJob route() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setOptimizerEnabled(false);
    RoutingJob job = getRoutingJob("Issue313-FastTest.dsn", testSettingsSource);
    job.routerSettings.maxSearchSteps = 50_000L;
    return runRoutingJob(job);
  }

  @Test
  void stopsAtTheSameBoardEveryRun() {
    RoutingJob first = route();
    RoutingJob second = route();
    assertNotNull(first.board, "the first run has a board");
    assertEquals(RoutingJobState.TIMED_OUT, first.state, "the cap ends the job as timed out");
    assertEquals(RoutingJobState.TIMED_OUT, second.state, "the cap ends the job as timed out");
    assertEquals(first.board.getHash(), second.board.getHash(), "the same board on both runs");
  }
}
