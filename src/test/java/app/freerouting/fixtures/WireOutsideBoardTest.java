package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A fixed wire with corners outside the board box is kept. It used to be dropped at load, so the
 * router routed its connection again, and a KiCad session import, which replaces the board's tracks
 * with the session's, deleted the designer's wire.
 */
class WireOutsideBoardTest extends RoutingFixtureTest {

  @Test
  void fixedWireOutsideTheBoardIsKept() {
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;

    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(3);
    testSettingsSource.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("WireOutsideBoard.dsn", testSettingsSource);
    runRoutingJob(job);

    assertEquals(0, getBoardStatistics(job).connections.incompleteCount, "unrouted connections");
    List<PolylineTrace> traces =
        job.board.getItems().stream()
            .filter(item -> item instanceof PolylineTrace)
            .map(item -> (PolylineTrace) item)
            .toList();
    assertTrue(
        traces.stream().allMatch(PolylineTrace::isUserFixed),
        "the fixed wire connects the net, so no trace is added: " + traces);
    // The wire runs 12 mm down, 38.4 mm across and 12 mm up: 624000 board units at resolution 10.
    double length = traces.stream().mapToDouble(PolylineTrace::getLength).sum();
    assertEquals(624000.0, length, 1.0, "the whole fixed wire must be on the board");
  }
}
