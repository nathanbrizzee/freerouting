package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A DSN exporter that rounds to a micrometre can leave a fixed wire half a micrometre short of the
 * pin centre it belongs to. A trace connects only at the pin centre, so reading the wire moves its
 * end onto the centre; otherwise the router closes the gap with a sub-micrometre stub.
 */
class PinCentreSnapTest extends RoutingFixtureTest {

  @Test
  void wireEndRoundedOffTheCentreIsMovedOntoIt() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(1);
    testSettingsSource.setJobTimeoutString("00:01:00");

    RoutingJob job = getRoutingJob("PinCentreSnap.dsn", testSettingsSource);
    runRoutingJob(job);

    List<PolylineTrace> fixed =
        job.board.getItems().stream()
            .filter(item -> item instanceof PolylineTrace && item.isUserFixed())
            .map(item -> (PolylineTrace) item)
            .toList();
    assertEquals(1, fixed.size(), "the fixed wire: " + fixed);
    // The DSN wire starts at 5800.5 um; the pin centre is 5800 um, 10 board units per um.
    assertTrue(
        fixed.get(0).firstCorner().equals(new IntPoint(58000, 100000))
            || fixed.get(0).lastCorner().equals(new IntPoint(58000, 100000)),
        "the wire must end on the pin centre: " + fixed.get(0));
  }
}
