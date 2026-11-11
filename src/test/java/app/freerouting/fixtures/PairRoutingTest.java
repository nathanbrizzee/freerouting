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
  void aCoupledRouteLongerThanTheDetourLimitIsLeftToTheOrdinaryRouter() {
    // PairViaBasic needs a via pair, and each via pair counts as extra length: with the limit at
    // the lowest value (1 = the direct distance) the coupled route is too long, with the default
    // it is taken.
    RoutingJob limited = getRoutingJob("PairViaBasic.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(limited);
    Set<Integer> before = new HashSet<>();
    for (Item item : limited.board.getItems()) {
      before.add(item.getId());
    }
    limited.routerSettings.autorouter.pairMaxDetour = 1.0;
    List<Integer> placed = new ArrayList<>();
    List<String> lines = PairRouting.apply(limited.routerSettings, limited.board, null, placed);
    assertTrue(
        lines != null && lines.stream().anyMatch(l -> l.contains("pair_max_detour")),
        "the line must say why the pair was left to the router: " + lines);
    assertTrue(placed.isEmpty(), "no copper may be left from a pair that was refused");
    for (Item item : limited.board.getItems()) {
      assertTrue(before.contains(item.getId()), "an item was left behind: " + item);
    }

    RoutingJob normal = getRoutingJob("PairViaBasic.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(normal);
    List<String> routed =
        PairRouting.apply(normal.routerSettings, normal.board, null, new ArrayList<>());
    assertTrue(
        routed != null && routed.stream().anyMatch(l -> l.contains("): routed on layer")),
        "the default limit must keep the coupled route: " + routed);
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
  void aPairThroughASeriesPartIsRoutedOnEveryHop() {
    RoutingJob job = getRoutingJob("PairSeries.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    List<Integer> placed = new ArrayList<>();
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, placed);
    assertTrue(lines != null, "the pair must be found");
    long routed = lines.stream().filter(l -> l.contains(": routed on layer")).count();
    assertEquals(2, routed, "both hops must be coupled pair connections: " + lines);
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("J1-1") && l.contains("D1-1")),
        "the hop from the connector to the diode is missing: " + lines);
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("D1-1") && l.contains("U1-")),
        "the hop to the part with two pins per member is missing: " + lines);
  }

  @Test
  void aPairKeepsToTheLayersItsNetClassAllows() {
    // The net class allows B.Cu only; the pads are on F.Cu, so the pair needs a via pair at each
    // end and must not be tried on F.Cu.
    RoutingJob job = getRoutingJob("PairLayerRestricted.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    List<Integer> placed = new ArrayList<>();
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, placed);
    assertTrue(lines != null, "the pair must be found");
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("routed on layer 1 with 2 via pair(s)")),
        "the pair must run on B.Cu: " + lines);
    assertFalse(
        lines.stream().anyMatch(l -> l.contains("layer 0:")), "F.Cu must not be tried: " + lines);
  }

  private static long count(RoutingJob job, Class<? extends Item> type, String netName) {
    int net = job.board.rules.nets.get(netName, 1).netNumber;
    return job.board.getItems().stream()
        .filter(i -> type.isInstance(i) && i.containsNet(net))
        .count();
  }

  @Test
  void existingFanoutOnThePairPinsIsReplacedByThePair() {
    // The D- fanout runs in front of the D+ pad to a via; the pair lifts both pins' fanout and
    // leaves the pads itself.
    RoutingJob job = getRoutingJob("PairFanout.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    assertEquals(1, count(job, Via.class, "D-"), "the fixture has a D- fanout via");
    List<Integer> placed = new ArrayList<>();
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, placed);
    assertTrue(
        lines != null && lines.stream().anyMatch(l -> l.contains("replacing 4 fanout item(s)")),
        "the pair must route and replace the fanout: " + lines);
    assertEquals(0, count(job, Via.class, "D-"), "the D- fanout via must be gone");
    assertEquals(0, count(job, Via.class, "D+"), "the D+ fanout via must be gone");
  }

  @Test
  void fanoutIsKeptWhenAskedAndPutBackWhenThePairFails() {
    RoutingJob keep = getRoutingJob("PairFanout.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(keep);
    keep.routerSettings.autorouter.pairFanout = "keep";
    List<Integer> fanout = new ArrayList<>();
    for (Item item : keep.board.getItems()) {
      if (item instanceof PolylineTrace || item instanceof Via) {
        fanout.add(item.getId());
      }
    }
    assertEquals(4, fanout.size(), "the fixture has two fanout traces and two vias");
    PairRouting.apply(keep.routerSettings, keep.board, null, new ArrayList<>());
    for (int id : fanout) {
      assertTrue(keep.board.getItem(id) != null, "pair_fanout=keep must not lift item " + id);
    }

    RoutingJob blocked = getRoutingJob("PairFanoutBlocked.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(blocked);
    long tracesP = count(blocked, PolylineTrace.class, "D+");
    long tracesN = count(blocked, PolylineTrace.class, "D-");
    List<Integer> placed = new ArrayList<>();
    List<String> lines = PairRouting.apply(blocked.routerSettings, blocked.board, null, placed);
    assertTrue(lines != null && lines.stream().anyMatch(l -> l.contains("left to the router")));
    assertTrue(placed.isEmpty(), "no pair copper may be left from a pair that failed");
    assertEquals(1, count(blocked, Via.class, "D+"), "the D+ fanout via must be back");
    assertEquals(1, count(blocked, Via.class, "D-"), "the D- fanout via must be back");
    assertEquals(tracesP, count(blocked, PolylineTrace.class, "D+"), "D+ fanout trace back");
    assertEquals(tracesN, count(blocked, PolylineTrace.class, "D-"), "D- fanout trace back");
    for (Pin pin : blocked.board.getPins()) {
      if (pin.componentName().equals("J1")) {
        assertFalse(pin.getNormalContacts().isEmpty(), "the fanout must reach " + pin.name());
      }
    }
  }

  @Test
  void aPairOnAdjacentFinePitchPinsLeavesThePackage() {
    // Two adjacent pins of a 0.5 mm pitch row (0.3 x 1.5 mm pads), 0.2032 mm width and clearance,
    // 0.25 mm gap: the members leave straight out between the neighbouring pads.
    RoutingJob job = getRoutingJob("PairFinePitch.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, new ArrayList<>());
    assertTrue(
        lines != null && lines.stream().anyMatch(l -> l.contains("U200-5") && l.contains("routed")),
        "the fine-pitch pair must be coupled: " + lines);
    // The 0.3 mm pads at 0.5 mm pitch are 0.2 mm apart, below the clearance: those pad-to-pad
    // violations come with the board. None may involve the pair's copper.
    assertTrue(
        new DesignRulesChecker(job.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations().stream()
                .noneMatch(
                    v ->
                        v.firstItem instanceof PolylineTrace
                            || v.secondItem instanceof PolylineTrace),
        "the pair must not violate a clearance");
  }

  private static double skewOf(String line) {
    var m = java.util.regex.Pattern.compile("skew ([0-9.]+) mm").matcher(line);
    assertTrue(m.find(), "no skew in " + line);
    return Double.parseDouble(m.group(1));
  }

  @Test
  void theShorterMemberGetsLengthBumpsUntilThePairMatches() {
    // The pair turns from going down to going right, so the inner member is shorter.
    RoutingJob untuned = getRoutingJob("PairSkew.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(untuned);
    untuned.routerSettings.autorouter.pairMaxSkewUm = -1.0;
    List<String> before =
        PairRouting.apply(untuned.routerSettings, untuned.board, null, new ArrayList<>());
    String beforeLine =
        before.stream().filter(l -> l.contains("routed on layer")).findFirst().orElseThrow();
    assertTrue(skewOf(beforeLine) > 0.1, "the fixture must make skew: " + beforeLine);

    RoutingJob tuned = getRoutingJob("PairSkew.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(tuned);
    List<String> after =
        PairRouting.apply(tuned.routerSettings, tuned.board, null, new ArrayList<>());
    String afterLine =
        after.stream().filter(l -> l.contains("routed on layer")).findFirst().orElseThrow();
    assertTrue(afterLine.contains("tuned from"), "the pair must be tuned: " + afterLine);
    assertTrue(skewOf(afterLine) <= 0.1, "skew must be within 0.1 mm: " + afterLine);
    assertTrue(
        new DesignRulesChecker(tuned.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations()
            .isEmpty(),
        "the bumps must not violate a clearance");
  }

  @Test
  void aPairNeverRunsInsideThePadRingOfAQuadPackage() {
    // The pair's pins are on the right side of a four-sided pad ring and its other end is to the
    // left of the package: through the ring would be shortest, but would close the inner ends of
    // the other pins. The pair goes round outside or is left to the router.
    RoutingJob job = getRoutingJob("PairPadRing.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    long itemsBefore = job.board.getItems().size();
    List<Integer> placed = new ArrayList<>();
    PairRouting.apply(job.routerSettings, job.board, null, placed);
    // Board units are 0.1 um: the pin centres span 17000..23000 um, the pads reach 500 um inward.
    int inner = 175000;
    int outer = 225000;
    for (Item item : job.board.getItems()) {
      if (item instanceof PolylineTrace trace) {
        for (int i = 0; i < trace.polyline().cornerCount(); i++) {
          FloatPoint c = trace.polyline().cornerApprox(i);
          boolean inside =
              c.x > inner + 10000 && c.x < outer - 10000 && c.y > 105000 && c.y < 195000;
          assertFalse(inside, "a pair corner lies inside the pad ring: " + c);
        }
      }
    }
    assertEquals(
        itemsBefore + placed.size(),
        job.board.getItems().size(),
        "the temporary keepouts must be gone");
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
