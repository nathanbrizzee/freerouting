package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.Freerouting;
import app.freerouting.board.model.items.Trace;
import app.freerouting.core.RoutingJob;
import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 0.6 mm net enters the middle pad of a 0.5 mm-pitch part with 0.15 mm pads, so it must neck
 * down. With {@code router.min_trace_width_um} at 0.2 mm the neck must not go below it; without the
 * floor it tapers to the pad width.
 */
class NeckFloorTest extends RoutingFixtureTest {

  @Test
  void neckNeverGoesBelowTheMinimumTraceWidth() {
    System.setProperty("freerouting.logging.console.level", "INFO");
    FRLogger.granularTraceEnabled = false;
    Freerouting.globalSettings.debugSettings.enableDetailedLogging = false;

    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(5);
    testSettingsSource.setJobTimeoutString("00:01:00");
    testSettingsSource.setMinTraceWidthUm(200);

    RoutingJob job = getRoutingJob("NeckFloor.dsn", testSettingsSource);
    runRoutingJob(job);

    // 0.2 mm at resolution 10 is a half-width of 1000 board units.
    assertEquals(1000, job.board.rules.getMinAllowedTraceHalfWidth(), "floor from the setting");
    assertEquals(0, getBoardStatistics(job).connections.incompleteCount, "unrouted connections");
    List<Trace> traces =
        job.board.getItems().stream()
            .filter(item -> item instanceof Trace)
            .map(item -> (Trace) item)
            .toList();
    assertFalse(traces.isEmpty(), "the net is routed with traces");
    assertTrue(
        traces.stream().allMatch(trace -> trace.getHalfWidth() >= 1000),
        "no trace narrower than 0.2 mm: "
            + traces.stream().map(Trace::getHalfWidth).sorted().toList());
  }
}
