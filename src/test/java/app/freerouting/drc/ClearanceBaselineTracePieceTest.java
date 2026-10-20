package app.freerouting.drc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.settings.sources.TestingSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A T-join onto pre-existing copper splits the trace. The pieces keep the trace's exemption when
 * the violation is otherwise unchanged; other copper and changed measurements stay new.
 */
class ClearanceBaselineTracePieceTest extends RoutingFixtureTest {

  @Test
  void pieceOfASplitTraceKeepsItsExemption() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(1);
    testSettingsSource.setJobTimeoutString("00:01:00");
    RoutingJob job = runRoutingJob(getRoutingJob("NeckFloor.dsn", testSettingsSource));
    List<PolylineTrace> traces =
        job.board.getItems().stream()
            .filter(item -> item instanceof PolylineTrace)
            .map(item -> (PolylineTrace) item)
            .toList();
    assertTrue(traces.size() > 1, "the routed fixture has several traces: " + traces.size());
    PolylineTrace trace = traces.getFirst();
    PolylineTrace other = traces.get(1);
    Item pin = job.board.getPins().iterator().next();
    int layer = trace.getLayer();

    ClearanceBaseline baseline =
        new ClearanceBaseline(List.of(new ClearanceViolation(trace, pin, null, layer, 100, 50)));

    Polyline polyline = trace.polyline();
    FloatPoint middle = polyline.cornerApprox(0).middlePoint(polyline.cornerApprox(1));
    Trace[] pieces = trace.split(middle.round());
    assertNotNull(pieces, "the trace splits at the middle of its first segment");
    Trace piece = pieces[0];

    assertEquals(
        0,
        baseline.countNewOrChanged(
            List.of(new ClearanceViolation(piece, pin, null, layer, 100, 50))),
        "a piece of the baseline trace");
    assertEquals(
        1,
        baseline.countNewOrChanged(
            List.of(new ClearanceViolation(piece, pin, null, layer, 100, 40))),
        "a changed clearance");
    assertEquals(
        1,
        baseline.countNewOrChanged(
            List.of(new ClearanceViolation(other, pin, null, layer, 100, 50))),
        "another trace");
  }
}
