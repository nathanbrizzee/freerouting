package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.fixtures.RoutingFixtureTest;
import app.freerouting.management.BoardLoader;
import app.freerouting.settings.sources.TestingSettings;
import org.junit.jupiter.api.Test;

/**
 * The reachability check after the first pass: a pin walled in by keepouts on every layer is one
 * connection that can never be made, and the passes stop searching for it; a gap a trace fits
 * through is not mistaken for a wall.
 */
class UnreachableGroupsTest extends RoutingFixtureTest {

  private static Pin pin(RoutingBoard board, String component, String name) {
    for (Pin pin : board.getPins()) {
      if (pin.componentName().equals(component) && pin.name().equals(name)) {
        return pin;
      }
    }
    throw new AssertionError("no pin " + component + "-" + name);
  }

  @Test
  void aPinWalledInByKeepoutsCanNeverBeConnected() {
    RoutingJob job = getRoutingJob("UnreachableWalledPin.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    UnreachableGroups check =
        UnreachableGroups.check(
            job.board, job.routerSettings, UnreachableGroups.DEFAULT_CELL_BUDGET);
    assertEquals(1, check.unreachableConnectionCount());
    assertEquals(1, check.splitNetCount());
    String line = check.describe(job.board);
    assertTrue(line.startsWith("Unreachable connections: 1 connection(s) on 1 net(s)"), line);
    assertTrue(line.contains("(A)"), line);

    Pin outside = pin(job.board, "J1", "1");
    Pin inside = pin(job.board, "U1", "1");
    int netA = outside.getNetNumber(0);
    assertFalse(check.canStillConnect(job.board, outside, netA));
    assertFalse(check.canStillConnect(job.board, inside, netA));
    Pin control = pin(job.board, "J2", "1");
    assertTrue(check.canStillConnect(job.board, control, control.getNetNumber(0)));
  }

  /**
   * The ring has a 500 um gap; a 150 um trace with 150 um clearance on both sides needs 450 um. The
   * check must leave it to the router, which routes it.
   */
  @Test
  void aGapATraceFitsThroughIsNotAWall() {
    RoutingJob job = getRoutingJob("UnreachableNarrowGap.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    UnreachableGroups check =
        UnreachableGroups.check(
            job.board, job.routerSettings, UnreachableGroups.DEFAULT_CELL_BUDGET);
    assertEquals(0, check.unreachableConnectionCount(), check.describe(job.board));
    assertTrue(check.isEmpty());

    RoutingJob routed = getRoutingJob("UnreachableNarrowGap.dsn", new TestingSettings());
    runRoutingJob(routed);
    assertEquals(
        0, new DesignRulesChecker(routed.board, null).getAllAirlines().length, "both nets route");
  }

  /**
   * A pin on the top and one on the bottom joined only through a via that passes a ground plane:
   * vias are drilled through a plane, so the plane must not wall the via off.
   */
  @Test
  void aViaThroughAPlaneIsAWayOut() {
    RoutingJob job = getRoutingJob("UnreachablePlaneVia.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    UnreachableGroups check =
        UnreachableGroups.check(
            job.board, job.routerSettings, UnreachableGroups.DEFAULT_CELL_BUDGET);
    assertEquals(0, check.unreachableConnectionCount(), check.describe(job.board));
  }

  @Test
  void theRunSkipsTheWalledPinAndRoutesTheRest() {
    TestingSettings settings = new TestingSettings();
    RoutingJob job = getRoutingJob("UnreachableWalledPin.dsn", settings);
    assertTrue(Boolean.TRUE.equals(job.routerSettings.autorouter.skipUnreachable));
    runRoutingJob(job);
    Pin control = pin(job.board, "J2", "1");
    assertTrue(
        control.getConnectedSet(control.getNetNumber(0)).contains(pin(job.board, "U2", "1")),
        "net B routes");
    assertEquals(
        1, new DesignRulesChecker(job.board, null).getAllAirlines().length, "A stays open");
  }
}
