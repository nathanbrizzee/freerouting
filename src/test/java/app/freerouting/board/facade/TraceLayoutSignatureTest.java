package app.freerouting.board.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.geometry.planar.IntVector;
import app.freerouting.settings.sources.TestingSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The layout signature that stops a normalization cycle depends only on geometry: not on the order
 * of the traces or on which objects hold them, and it changes when a trace moves.
 */
class TraceLayoutSignatureTest extends RoutingFixtureTest {

  @Test
  void signatureDependsOnlyOnGeometry() {
    TestingSettings testSettingsSource = new TestingSettings();
    testSettingsSource.setMaxPasses(5);
    testSettingsSource.setJobTimeoutString("00:01:00");
    RoutingJob job = runRoutingJob(getRoutingJob("NeckFloor.dsn", testSettingsSource));
    List<PolylineTrace> traces = traces(job.board);
    assertTrue(traces.size() > 1, "the routed fixture has several traces: " + traces.size());
    long signature = BasicBoard.traceLayoutSignature(traces);

    List<PolylineTrace> reversed = new ArrayList<>(traces);
    Collections.reverse(reversed);
    assertEquals(signature, BasicBoard.traceLayoutSignature(reversed), "order of the traces");

    RoutingBoard copy = job.board.deepCopy();
    List<PolylineTrace> copied = traces(copy);
    assertEquals(signature, BasicBoard.traceLayoutSignature(copied), "copied trace objects");

    copied.getFirst().translateBy(new IntVector(10, 0));
    assertNotEquals(signature, BasicBoard.traceLayoutSignature(copied), "a moved trace");
    assertNotEquals(
        signature, BasicBoard.traceLayoutSignature(traces.subList(1, traces.size())), "one fewer");
  }

  private static List<PolylineTrace> traces(BasicBoard board) {
    return board.getItems().stream()
        .filter(item -> item instanceof PolylineTrace)
        .map(item -> (PolylineTrace) item)
        .toList();
  }
}
