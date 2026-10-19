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
 * A pin beside the middle of a fixed track must be able to join it. Traces connect only at their
 * ends, so the join splits the track; fixed tracks used to refuse the split, which left the
 * connection open on every pass. The pieces must stay fixed and keep the track's copper.
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
    assertTrue(fixed.size() >= 2, "the fixed track should be split into fixed pieces: " + fixed);
    double fixedLength = fixed.stream().mapToDouble(PolylineTrace::getLength).sum();
    // 5800 to 44200 um at resolution 10 is 384000 board units.
    assertEquals(384000.0, fixedLength, 1.0, "the fixed copper must keep its length");
  }
}
