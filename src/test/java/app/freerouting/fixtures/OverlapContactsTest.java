package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.model.items.Trace;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.ClearanceViolation;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.TestingSettings;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * Same-net copper that touches is connected. Net A is two overlapping pads of different parts and
 * needs no route. Net B has fixed vias in SMD pads; the via padstack says attach off, which only
 * stops the router from placing vias in pads, so the designer's fixed vias are not clearance
 * violations.
 */
class OverlapContactsTest extends RoutingFixtureTest {

  @Test
  void touchingSameNetCopperIsConnectedAndNotAViolation() {
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;

    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(3);
    testSettingsSource.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("OverlapContacts.dsn", testSettingsSource);
    runRoutingJob(job);

    assertEquals(0, getBoardStatistics(job).connections.incompleteCount, "unrouted connections");
    int netA = job.board.rules.nets.get("A", 1).netNumber;
    assertTrue(
        job.board.getItems().stream()
            .noneMatch(item -> item instanceof Trace && item.containsNet(netA)),
        "overlapping pads of net A need no trace");
    Collection<ClearanceViolation> violations =
        new DesignRulesChecker(job.board, null).getAllClearanceViolations();
    assertEquals(0, violations.size(), "clearance violations: " + violations);
  }
}
