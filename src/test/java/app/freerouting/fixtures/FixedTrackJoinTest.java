package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.freerouting.Freerouting;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A pin beside the middle of a fixed track must be able to join it. A trace ending on the
 * centerline of a fixed track counts as connected to it, so the track stays whole and the
 * connection closes.
 */
class FixedTrackJoinTest extends RoutingFixtureTest {

  @Test
  void pinJoinsTheMiddleOfAFixedTrack() {
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;

    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(5);
    testSettingsSource.setJobTimeoutString("00:02:00");

    RoutingJob job = getRoutingJob("FixedTrackJoin.dsn", testSettingsSource);
    runRoutingJob(job);

    assertEquals(0, getBoardStatistics(job).connections.incompleteCount, "unrouted connections");
    List<PolylineTrace> fixed =
        job.board.getItems().stream()
            .filter(item -> item instanceof PolylineTrace && item.isUserFixed())
            .map(item -> (PolylineTrace) item)
            .toList();
    assertEquals(1, fixed.size(), "the fixed track must stay whole: " + fixed);
    double fixedLength = fixed.stream().mapToDouble(PolylineTrace::getLength).sum();
    // 5800 to 44200 um at resolution 10 is 384000 board units.
    assertEquals(384000.0, fixedLength, 1.0, "the fixed copper must keep its length");
  }
}
