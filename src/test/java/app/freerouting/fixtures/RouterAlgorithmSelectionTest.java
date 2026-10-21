package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.freerouting.autoroute.pipeline.RouterAlgorithms;
import app.freerouting.core.RoutingJob;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/**
 * {@code router.autorouter.algorithm} selects a registered router. Until the global planner lands,
 * the planned router routes exactly like the original; an unknown name falls back to the original.
 */
class RouterAlgorithmSelectionTest extends RoutingFixtureTest {

  private RoutingJob route(String algorithm) {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setFallbackStrategies("none");
    RoutingJob job = getRoutingJob("Issue313-FastTest.dsn", testSettingsSource);
    job.routerSettings.autorouter.maxPasses = 2;
    job.routerSettings.autorouter.algorithm = algorithm;
    return runRoutingJob(job);
  }

  @Test
  void plannedRoutesLikeTheOriginalForNow() {
    RoutingJob original = route(RouterAlgorithms.ORIGINAL);
    RoutingJob planned = route(RouterAlgorithms.PLANNED);
    assertNotNull(original.board, "the original run has a board");
    assertNotNull(planned.board, "the planned run has a board");
    assertEquals(RouterAlgorithms.PLANNED, planned.routerSettings.autorouter.algorithm);
    assertEquals(original.board.getHash(), planned.board.getHash(), "the same routes");
  }

  @Test
  void unknownAlgorithmFallsBackToTheOriginal() {
    RoutingJob job = route("no-such-router");
    assertEquals(RouterAlgorithms.ORIGINAL, job.routerSettings.autorouter.algorithm);
  }
}
