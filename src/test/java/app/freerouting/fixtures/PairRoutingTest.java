package app.freerouting.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.autoroute.pipeline.PairRouting;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.management.BoardLoader;
import app.freerouting.settings.DesignRulesCheckerSettings;
import app.freerouting.settings.sources.TestingSettings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A differential pair declared with {@code (pair (nets P N (gap g)))} is routed as two coupled
 * traces: the members run at a constant gap, their breakouts at both ends match, and nothing
 * violates a clearance.
 */
class PairRoutingTest extends RoutingFixtureTest {

  private static List<PolylineTrace> tracesOf(RoutingJob job, int net) {
    List<PolylineTrace> result = new ArrayList<>();
    for (Item item : job.board.getItems()) {
      if (item instanceof PolylineTrace trace && trace.containsNet(net)) {
        result.add(trace);
      }
    }
    return result;
  }

  private static double distanceToTrace(FloatPoint point, PolylineTrace trace) {
    double best = Double.POSITIVE_INFINITY;
    int count = trace.polyline().cornerCount();
    for (int i = 0; i + 1 < count; i++) {
      FloatPoint a = trace.polyline().cornerApprox(i);
      FloatPoint b = trace.polyline().cornerApprox(i + 1);
      double dx = b.x - a.x;
      double dy = b.y - a.y;
      double t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / (dx * dx + dy * dy);
      t = Math.max(0, Math.min(1, t));
      best = Math.min(best, point.distance(new FloatPoint(a.x + t * dx, a.y + t * dy)));
    }
    return best;
  }

  @Test
  void padsOnOppositeSidesAreJoinedThroughAViaPair() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(2);
    settings.setJobTimeoutString("00:01:00");
    RoutingJob job = getRoutingJob("PairViaBasic.dsn", settings);
    runRoutingJob(job);

    int netP = job.board.rules.nets.get("D+", 1).netNumber;
    int netN = job.board.rules.nets.get("D-", 1).netNumber;
    long viasP =
        job.board.getItems().stream().filter(i -> i instanceof Via && i.containsNet(netP)).count();
    long viasN =
        job.board.getItems().stream().filter(i -> i instanceof Via && i.containsNet(netN)).count();
    assertEquals(1, viasP, "the P member changes layer once");
    assertEquals(1, viasN, "the N member changes layer once");
    DesignRulesChecker drc = new DesignRulesChecker(job.board, new DesignRulesCheckerSettings());
    drc.calculateAllIncompletes();
    assertEquals(0, drc.getIncompleteCount(), "both nets must be complete");
    assertTrue(drc.getAllClearanceViolations().isEmpty(), "the pair must not violate a clearance");
  }

  @Test
  void aPairThatCannotBeCoupledLeavesNothingBehind() {
    RoutingJob job = getRoutingJob("PairBlocked.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    // A wall of foreign copper across the whole board: no corridor for the pair.
    Set<Integer> before = new HashSet<>();
    for (Item item : job.board.getItems()) {
      before.add(item.getId());
    }
    List<Integer> placed = new ArrayList<>();
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, placed);
    assertTrue(lines != null && lines.stream().anyMatch(l -> l.contains("left to the router")));
    assertTrue(placed.isEmpty(), "no copper may be left from a pair that failed");
    for (Item item : job.board.getItems()) {
      assertTrue(before.contains(item.getId()), "an item was left behind: " + item);
    }
  }

  @Test
  void aDeclaredPairRoutesAsTwoCoupledTraces() {
    TestingSettings settings = new TestingSettings();
    settings.setMaxPasses(2);
    settings.setJobTimeoutString("00:01:00");
    RoutingJob job = getRoutingJob("PairBasic.dsn", settings);
    runRoutingJob(job);

    int netP = job.board.rules.nets.get("D+", 1).netNumber;
    int netN = job.board.rules.nets.get("D-", 1).netNumber;
    List<PolylineTrace> positive = tracesOf(job, netP);
    List<PolylineTrace> negative = tracesOf(job, netN);
    assertFalse(positive.isEmpty(), "the P member must be routed");
    assertFalse(negative.isEmpty(), "the N member must be routed");
    DesignRulesChecker drc = new DesignRulesChecker(job.board, new DesignRulesCheckerSettings());
    drc.calculateAllIncompletes();
    assertEquals(0, drc.getIncompleteCount(), "both nets must be complete");
    assertTrue(
        new DesignRulesChecker(job.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations()
            .isEmpty(),
        "the pair must not violate a clearance");

    // Board units are 0.1 um: width 150 um, gap 200 um, so the members run 350 um apart centre to
    // centre. Walk along P and check how far N is: never closer than that, and at that distance
    // over most of the length (the breakouts at the pads are the uncoupled part).
    double spacing = 3500;
    double step = 500;
    int coupled = 0;
    int samples = 0;
    for (PolylineTrace p : positive) {
      for (int i = 0; i + 1 < p.polyline().cornerCount(); i++) {
        FloatPoint a = p.polyline().cornerApprox(i);
        FloatPoint b = p.polyline().cornerApprox(i + 1);
        int parts = Math.max(1, (int) (a.distance(b) / step));
        for (int k = 0; k <= parts; k++) {
          double t = (double) k / parts;
          FloatPoint sample = new FloatPoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y));
          double nearest = Double.POSITIVE_INFINITY;
          for (PolylineTrace n : negative) {
            nearest = Math.min(nearest, distanceToTrace(sample, n));
          }
          assertTrue(nearest >= spacing - 25, "the members must not come closer than the gap");
          samples++;
          if (Math.abs(nearest - spacing) < 25) {
            coupled++;
          }
        }
      }
    }
    assertTrue(coupled * 10 >= samples * 8, coupled + " of " + samples + " samples are coupled");
    for (Item item : job.board.getItems()) {
      if (item instanceof Pin pin && (pin.containsNet(netP) || pin.containsNet(netN))) {
        assertFalse(pin.getNormalContacts().isEmpty(), "pin " + pin.name() + " must be connected");
      }
    }
  }
}
