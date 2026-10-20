package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.settings.RouterSettings;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/** The planned fanout escapes pins to vias without clearance violations, the same way each run. */
class PlannedFanoutTest extends RoutingFixtureTest {

  @Test
  void escapesPinsCleanlyAndDeterministically() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setRouterEnabled(false);
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setJobTimeoutString("00:01:00");
    RoutingJob job = runRoutingJob(getRoutingJob("Issue313-FastTest.dsn", testSettingsSource));
    assertNotNull(job.board, "the board loads");
    RouterSettings settings = job.routerSettings.clone();
    settings.fanout.strategy = "planned";
    int violationsBefore =
        new DesignRulesChecker(job.board, null).getAllClearanceViolations().size();

    RoutingBoard first = job.board.deepCopy();
    PlannedFanout.Summary summary = PlannedFanout.run(first, settings, null);
    RoutingBoard second = job.board.deepCopy();
    PlannedFanout.run(second, settings, null);

    assertTrue(summary.escaped() > 0, "some pins escape: " + summary);
    assertEquals(
        violationsBefore,
        new DesignRulesChecker(first, null).getAllClearanceViolations().size(),
        "no new clearance violations");
    assertEquals(job.board.getViaCount() + summary.escaped(), first.getViaCount(), "one via each");
    assertEquals(first.getHash(), second.getHash(), "the same escapes on every run");
  }
}
