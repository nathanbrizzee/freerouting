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

  /**
   * A one-layer board with a wall that has two gaps: the middle one, on the pair's direct line, is
   * the only one the 1 mm trace of WIDE fits through; the pair fits through either. The pair stage
   * takes the middle gap and leaves WIDE open; the re-route as a unit routes WIDE there and moves
   * the pair, still coupled, to the other gap.
   */
  @Test
  void aPairInTheWayOfAnOpenConnectionIsRoutedAgainAroundIt() {
    RoutingJob job = getRoutingJob("PairReroute.dsn", new TestingSettings());
    job.routerSettings.autorouter.maxPasses = 3;
    job.routerSettings.optimizer.enabled = false;
    runRoutingJob(job);

    DesignRulesChecker drc = new DesignRulesChecker(job.board, new DesignRulesCheckerSettings());
    drc.calculateAllIncompletes();
    assertEquals(0, drc.getIncompleteCount(), "WIDE and both pair members must be complete");
    assertTrue(drc.getAllClearanceViolations().isEmpty(), "nothing may violate a clearance");
    int netP = job.board.rules.nets.get("D+", 1).netNumber;
    for (PolylineTrace trace : tracesOf(job, netP)) {
      for (int i = 0; i < trace.polyline().cornerCount(); i++) {
        FloatPoint corner = trace.polyline().cornerApprox(i);
        assertFalse(
            corner.x > 19000 * 10 && corner.x < 21000 * 10 && corner.y < 16000 * 10,
            "the pair must have left the middle gap to WIDE: " + corner);
      }
    }

    RoutingJob off = getRoutingJob("PairReroute.dsn", new TestingSettings());
    off.routerSettings.autorouter.maxPasses = 3;
    off.routerSettings.optimizer.enabled = false;
    off.routerSettings.autorouter.pairReroute = -1;
    runRoutingJob(off);
    DesignRulesChecker offDrc = new DesignRulesChecker(off.board, new DesignRulesCheckerSettings());
    offDrc.calculateAllIncompletes();
    assertEquals(1, offDrc.getIncompleteCount(), "without the re-route WIDE stays open");
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

  /**
   * The same pair with {@code (skew 2000)} in its rule (2 mm in this file's units). The rule is a
   * limit, not a goal: with the 0.1 mm global target the pair is still tuned exactly as without the
   * rule (leaving the bumps out changes the copper the rest of the board routes around), and the
   * line names the pair's own target, which the pair re-route accepts against.
   */
  @Test
  void aLooserPairSkewRuleStillTunesToTheGlobalTarget() {
    RoutingJob plain = getRoutingJob("PairSkew.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(plain);
    String plainLine =
        PairRouting.apply(plain.routerSettings, plain.board, null, new ArrayList<>()).stream()
            .filter(l -> l.contains("routed on layer"))
            .findFirst()
            .orElseThrow();

    RoutingJob job = getRoutingJob("PairSkewRule.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    assertEquals(100.0, job.routerSettings.autorouter.pairMaxSkewUm, 1e-9);
    String line =
        PairRouting.apply(job.routerSettings, job.board, null, new ArrayList<>()).stream()
            .filter(l -> l.contains("routed on layer"))
            .findFirst()
            .orElseThrow();
    assertTrue(line.contains("(target 2.000 mm)"), "the pair's own target: " + line);
    assertTrue(line.contains("tuned from"), "still tuned to the global target: " + line);
    assertEquals(
        plainLine.replace("(target 0.100 mm)", "(target 2.000 mm)"),
        line,
        "the rule must not change the routed pair");
  }

  /** With a global target looser than the pair's rule, the rule is the tighter one and governs. */
  @Test
  void aTighterPairSkewRuleGovernsTheTuning() {
    RoutingJob job = getRoutingJob("PairSkewRule.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    job.routerSettings.autorouter.pairMaxSkewUm = 5000.0;
    String line =
        PairRouting.apply(job.routerSettings, job.board, null, new ArrayList<>()).stream()
            .filter(l -> l.contains("routed on layer"))
            .findFirst()
            .orElseThrow();
    assertFalse(line.contains("tuned"), "within the 2 mm rule, no bumps: " + line);
    assertTrue(skewOf(line) > 0.1 && skewOf(line) <= 2.0, "the bend's skew stays: " + line);
  }

  /**
   * The pads at J1 sit side by side across the route; at U1 the D- pad sits 1.2 mm further along,
   * so D+ comes up short at the U1 end. The bumps that make up for it go near that end (TI
   * SPRACP4A: as close to the mismatched end as possible), not wherever the run starts.
   */
  @Test
  void lengthBumpsGoNearTheEndThatCausesTheSkew() {
    RoutingJob job = getRoutingJob("PairSkewEnd.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, new ArrayList<>());
    String line =
        lines.stream().filter(l -> l.contains("routed on layer")).findFirst().orElseThrow();
    assertTrue(line.contains("tuned from"), "the pair must be tuned: " + line);
    assertTrue(skewOf(line) <= 0.1, "skew must be within 0.1 mm: " + line);

    // D+ runs straight at y = 15200 um between the ends; its bump corners leave that line.
    int netP = job.board.rules.nets.get("D+", 1).netNumber;
    int bumpCorners = 0;
    for (PolylineTrace trace : tracesOf(job, netP)) {
      for (int i = 0; i < trace.polyline().cornerCount(); i++) {
        FloatPoint corner = trace.polyline().cornerApprox(i);
        boolean onRun = corner.x > 100000 && corner.x < 430000;
        if (onRun && Math.abs(corner.y - 152000) > 1000) {
          bumpCorners++;
          assertTrue(
              corner.x > 245000,
              "a bump corner is in the J1 half, far from the staggered U1 pads: " + corner);
        }
      }
    }
    assertTrue(bumpCorners > 0, "the shorter member must have bumps: " + line);
    assertTrue(
        new DesignRulesChecker(job.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations()
            .isEmpty(),
        "the bumps must not violate a clearance");
  }

  /**
   * Four layers with a GND plane on In1.Cu; the pair's pads are on opposite sides, so it changes
   * layer through a via pair. With pair_return_via_mm set (default off), each signal via gets a GND
   * return via on its outer side, within that distance, none between the two signal vias, and
   * nothing violates a clearance.
   */
  @Test
  void aViaPairGetsGroundReturnViasBesideIt() {
    RoutingJob job = getRoutingJob("PairReturnVia.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(job);
    job.routerSettings.autorouter.pairReturnViaMm = 1.27;
    List<String> lines = PairRouting.apply(job.routerSettings, job.board, null, new ArrayList<>());
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("routed on layer") && l.contains("via pair")),
        "the pair must change layer through a via pair: " + lines);
    String line =
        lines.stream().filter(l -> l.startsWith("Return vias:")).findFirst().orElseThrow();
    assertTrue(line.contains("2 of 2 placed"), "both signal vias need one: " + line);

    int ground = job.board.rules.nets.get("GND", 1).netNumber;
    int netP = job.board.rules.nets.get("D+", 1).netNumber;
    int netN = job.board.rules.nets.get("D-", 1).netNumber;
    List<Via> groundVias = new ArrayList<>();
    FloatPoint p = null;
    FloatPoint n = null;
    for (Item item : job.board.getItems()) {
      if (item instanceof Via via) {
        if (via.containsNet(ground)) {
          groundVias.add(via);
        } else if (via.containsNet(netP)) {
          p = via.getCenter().toFloat();
        } else if (via.containsNet(netN)) {
          n = via.getCenter().toFloat();
        }
      }
    }
    assertEquals(2, groundVias.size(), "one GND via per signal via");
    double reach = 1.27 * 10000; // board units are 0.1 um
    for (Via via : groundVias) {
      FloatPoint at = via.getCenter().toFloat();
      assertTrue(
          Math.min(at.distance(p), at.distance(n)) <= reach,
          "a return via must sit within 1.27 mm of its signal via: " + at);
      // Projected on the line from the P via to the N via, it must lie outside the two.
      double dx = n.x - p.x;
      double dy = n.y - p.y;
      double t = ((at.x - p.x) * dx + (at.y - p.y) * dy) / (dx * dx + dy * dy);
      assertFalse(t > 0 && t < 1, "a return via must not sit between the signal vias: " + at);
    }
    assertTrue(
        new DesignRulesChecker(job.board, new DesignRulesCheckerSettings())
            .getAllClearanceViolations()
            .isEmpty(),
        "the return vias must not violate a clearance");

    RoutingJob off = getRoutingJob("PairReturnVia.dsn", new TestingSettings());
    BoardLoader.loadBoardIfNeeded(off);
    off.routerSettings.autorouter.pairReturnViaMm = -1.0;
    List<String> offLines =
        PairRouting.apply(off.routerSettings, off.board, null, new ArrayList<>());
    assertFalse(offLines.stream().anyMatch(l -> l.startsWith("Return vias")), offLines.toString());
    assertTrue(
        off.board.getItems().stream().noneMatch(i -> i instanceof Via && i.containsNet(ground)),
        "no return vias when they are off");
  }

  /**
   * D+ is already wired on F.Cu, so the pair router leaves the pair to the ordinary router. A
   * keepout on F.Cu lies across the straight way of D-, which makes B.Cu the cheap way; held to its
   * partner's layers, D- goes round on F.Cu. Without the hold it takes B.Cu and the split is
   * reported.
   */
  @Test
  void anUncoupledMemberKeepsToItsPartnersLayers() {
    RoutingJob held = getRoutingJob("PairSplitLayer.dsn", new TestingSettings());
    held.routerSettings.autorouter.maxPasses = 2;
    held.routerSettings.optimizer.enabled = false;
    runRoutingJob(held);
    int netP = held.board.rules.nets.get("D+", 1).netNumber;
    int netN = held.board.rules.nets.get("D-", 1).netNumber;
    DesignRulesChecker drc = new DesignRulesChecker(held.board, new DesignRulesCheckerSettings());
    drc.calculateAllIncompletes();
    assertEquals(0, drc.getIncompleteCount(), "D- must be routed");
    assertTrue(
        tracesOf(held, netN).stream().allMatch(t -> t.getLayer() == 0),
        "D- must stay on F.Cu with D+");
    assertTrue(PairRouting.layerSplits(held.board).isEmpty(), "no split to report");

    RoutingJob free = getRoutingJob("PairSplitLayer.dsn", new TestingSettings());
    free.routerSettings.autorouter.maxPasses = 2;
    free.routerSettings.optimizer.enabled = false;
    free.routerSettings.autorouter.pairLayerHoldPasses = -1;
    runRoutingJob(free);
    assertTrue(
        tracesOf(free, netN).stream().anyMatch(t -> t.getLayer() == 1),
        "the fixture must make B.Cu the cheap way for D- when it is not held");
    List<String> splits = PairRouting.layerSplits(free.board);
    assertEquals(1, splits.size(), "the split must be reported: " + splits);
    assertTrue(
        splits.get(0).contains("D+ on F.Cu") && splits.get(0).contains("B.Cu"), splits.get(0));
    assertTrue(netP > 0);
  }

  /**
   * With no hold in the passes D- takes B.Cu as above; the layer repair after the routing stage
   * then moves it onto D+'s layer, F.Cu, and the board stays complete.
   */
  @Test
  void aSplitPairIsMovedOntoOneMembersLayersAfterRouting() {
    RoutingJob job = getRoutingJob("PairSplitLayer.dsn", new TestingSettings());
    job.routerSettings.autorouter.maxPasses = 2;
    job.routerSettings.optimizer.enabled = false;
    job.routerSettings.autorouter.pairLayerHoldPasses = 0;
    runRoutingJob(job);
    int netN = job.board.rules.nets.get("D-", 1).netNumber;
    DesignRulesChecker drc = new DesignRulesChecker(job.board, new DesignRulesCheckerSettings());
    drc.calculateAllIncompletes();
    assertEquals(0, drc.getIncompleteCount(), "D- must stay routed");
    assertTrue(
        tracesOf(job, netN).stream().allMatch(t -> t.getLayer() == 0),
        "D- must be moved onto F.Cu with D+");
    assertTrue(PairRouting.layerSplits(job.board).isEmpty(), "no split left to report");
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
