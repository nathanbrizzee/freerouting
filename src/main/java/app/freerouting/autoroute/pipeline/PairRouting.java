package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.autoroute.path.FoundConnectionLocator;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.StoppableThread;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.rules.Nets;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Routes differential pairs as two coupled traces before the rest of the board, while the board is
 * still empty enough for them to run side by side.
 *
 * <p>Per pair connection the two pads at each end are escaped as a pair: both members leave their
 * pads along the same axis and meet the coupled gap at a gather point, with 45 degree legs. Between
 * the two gather points the maze search routes one virtual trace along the pair's centre line, as
 * wide as both members and the gap together, so every clearance check of the search covers the
 * finished pair exactly. The virtual path is then offset by half the coupled spacing to each side,
 * which gives the two members; no step uses the shadow of a first member, so the second member
 * never loses its room to an obstacle the first one hugged.
 *
 * <p>The pair copper is placed fixed, so the rip-up and the optimizer leave it alone, and released
 * after the last stage. A pair that cannot be routed this way (more than one layer, a polarity
 * swap, no free corridor) is left to the ordinary router, with the reason in the log.
 */
public final class PairRouting {

  /** The state the pair copper holds while the other stages run. */
  public static final FixedState PLACED_STATE = FixedState.USER_FIXED;

  /** Search steps the virtual trace of one pair connection may use. */
  private static final long SEARCH_STEPS = 2_000_000L;

  /** Candidate gather points tried per end, best first. */
  private static final int END_CANDIDATES = 3;

  private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

  private PairRouting() {}

