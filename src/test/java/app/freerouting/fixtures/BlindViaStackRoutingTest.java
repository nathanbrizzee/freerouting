package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.freerouting.board.model.items.Via;
import app.freerouting.core.RoutingJob;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/**
 * A pad on the top layer must reach a pad on the first inner layer of a 4-layer board whose two
 * lower layers are keepouts everywhere. Only the blind via from the via rule fits; the through via
 * never does. Until 2026-10-21 the layer change was rejected wherever the through via would not
 * fit, so this board stayed open.
 */
class BlindViaStackRoutingTest extends RoutingFixtureTest {

  @Test
  void routesThroughTheBlindViaWhereTheStackIsBlocked() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(3);
    testSettingsSource.setOptimizerEnabled(false);
    testSettingsSource.setJobTimeoutString("00:01:00");
    RoutingJob job = runRoutingJob(getRoutingJob("BlindViaStack.dsn", testSettingsSource));

    assertRoutingResult(job, "BlindViaStack.dsn")
        .exactIncompleteConnections(0)
        .exactClearanceViolations(0)
        .check();
    assertEquals(1, job.board.getVias().size(), "one via");
    Via via = job.board.getVias().iterator().next();
    assertEquals(0, via.getPadstack().fromLayer(), "from the top layer");
    assertEquals(1, via.getPadstack().toLayer(), "to the first inner layer only");
  }
}