  /**
   * Routes the board's differential pairs. The ids of the copper placed go to {@code itemIds}, for
   * {@link #release}. Returns the lines to log, or null when pair routing is off or the board has
   * no pairs.
   */
  public static List<String> apply(
      RouterSettings settings, RoutingBoard board, StoppableThread thread, List<Integer> itemIds) {
    String mode =
        settings.autorouter != null && settings.autorouter.differentialPairs != null
            ? settings.autorouter.differentialPairs.toLowerCase(Locale.ROOT)
            : "auto";
    if (mode.equals("off")
        || !settings.getRunRouter()
        || (settings.autorouter != null
            && settings.autorouter.maxPasses != null
            && settings.autorouter.maxPasses < 1)
        || board.rules.differentialPairs.isEmpty()) {
      return null;
    }
    List<DifferentialPair> pairs = new ArrayList<>(board.rules.differentialPairs.all());
    pairs.sort(Comparator.comparing(DifferentialPair::baseName, String.CASE_INSENSITIVE_ORDER));
    List<String> lines = new ArrayList<>();
    int routed = 0;
    int connections = 0;
    int attempted = 0;
    for (DifferentialPair pair : pairs) {
      if (thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested())) {
        break;
      }
      List<ConnectionPlan> plans = plan(board, pair, lines);
      for (ConnectionPlan plan : plans) {
        attempted++;
        String outcome = routeConnection(board, settings, thread, pair, plan, itemIds);
        if (outcome.startsWith("routed")) {
          connections++;
        }
        lines.add("Pair " + pair.baseName() + " (" + plan.describe() + "): " + outcome);
      }
      if (!plans.isEmpty()) {
        routed++;
      }
    }
    if (attempted == 0 && lines.isEmpty()) {
      return null;
    }
    board.clearTransientAutorouteState();
    lines.add(
        0,
        "Differential pairs: "
            + pairs.size()
            + " found, "
            + connections
            + " of "
            + attempted
            + " pair connection(s) routed as coupled traces.");
    return lines;
  }

  /** Lets the rip-up and the optimizer work on the pair copper again. */
  public static String release(RoutingBoard board, Collection<Integer> itemIds) {
    int released = 0;
    for (int id : itemIds) {
      Item item = board.getItem(id);
      if (item != null && item.getFixedState() == PLACED_STATE) {
        item.setFixedState(FixedState.UNFIXED);
        released++;
      }
    }
    return released == 0 ? null : "Released " + released + " differential pair trace(s).";
  }

  // ---------------------------------------------------------------------------------------------

  /** One pair connection: the two pin pairs it joins. */
  private record ConnectionPlan(Pin firstP, Pin firstN, Pin secondP, Pin secondN) {
    String describe() {
      return firstP.componentName()
          + "-"
          + firstP.name()
          + " to "
          + secondP.componentName()
          + "-"
          + secondP.name();
    }
  }

  /**
   * The connections to route for a pair: the components that have exactly one pin of each member
   * are the ends; with more than two, a minimum spanning tree over them by distance.
   */
  private static List<ConnectionPlan> plan(
      RoutingBoard board, DifferentialPair pair, List<String> lines) {
    Net positive = pair.positiveNet();
    Net negative = pair.negativeNet();
    TreeMap<Integer, List<Pin>> positiveByComponent = pinsByComponent(board, positive.netNumber);
    TreeMap<Integer, List<Pin>> negativeByComponent = pinsByComponent(board, negative.netNumber);
    List<Integer> ends = new ArrayList<>();
    for (var entry : positiveByComponent.entrySet()) {
      List<Pin> n = negativeByComponent.get(entry.getKey());
      if (n != null && entry.getValue().size() == 1 && n.size() == 1) {
        ends.add(entry.getKey());
      }
    }
    if (ends.size() < 2) {
      lines.add(
          "Pair "
              + pair.baseName()
              + ": not routed as a pair, it has fewer than two parts with one pin of each net.");
      return List.of();
    }
    List<ConnectionPlan> plans = new ArrayList<>();
    // Prim's algorithm over the ends, nearest first, deterministic by component id.
    List<Integer> inTree = new ArrayList<>();
    inTree.add(ends.get(0));
    List<Integer> rest = new ArrayList<>(ends.subList(1, ends.size()));
    while (!rest.isEmpty()) {
      int bestFrom = -1;
      int bestTo = -1;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int from : inTree) {
        for (int to : rest) {
          double d =
              middle(positiveByComponent.get(from).get(0), negativeByComponent.get(from).get(0))
                  .distance(
                      middle(
                          positiveByComponent.get(to).get(0), negativeByComponent.get(to).get(0)));
          if (d < bestDistance) {
            bestDistance = d;
            bestFrom = from;
            bestTo = to;
          }
        }
      }
      plans.add(
          new ConnectionPlan(
              positiveByComponent.get(bestFrom).get(0),
              negativeByComponent.get(bestFrom).get(0),
              positiveByComponent.get(bestTo).get(0),
              negativeByComponent.get(bestTo).get(0)));
      inTree.add(bestTo);
      rest.remove(Integer.valueOf(bestTo));
    }
    // Only connections still open: both members unconnected between the ends.
    List<ConnectionPlan> open = new ArrayList<>();
    for (ConnectionPlan plan : plans) {
      boolean connected =
          plan.firstP().getConnectedSet(positive.netNumber).contains(plan.secondP())
              || plan.firstN().getConnectedSet(negative.netNumber).contains(plan.secondN());
      if (!connected) {
        open.add(plan);
      }
    }
    return open;
  }

  private static TreeMap<Integer, List<Pin>> pinsByComponent(RoutingBoard board, int netNumber) {
    TreeMap<Integer, List<Pin>> result = new TreeMap<>();
    for (Item item : board.getConnectableItems(netNumber)) {
      if (item instanceof Pin pin && pin.netCount() == 1 && !pin.hasIgnoredNets()) {
        result.computeIfAbsent(pin.getComponentId(), k -> new ArrayList<>()).add(pin);
      }
    }
    return result;
  }

  private static FloatPoint middle(Pin p, Pin n) {
    FloatPoint a = p.getCenter().toFloat();
    FloatPoint b = n.getCenter().toFloat();
    return new FloatPoint((a.x + b.x) / 2, (a.y + b.y) / 2);
  }

  // ---------------------------------------------------------------------------------------------

  /** Where one end of a pair connection leaves its two pads. */
  private record End(
      IntPoint[] positive,
      IntPoint[] negative,
      IntPoint gather,
      int dx,
      int dy,
      int positiveSide,
      double cost) {}

  private static String routeConnection(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      DifferentialPair pair,
      ConnectionPlan plan,
      List<Integer> itemIds) {
    int netP = pair.positiveNet().netNumber;
    int netN = pair.negativeNet().netNumber;
    var classP = board.rules.nets.get(netP).getNetClass();
    var classN = board.rules.nets.get(netN).getNetClass();
    String lastFailure = "no layer carries all four pads (" + padLayers(plan) + ")";
    for (int layer : candidateLayers(board, plan)) {
      int halfWidth = classP.getTraceHalfWidth(layer);
      int width = 2 * halfWidth;
      int clearance =
          board.rules.clearanceMatrix.getValue(
              classP.getTraceClearanceClass(), classN.getTraceClearanceClass(), layer, false);
      int gap = (int) Math.round(pair.coupledSpacing());
      if (gap <= 0 && settings.autorouter != null && settings.autorouter.pairGapUm != null) {
        gap = umToBoard(board, settings.autorouter.pairGapUm);
      }
      gap = Math.max(gap, clearance);
      int spacing = width + gap; // centre to centre of the two members
      int virtualHalf = (2 * width + gap + 1) / 2;
      int allowance = ExitStubs.copperAllowance(board, settings);

      List<End> endsA =
          endCandidates(
              board,
              plan.firstP(),
              plan.firstN(),
              plan.secondP(),
              plan.secondN(),
              layer,
              spacing,
              width,
              netP,
              classP.getTraceClearanceClass(),
              virtualHalf,
              allowance);
      List<End> endsB =
          endCandidates(
              board,
              plan.secondP(),
              plan.secondN(),
              plan.firstP(),
              plan.firstN(),
              layer,
              spacing,
              width,
              netP,
              classP.getTraceClearanceClass(),
              virtualHalf,
              allowance);
      if (endsA.isEmpty() || endsB.isEmpty()) {
        lastFailure = "no clear gather point at an end on layer " + layer;
        continue;
      }
      for (End a : endsA) {
        for (End b : endsB) {
          if (a.positiveSide() != -b.positiveSide()) {
            lastFailure = "the members would have to cross (polarity swap)";
            continue;
          }
          String outcome =
              tryRoute(
                  board,
                  settings,
                  thread,
                  pair,
                  plan,
                  layer,
                  halfWidth,
                  spacing,
                  virtualHalf,
                  allowance,
                  a,
                  b,
                  itemIds);
          if (outcome.startsWith("routed")) {
            return outcome;
          }
          lastFailure = outcome;
          if (thread != null && thread.isStopRequested()) {
            return lastFailure;
          }
        }
      }
    }
    return "left to the router: " + lastFailure;
  }

  private static String padLayers(ConnectionPlan plan) {
    StringBuilder sb = new StringBuilder();
    for (Pin pin : new Pin[] {plan.firstP(), plan.firstN(), plan.secondP(), plan.secondN()}) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(pin.componentName())
          .append('-')
          .append(pin.name())
          .append(" layers ")
          .append(pin.firstLayer())
          .append('-')
          .append(pin.lastLayer());
    }
    return sb.toString();
  }

  private static List<Integer> candidateLayers(RoutingBoard board, ConnectionPlan plan) {
    int count = board.getLayerCount();
    List<Integer> layers = new ArrayList<>();
    for (int layer = 0; layer < count; layer++) {
      if (board.layerStructure.layers[layer].isSignal
          && plan.firstP().isOnLayer(layer)
          && plan.firstN().isOnLayer(layer)
          && plan.secondP().isOnLayer(layer)
          && plan.secondN().isOnLayer(layer)) {
        layers.add(layer);
      }
    }
    // Outer layers first: a surface pair needs no vias.
    layers.sort(
        Comparator.comparingInt(layer -> layer == 0 ? 0 : layer == count - 1 ? 1 : 2 + layer));
    return layers;
  }

  private static int umToBoard(RoutingBoard board, double um) {
    int resolution = Math.max(1, board.communication.resolution);
    return (int)
        Math.round(
            app.freerouting.board.model.structure.Unit.scale(
                um * resolution,
                app.freerouting.board.model.structure.Unit.UM,
                board.communication.unit));
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * The ways the two pads of one end can leave along an axis, best first. The members keep their
   * order across the axis; both end on the coupled spacing at the same distance from the pads.
   */
  private static List<End> endCandidates(
      RoutingBoard board,
      Pin pinP,
      Pin pinN,
      Pin otherP,
      Pin otherN,
      int layer,
      int spacing,
      int width,
      int netP,
      int traceClass,
      int virtualHalf,
      int allowance) {
    IntPoint cP = centre(pinP);
    IntPoint cN = centre(pinN);
    FloatPoint towardOther = middle(otherP, otherN);
    FloatPoint here = middle(pinP, pinN);
    int halfWidth = width / 2;
    List<End> found = new ArrayList<>();
    for (int[] d : DIRECTIONS) {
      int dx = d[0];
      int dy = d[1];
      int nx = -dy;
      int ny = dx;
      int fP = dx * cP.x + dy * cP.y;
      int qP = nx * cP.x + ny * cP.y;
      int fN = dx * cN.x + dy * cN.y;
      int qN = nx * cN.x + ny * cN.y;
      if (qP == qN) {
        continue; // the pads lie on the axis, one behind the other
      }
      int qMid2 = qP + qN;
      int qMid = Math.floorDiv(qMid2, 2);
      int half = spacing / 2;
      int qPTarget;
      int qNTarget;
      if (qP < qN) {
        qPTarget = qMid - half;
        qNTarget = qPTarget + spacing;
      } else {
        qNTarget = qMid - half;
        qPTarget = qNTarget + spacing;
      }
      int legP = Math.abs(qPTarget - qP);
      int legN = Math.abs(qNTarget - qN);
      int sMinP = forwardExtent(pinP, dx, dy, fP) + width;
      int sMinN = forwardExtent(pinN, dx, dy, fN) + width;
      int forward = Math.max(fP + sMinP + legP, fN + sMinN + legN);
      IntPoint[] pathP = member(cP, dx, dy, nx, ny, fP, qP, forward, qPTarget, legP);
      IntPoint[] pathN = member(cN, dx, dy, nx, ny, fN, qN, forward, qNTarget, legN);
      IntPoint gather = at(dx, dy, nx, ny, forward, (qPTarget + qNTarget) / 2);
      IntPoint stubEnd = new IntPoint(gather.x + dx * virtualHalf, gather.y + dy * virtualHalf);
      double slackP =
          ExitStubs.pathSlack(board, pathP, layer, halfWidth, netP, traceClass, allowance);
      double slackN = Double.POSITIVE_INFINITY;
      // N is another net: check it as one, against the board without P's members.
      slackN =
          ExitStubs.pathSlack(
              board, pathN, layer, halfWidth, pinN.getNetNumber(0), traceClass, allowance);
      double slackStub =
          ExitStubs.pathSlack(
              board,
              new Point[] {gather, stubEnd},
              layer,
              virtualHalf,
              netP,
              traceClass,
              allowance);
      if (slackP < 0 || slackN < 0 || slackStub < 0) {
        continue;
      }
      double length = pathLength(pathP) + pathLength(pathN);
      double skew = Math.abs(pathLength(pathP) - pathLength(pathN));
      // Prefer the shortest breakout, matched members, and an axis that points at the other end.
      double toward = (towardOther.x - here.x) * dx + (towardOther.y - here.y) * dy;
      double cost = length + 4 * skew + (toward < 0 ? 2.0 * Math.abs(toward) : 0);
      int side = qPTarget > qNTarget ? 1 : -1;
      found.add(new End(pathP, pathN, gather, dx, dy, side, cost));
    }
    found.sort(Comparator.comparingDouble(End::cost));
    return found.size() > END_CANDIDATES ? found.subList(0, END_CANDIDATES) : found;
  }

  private static IntPoint[] member(
      IntPoint centre,
      int dx,
      int dy,
      int nx,
      int ny,
      int f,
      int q,
      int forward,
      int qTarget,
      int leg) {
    List<IntPoint> points = new ArrayList<>();
    points.add(centre);
    int straight = forward - f - leg;
    if (leg > 0 && straight > 0) {
      points.add(at(dx, dy, nx, ny, f + straight, q));
    }
    points.add(at(dx, dy, nx, ny, forward, qTarget));
    return points.toArray(new IntPoint[0]);
  }

  private static IntPoint at(int dx, int dy, int nx, int ny, int f, int q) {
    return new IntPoint(dx * f + nx * q, dy * f + ny * q);
  }

  private static int forwardExtent(Pin pin, int dx, int dy, int centreF) {
    IntBox box = pin.boundingBox();
    int best = 0;
    for (int x : new int[] {box.ll.x, box.ur.x}) {
      for (int y : new int[] {box.ll.y, box.ur.y}) {
        best = Math.max(best, dx * x + dy * y - centreF);
      }
    }
    return best;
  }

  private static IntPoint centre(Pin pin) {
    return pin.getCenter().toFloat().round();
  }

  private static double pathLength(Point[] corners) {
    double length = 0;
    for (int i = 0; i + 1 < corners.length; i++) {
      length += corners[i].toFloat().distance(corners[i + 1].toFloat());
    }
    return length;
  }

  // ---------------------------------------------------------------------------------------------

  private static String tryRoute(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      DifferentialPair pair,
      ConnectionPlan plan,
      int layer,
      int halfWidth,
      int spacing,
      int virtualHalf,
      int allowance,
      End a,
      End b,
      List<Integer> itemIds) {
    int netP = pair.positiveNet().netNumber;
    int netN = pair.negativeNet().netNumber;
    var classP = board.rules.nets.get(netP).getNetClass();
    var classN = board.rules.nets.get(netN).getNetClass();
    int traceClass = classP.getTraceClearanceClass();
    List<Item> stubs = new ArrayList<>();
    List<IntPoint> centreLine;
    try {
      PolylineTrace stubA = stub(board, a, layer, virtualHalf, traceClass);
      PolylineTrace stubB = stub(board, b, layer, virtualHalf, traceClass);
      if (stubA != null) {
        stubs.add(stubA);
      }
      if (stubB != null) {
        stubs.add(stubB);
      }
      if (stubA == null || stubB == null) {
        return "the gather point cannot be placed";
      }
      // The stubs carry the hidden net, so that no pad of either member counts as a destination.
      AutorouteControl control = new AutorouteControl(board, netP, settings);
      control.netNumber = Nets.hidden_net_number;
      control.traceClearanceClassIndex = traceClass;
      control.viasAllowed = false;
      control.ripupAllowed = false;
      control.removeUnconnectedVias = false;
      for (int i = 0; i < control.layerCount; i++) {
        control.layerActive[i] = control.layerActive[i] && i == layer;
      }
      int compensation = control.compensatedTraceHalfWidth[layer] - control.traceHalfWidth[layer];
      control.traceHalfWidth[layer] = virtualHalf;
      control.compensatedTraceHalfWidth[layer] = virtualHalf + compensation;
      AutorouteEngine engine =
          board.initAutoroute(
              Nets.hidden_net_number, traceClass, thread, TimeLimit.ofSteps(SEARCH_STEPS), false);
      List<FoundConnectionLocator.Segment> segments =
          engine.locateConnection(Set.of(stubA), Set.of(stubB), control);
      if (segments == null || segments.isEmpty()) {
        return "no free corridor for the pair on layer " + layer;
      }
      centreLine = centreLine(a, b, virtualHalf, segments, layer);
      if (centreLine == null) {
        return "the corridor needed a layer change";
      }
    } finally {
      for (Item stub : stubs) {
        if (stub.isOnTheBoard()) {
          board.removeItem(stub);
        }
      }
      board.clearTransientAutorouteState();
    }

    double half = spacing / 2.0;
    List<IntPoint> positive = coupled(centreLine, a, b, half * a.positiveSide(), true);
    List<IntPoint> negative = coupled(centreLine, a, b, -half * a.positiveSide(), false);
    positive = joinEnds(a.positive(), positive, b.positive());
    negative = joinEnds(a.negative(), negative, b.negative());
    Point[] positiveCorners = positive.toArray(new Point[0]);
    Point[] negativeCorners = negative.toArray(new Point[0]);
    if (ExitStubs.pathSlack(board, positiveCorners, layer, halfWidth, netP, traceClass, allowance)
        < 0) {
      return "the coupled P trace would violate a clearance";
    }
    if (ExitStubs.pathSlack(
            board,
            negativeCorners,
            layer,
            halfWidth,
            netN,
            classN.getTraceClearanceClass(),
            allowance)
        < 0) {
      return "the coupled N trace would violate a clearance";
    }
    List<Item> placed = new ArrayList<>();
    PolylineTrace p =
        board.insertTraceWithoutCleaning(
            new Polyline(positiveCorners),
            layer,
            halfWidth,
            new int[] {netP},
            traceClass,
            PLACED_STATE);
    if (p != null) {
      placed.add(p);
    }
    PolylineTrace n =
        board.insertTraceWithoutCleaning(
            new Polyline(negativeCorners),
            layer,
            halfWidth,
            new int[] {netN},
            classN.getTraceClearanceClass(),
            PLACED_STATE);
    if (n != null) {
      placed.add(n);
    }
    boolean ok =
        p != null
            && n != null
            && plan.firstP().getConnectedSet(netP).contains(plan.secondP())
            && plan.firstN().getConnectedSet(netN).contains(plan.secondN());
    if (!ok) {
      for (Item item : placed) {
        board.removeItem(item);
      }
      return "the coupled traces did not connect the pads";
    }
    for (Item item : placed) {
      itemIds.add(item.getId());
    }
    double lengthP = pathLength(positiveCorners);
    double lengthN = pathLength(negativeCorners);
    return String.format(
        Locale.ROOT,
        "routed on layer %d, lengths %.3f and %.3f mm, skew %.3f mm",
        layer,
        toMm(board, lengthP),
        toMm(board, lengthN),
        toMm(board, Math.abs(lengthP - lengthN)));
  }

  private static double toMm(RoutingBoard board, double boardUnits) {
    return app.freerouting.board.model.structure.Unit.scale(
        boardUnits / Math.max(1, board.communication.resolution),
        board.communication.unit,
        app.freerouting.board.model.structure.Unit.MM);
  }

  private static PolylineTrace stub(
      RoutingBoard board, End end, int layer, int virtualHalf, int traceClass) {
    IntPoint tip =
        new IntPoint(
            end.gather().x + end.dx() * virtualHalf, end.gather().y + end.dy() * virtualHalf);
    return board.insertTraceWithoutCleaning(
        new Polyline(new Point[] {end.gather(), tip}),
        layer,
        virtualHalf,
        new int[] {Nets.hidden_net_number},
        traceClass,
        FixedState.UNFIXED);
  }

  /** The virtual path from gather point to gather point, simplified. */
  private static List<IntPoint> centreLine(
      End a, End b, int virtualHalf, List<FoundConnectionLocator.Segment> segments, int layer) {
    List<IntPoint> route = new ArrayList<>();
    for (FoundConnectionLocator.Segment segment : segments) {
      if (segment.layer() != layer) {
        return null;
      }
      List<IntPoint> corners = new ArrayList<>(List.of(segment.corners()));
      if (!route.isEmpty()) {
        IntPoint last = route.get(route.size() - 1);
        if (corners.get(0).distance(last) > corners.get(corners.size() - 1).distance(last)) {
          java.util.Collections.reverse(corners);
        }
      } else if (corners.get(0).distance(a.gather())
          > corners.get(corners.size() - 1).distance(a.gather())) {
        java.util.Collections.reverse(corners);
      }
      route.addAll(corners);
    }
    List<IntPoint> line = new ArrayList<>();
    line.add(a.gather());
    if (!route.isEmpty() && !onSegment(route.get(0), a.gather(), tip(a, virtualHalf))) {
      line.add(tip(a, virtualHalf));
    }
    line.addAll(route);
    if (!route.isEmpty()
        && !onSegment(route.get(route.size() - 1), b.gather(), tip(b, virtualHalf))) {
      line.add(tip(b, virtualHalf));
    }
    line.add(b.gather());
    return simplify(line);
  }

  private static IntPoint tip(End end, int virtualHalf) {
    return new IntPoint(
        end.gather().x + end.dx() * virtualHalf, end.gather().y + end.dy() * virtualHalf);
  }

  private static boolean onSegment(IntPoint p, IntPoint a, IntPoint b) {
    long cross = (long) (b.x - a.x) * (p.y - a.y) - (long) (b.y - a.y) * (p.x - a.x);
    if (cross != 0) {
      return false;
    }
    return p.x >= Math.min(a.x, b.x)
        && p.x <= Math.max(a.x, b.x)
        && p.y >= Math.min(a.y, b.y)
        && p.y <= Math.max(a.y, b.y);
  }

  /** Drops repeated points and points in the middle of a straight run. */
  private static List<IntPoint> simplify(List<IntPoint> points) {
    List<IntPoint> distinct = new ArrayList<>();
    for (IntPoint p : points) {
      if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(p)) {
        distinct.add(p);
      }
    }
    boolean changed = true;
    while (changed && distinct.size() > 2) {
      changed = false;
      for (int i = 1; i + 1 < distinct.size(); i++) {
        IntPoint p0 = distinct.get(i - 1);
        IntPoint p1 = distinct.get(i);
        IntPoint p2 = distinct.get(i + 1);
        long cross = (long) (p1.x - p0.x) * (p2.y - p1.y) - (long) (p1.y - p0.y) * (p2.x - p1.x);
        long dot = (long) (p1.x - p0.x) * (p2.x - p1.x) + (long) (p1.y - p0.y) * (p2.y - p1.y);
        if (cross == 0 && dot >= 0) {
          distinct.remove(i);
          changed = true;
          break;
        }
      }
    }
    return distinct;
  }

  /**
   * The centre line offset by {@code offset} to the left of its direction of travel (negative:
   * right), with mitred corners.
   */
  private static List<IntPoint> coupled(
      List<IntPoint> line, End a, End b, double offset, boolean positiveMember) {
    int n = line.size();
    List<IntPoint> result = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double[] in = i > 0 ? unit(line.get(i - 1), line.get(i)) : null;
      double[] out = i + 1 < n ? unit(line.get(i), line.get(i + 1)) : null;
      double x = line.get(i).x;
      double y = line.get(i).y;
      if (in == null) {
        x += -out[1] * offset;
        y += out[0] * offset;
      } else if (out == null) {
        x += -in[1] * offset;
        y += in[0] * offset;
      } else {
        double mx = -in[1] - out[1];
        double my = in[0] + out[0];
        double denominator = 1 + in[0] * out[0] + in[1] * out[1];
        if (denominator < 1e-6) {
          denominator = 1e-6;
        }
        x += offset * mx / denominator;
        y += offset * my / denominator;
      }
      result.add(new IntPoint((int) Math.round(x), (int) Math.round(y)));
    }
    return result;
  }

  private static double[] unit(IntPoint from, IntPoint to) {
    double dx = to.x - from.x;
    double dy = to.y - from.y;
    double length = Math.hypot(dx, dy);
    return new double[] {dx / length, dy / length};
  }

  /**
   * One member's polyline: its breakout at the first end, the coupled path (whose first and last
   * corner are replaced by the breakouts' ends), its breakout at the other end reversed.
   */
  private static List<IntPoint> joinEnds(
      IntPoint[] breakoutA, List<IntPoint> coupled, IntPoint[] breakoutB) {
    List<IntPoint> all = new ArrayList<>(List.of(breakoutA));
    for (int i = 1; i + 1 < coupled.size(); i++) {
      all.add(coupled.get(i));
    }
    for (int i = breakoutB.length - 1; i >= 0; i--) {
      all.add(breakoutB[i]);
    }
    return simplify(all);
  }

  /** Items placed by an earlier call are looked up by id, so the set is also usable in tests. */
  static Set<Integer> idsOf(Collection<Item> items) {
    Set<Integer> ids = new HashSet<>();
    for (Item item : items) {
      ids.add(item.getId());
    }
    return ids;
  }
}
