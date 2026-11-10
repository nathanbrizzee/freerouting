package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.autoroute.path.FoundConnectionLocator;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.board.trace.PolylineTrace;
import app.freerouting.core.StoppableThread;
import app.freerouting.core.library.Padstack;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.Polyline;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.rules.NetClass;
import app.freerouting.rules.Nets;
import app.freerouting.rules.ViaInfo;
import app.freerouting.rules.ViaRule;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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

  /** Most searches one pair connection may use over all its layers and gather points. */
  private static final int MAX_SEARCHES = 12;

  /** Cost of a via pair in coupled spacings of breakout length. */
  private static final double VIA_PAIR_COST = 40;

  private static final int[][] DIRECTIONS = {
    {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {-1, -1}, {1, -1}
  };

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
            && settings.autorouter.maxPasses < 0)
        || board.rules.differentialPairs.isEmpty()) {
      return null;
    }
    List<DifferentialPair> pairs = new ArrayList<>(board.rules.differentialPairs.all());
    pairs.sort(Comparator.comparing(DifferentialPair::baseName, String.CASE_INSENSITIVE_ORDER));
    List<String> lines = new ArrayList<>();
    int connections = 0;
    int attempted = 0;
    for (DifferentialPair pair : pairs) {
      if (thread != null && (thread.isStopRequested() || thread.isStopAutoRouterRequested())) {
        break;
      }
      List<ConnectionPlan> plans = plan(board, pair, lines);
      if (!plans.isEmpty() && pair.coupledSpacing() <= 0) {
        boolean fromSetting = settings.autorouter != null && settings.autorouter.pairGapUm != null;
        lines.add(
            "Pair "
                + pair.baseName()
                + ": no declared gap, using "
                + (fromSetting
                    ? "pair_gap_um (" + settings.autorouter.pairGapUm + " um), or the clearance"
                    : "the clearance")
                + " if larger.");
      }
      for (ConnectionPlan plan : plans) {
        attempted++;
        String outcome = routeConnection(board, settings, thread, pair, plan, itemIds);
        if (outcome.startsWith("routed")) {
          connections++;
        }
        lines.add("Pair " + pair.baseName() + " (" + plan.describe() + "): " + outcome);
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
   * The connections to route for a pair: every component that has a pin of each member is an end,
   * at its closest pair of a positive and a negative pin (a connector that has several pins on a
   * member, like the paralleled CC pins of a USB-C receptacle, joins at the pair that sits
   * together); with more than two ends, a minimum spanning tree over them by distance. The other
   * pins of such a component are left to the ordinary router.
   */
  private static List<ConnectionPlan> plan(
      RoutingBoard board, DifferentialPair pair, List<String> lines) {
    Net positive = pair.positiveNet();
    Net negative = pair.negativeNet();
    TreeMap<Integer, List<Pin>> positiveByComponent = pinsByComponent(board, positive.netNumber);
    TreeMap<Integer, List<Pin>> negativeByComponent = pinsByComponent(board, negative.netNumber);
    // The pin pair each end joins at.
    TreeMap<Integer, Pin[]> endPins = new TreeMap<>();
    for (var entry : positiveByComponent.entrySet()) {
      List<Pin> n = negativeByComponent.get(entry.getKey());
      if (n != null && !entry.getValue().isEmpty() && !n.isEmpty()) {
        endPins.put(entry.getKey(), closestPair(entry.getValue(), n));
      }
    }
    List<Integer> ends = new ArrayList<>(endPins.keySet());
    if (ends.size() < 2) {
      lines.add(
          "Pair "
              + pair.baseName()
              + ": not routed as a pair, it has fewer than two parts with a pin of each net.");
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
              middle(endPins.get(from)[0], endPins.get(from)[1])
                  .distance(middle(endPins.get(to)[0], endPins.get(to)[1]));
          if (d < bestDistance) {
            bestDistance = d;
            bestFrom = from;
            bestTo = to;
          }
        }
      }
      plans.add(
          new ConnectionPlan(
              endPins.get(bestFrom)[0],
              endPins.get(bestFrom)[1],
              endPins.get(bestTo)[0],
              endPins.get(bestTo)[1]));
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

  /** The positive and the negative pin that are closest together; the first of a tie wins. */
  private static Pin[] closestPair(List<Pin> positives, List<Pin> negatives) {
    Pin[] best = null;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (Pin p : positives) {
      for (Pin n : negatives) {
        double d = p.getCenter().toFloat().distance(n.getCenter().toFloat());
        if (d < bestDistance) {
          bestDistance = d;
          best = new Pin[] {p, n};
        }
      }
    }
    return best;
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

  /** One trace of a member: its layer and its corners from the pad outward. */
  private record Piece(int layer, IntPoint[] corners) {}

  /** A via of one member: where it sits and which via it is. */
  private record ViaSpot(IntPoint at, ViaInfo info, int net) {}

  /**
   * Where one end of a pair connection leaves its two pads: for each member the traces from the pad
   * to the gather point on the route layer (with a via and a trace on the pad's layer first when
   * the pads are on another layer), and the direction the pair continues in.
   */
  private record End(
      List<Piece> positive,
      List<Piece> negative,
      List<ViaSpot> vias,
      IntPoint gather,
      int dx,
      int dy,
      int positiveSide,
      double cost) {

    Piece routePositive() {
      return positive.get(positive.size() - 1);
    }

    Piece routeNegative() {
      return negative.get(negative.size() - 1);
    }
  }

  /** The members' corners from two start points to the coupled spacing, and their gather point. */
  private record Escape(IntPoint[] positive, IntPoint[] negative, IntPoint gather, int side) {}

  /** What one pair connection routes with on one layer. */
  private record Geometry(
      int layer,
      int halfWidth,
      int width,
      int spacing,
      int virtualHalf,
      int padding,
      int allowance,
      int netP,
      int netN,
      int classP,
      int classN,
      NetClass netClassP) {}

  private static String routeConnection(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      DifferentialPair pair,
      ConnectionPlan plan,
      List<Integer> itemIds) {
    int netP = pair.positiveNet().netNumber;
    int netN = pair.negativeNet().netNumber;
    NetClass classP = board.rules.nets.get(netP).getNetClass();
    NetClass classN = board.rules.nets.get(netN).getNetClass();
    String lastFailure = "no signal layer the router and both net classes allow";
    List<String> reasons = new ArrayList<>();
    int searches = 0;
    for (int layer : routeLayers(board, settings, classP, classN, plan)) {
      int halfWidth = classP.getTraceHalfWidth(layer);
      if (classN.getTraceHalfWidth(layer) != halfWidth) {
        reasons.add("layer " + layer + ": the two members have different trace widths");
        continue;
      }
      int width = 2 * halfWidth;
      int clearance =
          board.rules.clearanceMatrix.getValue(
              classP.getTraceClearanceClass(), classN.getTraceClearanceClass(), layer, false);
      int gap = (int) Math.round(pair.coupledSpacing());
      if (gap <= 0 && settings.autorouter != null && settings.autorouter.pairGapUm != null) {
        gap = umToBoard(board, settings.autorouter.pairGapUm);
      }
      gap = Math.max(gap, clearance);
      Geometry g =
          new Geometry(
              layer,
              halfWidth,
              width,
              width + gap, // centre to centre of the two members
              (2 * width + gap + 1) / 2,
              (2 * width + gap + 1) / 2
                  + halfWidth
                  + board.rules.clearanceMatrix.getValue(
                      classP.getTraceClearanceClass(),
                      classP.getTraceClearanceClass(),
                      layer,
                      false),
              ExitStubs.copperAllowance(board, settings),
              netP,
              netN,
              classP.getTraceClearanceClass(),
              classN.getTraceClearanceClass(),
              classP);
      List<End> endsA =
          endCandidates(board, plan.firstP(), plan.firstN(), plan.secondP(), plan.secondN(), g);
      List<End> endsB =
          endCandidates(board, plan.secondP(), plan.secondN(), plan.firstP(), plan.firstN(), g);
      if (endsA.isEmpty() || endsB.isEmpty()) {
        reasons.add(
            "layer "
                + layer
                + ": no clear way out of the pads at "
                + (endsA.isEmpty()
                    ? plan.firstP().componentName()
                    : plan.secondP().componentName()));
        continue;
      }
      List<End[]> combinations = new ArrayList<>();
      boolean polaritySwap = false;
      for (End a : endsA) {
        for (End b : endsB) {
          if (a.positiveSide() != -b.positiveSide()) {
            polaritySwap = true;
            continue;
          }
          combinations.add(new End[] {a, b});
        }
      }
      if (combinations.isEmpty() && polaritySwap) {
        reasons.add("layer " + layer + ": the members would have to cross (polarity swap)");
      }
      combinations.sort(Comparator.comparingDouble(c -> c[0].cost() + c[1].cost()));
      for (End[] c : combinations) {
        if (searches++ >= MAX_SEARCHES) {
          reasons.add("search limit of " + MAX_SEARCHES + " tries reached");
          return "left to the router: " + summary(reasons);
        }
        String outcome = tryRoute(board, settings, thread, pair, plan, g, c[0], c[1], itemIds);
        if (outcome.startsWith("routed")) {
          return outcome;
        }
        if (!reasons.contains("layer " + layer + ": " + outcome)) {
          reasons.add("layer " + layer + ": " + outcome);
        }
        if (thread != null && thread.isStopRequested()) {
          return "left to the router: " + summary(reasons);
        }
      }
    }
    return "left to the router: " + (reasons.isEmpty() ? lastFailure : summary(reasons));
  }

  private static void debug(String message) {
    if (System.getProperty("pairs.debug") != null) {
      FRLogger.info("PAIRDBG " + message);
    }
  }

  private static String summary(List<String> reasons) {
    int shown = Math.min(3, reasons.size());
    String text = String.join("; ", reasons.subList(0, shown));
    return reasons.size() > shown ? text + "; and " + (reasons.size() - shown) + " more" : text;
  }

  /**
   * The layers to try for the coupled run, fewest vias first: a layer all four pads are on needs
   * none, a layer one end's pads are not on needs a via pair at that end. Only signal layers the
   * router may use and both members' net classes allow, as the maze search would allow them.
   */
  private static List<Integer> routeLayers(
      RoutingBoard board,
      RouterSettings settings,
      NetClass classP,
      NetClass classN,
      ConnectionPlan plan) {
    int count = board.getLayerCount();
    List<Integer> layers = new ArrayList<>();
    for (int layer = 0; layer < count; layer++) {
      if (board.layerStructure.layers[layer].isSignal
          && settings.getLayerActive(layer)
          && classP.isActiveRoutingLayer(layer)
          && classN.isActiveRoutingLayer(layer)) {
        layers.add(layer);
      }
    }
    layers.sort(
        Comparator.<Integer>comparingInt(
                layer ->
                    (onLayer(plan.firstP(), plan.firstN(), layer) ? 0 : 1)
                        + (onLayer(plan.secondP(), plan.secondN(), layer) ? 0 : 1))
            .thenComparingInt(layer -> layer == 0 ? 0 : layer == count - 1 ? 1 : 2 + layer));
    return layers;
  }

  private static boolean onLayer(Pin p, Pin n, int layer) {
    return p.isOnLayer(layer) && n.isOnLayer(layer);
  }

  private static int umToBoard(RoutingBoard board, double um) {
    int resolution = Math.max(1, board.communication.resolution);
    return (int) Math.round(Unit.scale(um * resolution, Unit.UM, board.communication.unit));
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * The ways the two pads of one end can leave for the route layer, best first: straight out of the
   * pads along an axis when the pads are on that layer, otherwise through a via pair beside the
   * pads. The members keep their order across the axis; both end on the coupled spacing at the same
   * distance from the pads.
   */
  private static List<End> endCandidates(
      RoutingBoard board, Pin pinP, Pin pinN, Pin otherP, Pin otherN, Geometry g) {
    FloatPoint towardOther = middle(otherP, otherN);
    FloatPoint here = middle(pinP, pinN);
    boolean direct = onLayer(pinP, pinN, g.layer());
    ViaInfo via = null;
    int padLayer = pinP.firstLayer();
    if (!direct) {
      if (pinP.firstLayer() != pinP.lastLayer()
          || pinN.firstLayer() != pinN.lastLayer()
          || pinN.firstLayer() != padLayer) {
        return List.of();
      }
      via = viaFor(g.netClassP(), padLayer, g.layer());
      if (via == null) {
        return List.of();
      }
    }
    List<End> found = new ArrayList<>();
    for (int[] d : DIRECTIONS) {
      // The shortest straight run out of the pads that leaves room for the 45 degree legs.
      for (int extra : new int[] {0, 2 * g.width(), 6 * g.width()}) {
        End end =
            direct
                ? directEnd(board, pinP, pinN, g, d, extra)
                : viaEnd(board, pinP, pinN, g, d, via, padLayer, extra);
        if (end != null) {
          found.add(end);
          break;
        }
      }
    }
    // Prefer the shortest breakout, matched members, an axis that points at the other end.
    List<End> ranked = new ArrayList<>();
    for (End end : found) {
      double toward = (towardOther.x - here.x) * end.dx() + (towardOther.y - here.y) * end.dy();
      ranked.add(
          new End(
              end.positive(),
              end.negative(),
              end.vias(),
              end.gather(),
              end.dx(),
              end.dy(),
              end.positiveSide(),
              end.cost() + (toward < 0 ? 2.0 * Math.abs(toward) : 0)));
    }
    ranked.sort(Comparator.comparingDouble(End::cost));
    return ranked.size() > END_CANDIDATES ? ranked.subList(0, END_CANDIDATES) : ranked;
  }

  private static End directEnd(
      RoutingBoard board, Pin pinP, Pin pinN, Geometry g, int[] d, int extra) {
    int dx = d[0];
    int dy = d[1];
    IntPoint cP = centre(pinP);
    IntPoint cN = centre(pinN);
    Escape e =
        escape(
            cP,
            cN,
            forwardExtent(pinP, dx, dy),
            forwardExtent(pinN, dx, dy),
            dx,
            dy,
            g.spacing(),
            g.width(),
            extra);
    if (e == null || !clear(board, g, e, g.layer(), dx, dy)) {
      return null;
    }
    double lengthP = pathLength(e.positive());
    double lengthN = pathLength(e.negative());
    return new End(
        List.of(new Piece(g.layer(), e.positive())),
        List.of(new Piece(g.layer(), e.negative())),
        List.of(),
        e.gather(),
        dx,
        dy,
        e.side(),
        lengthP + lengthN + 4 * Math.abs(lengthP - lengthN));
  }

  /**
   * Pads on another layer than the route layer: a trace on the pad layer from each pad to a via at
   * the via spacing, then on the route layer from each via out to the coupled spacing. The route
   * leaves the vias along the axis of the pad breakout or back along it.
   */
  private static End viaEnd(
      RoutingBoard board,
      Pin pinP,
      Pin pinN,
      Geometry g,
      int[] d,
      ViaInfo via,
      int padLayer,
      int extra) {
    int dx = d[0];
    int dy = d[1];
    int radius = viaRadius(via, padLayer, g.layer());
    int viaClass = via.getClearanceClassIndex();
    int viaSpacing =
        Math.max(
            g.spacing(),
            2 * radius + board.rules.clearanceMatrix.getValue(viaClass, viaClass, padLayer, false));
    Escape toVias =
        escape(
            centre(pinP),
            centre(pinN),
            forwardExtent(pinP, dx, dy),
            forwardExtent(pinN, dx, dy),
            dx,
            dy,
            viaSpacing,
            g.width(),
            extra);
    if (toVias == null) {
      return null;
    }
    IntPoint viaP = toVias.positive()[toVias.positive().length - 1];
    IntPoint viaN = toVias.negative()[toVias.negative().length - 1];
    if (!clearPath(board, toVias.positive(), padLayer, g.halfWidth(), g.netP(), g.classP(), g)
        || !clearPath(board, toVias.negative(), padLayer, g.halfWidth(), g.netN(), g.classN(), g)
        || !clearVia(board, via, viaP, g.netP(), g)
        || !clearVia(board, via, viaN, g.netN(), g)) {
      return null;
    }
    End best = null;
    for (int sign : new int[] {1, -1}) {
      int rx = sign * dx;
      int ry = sign * dy;
      Escape onRoute = escape(viaP, viaN, radius, radius, rx, ry, g.spacing(), g.width(), extra);
      if (onRoute == null || !clear(board, g, onRoute, g.layer(), rx, ry)) {
        continue;
      }
      double lengthP = pathLength(toVias.positive()) + pathLength(onRoute.positive());
      double lengthN = pathLength(toVias.negative()) + pathLength(onRoute.negative());
      double cost =
          lengthP + lengthN + 4 * Math.abs(lengthP - lengthN) + VIA_PAIR_COST * g.spacing();
      End end =
          new End(
              List.of(
                  new Piece(padLayer, toVias.positive()), new Piece(g.layer(), onRoute.positive())),
              List.of(
                  new Piece(padLayer, toVias.negative()), new Piece(g.layer(), onRoute.negative())),
              List.of(new ViaSpot(viaP, via, g.netP()), new ViaSpot(viaN, via, g.netN())),
              onRoute.gather(),
              rx,
              ry,
              onRoute.side(),
              cost);
      if (best == null || end.cost() < best.cost()) {
        best = end;
      }
    }
    return best;
  }

  /** The via of {@code netClass} that spans both layers, the least wide copper first. */
  private static ViaInfo viaFor(NetClass netClass, int layerA, int layerB) {
    ViaRule rule = netClass.getViaRule();
    if (rule == null) {
      return null;
    }
    int from = Math.min(layerA, layerB);
    int to = Math.max(layerA, layerB);
    ViaInfo best = null;
    for (int i = 0; i < rule.viaCount(); i++) {
      ViaInfo info = rule.getVia(i);
      Padstack padstack = info.getPadstack();
      if (padstack.fromLayer() <= from && padstack.toLayer() >= to) {
        if (best == null || viaRadius(info, from, to) < viaRadius(best, from, to)) {
          best = info;
        }
      }
    }
    return best;
  }

  private static int viaRadius(ViaInfo via, int layerA, int layerB) {
    double widest = 0;
    for (int layer = Math.min(layerA, layerB); layer <= Math.max(layerA, layerB); layer++) {
      var shape = via.getPadstack().getShape(layer);
      if (shape != null) {
        widest = Math.max(widest, shape.maxWidth());
      }
    }
    return (int) Math.ceil(widest / 2);
  }

  /**
   * Both members' corners from the two start points (a pad or via each), leaving along the axis
   * (dx, dy). The members keep their order across the axis, end on the coupled spacing at the same
   * distance forward, and reach it with 45 degree legs after a straight run clear of the pads.
   */
  private static Escape escape(
      IntPoint cP,
      IntPoint cN,
      int extentP,
      int extentN,
      int dx,
      int dy,
      int spacing,
      int width,
      int extra) {
    double k = unit(dx, dy);
    int nx = -dy;
    int ny = dx;
    double fP = (dx * cP.x + dy * cP.y) * k;
    double qP = (nx * cP.x + ny * cP.y) * k;
    double fN = (dx * cN.x + dy * cN.y) * k;
    double qN = (nx * cN.x + ny * cN.y) * k;
    if (Math.abs(qP - qN) < 1) {
      return null; // the pads lie on the axis, one behind the other
    }
    double qMid = (qP + qN) / 2;
    double half = spacing / 2.0;
    double qPTarget;
    double qNTarget;
    if (qP < qN) {
      qPTarget = qMid - half;
      qNTarget = qPTarget + spacing;
    } else {
      qNTarget = qMid - half;
      qPTarget = qNTarget + spacing;
    }
    double legP = Math.abs(qPTarget - qP);
    double legN = Math.abs(qNTarget - qN);
    double forward = Math.max(fP + extentP + width + legP, fN + extentN + width + legN) + extra;
    IntPoint[] pathP = member(cP, dx, dy, k, fP, qP, forward, qPTarget, legP);
    IntPoint[] pathN = member(cN, dx, dy, k, fN, qN, forward, qNTarget, legN);
    IntPoint gather = at(dx, dy, k, forward, (qPTarget + qNTarget) / 2);
    return new Escape(pathP, pathN, gather, qPTarget > qNTarget ? 1 : -1);
  }

  /** The length of one step along (dx, dy) in units of the axis: 1/sqrt(2) for a diagonal. */
  private static double unit(int dx, int dy) {
    return dx != 0 && dy != 0 ? Math.sqrt(0.5) : 1.0;
  }

  private static IntPoint tipAt(IntPoint gather, int dx, int dy, int length) {
    double k = unit(dx, dy);
    return new IntPoint(
        (int) Math.round(gather.x + dx * k * length), (int) Math.round(gather.y + dy * k * length));
  }

  /**
   * Whether both members' corners and the stub at the gather point are clear on the route layer.
   */
  private static boolean clear(
      RoutingBoard board, Geometry g, Escape e, int layer, int dx, int dy) {
    IntPoint start = tipAt(e.gather(), dx, dy, g.padding());
    IntPoint tip = tipAt(e.gather(), dx, dy, g.padding() + g.virtualHalf());
    if (!clearPath(board, e.positive(), layer, g.halfWidth(), g.netP(), g.classP(), g)) {
      debug("P breakout blocked on layer " + layer + " " + java.util.Arrays.toString(e.positive()));
      return false;
    }
    if (!clearPath(board, e.negative(), layer, g.halfWidth(), g.netN(), g.classN(), g)) {
      debug("N breakout blocked on layer " + layer + " " + java.util.Arrays.toString(e.negative()));
      return false;
    }
    double stub =
        ExitStubs.pathSlack(
            board,
            new Point[] {start, tip},
            layer,
            g.virtualHalf(),
            g.netP(),
            g.classP(),
            g.allowance(),
            ownPads(board, g));
    if (stub < 0) {
      debug("stub blocked on layer " + layer + " " + start + " -> " + tip + " slack " + stub);
    }
    return stub >= 0;
  }

  /** The pads of the pair itself, which the wide virtual stub need not keep clear of. */
  private static Set<Item> ownPads(RoutingBoard board, Geometry g) {
    Set<Item> pads = new java.util.HashSet<>();
    for (int net : new int[] {g.netP(), g.netN()}) {
      for (Item item : board.getConnectableItems(net)) {
        if (item instanceof Pin) {
          pads.add(item);
        }
      }
    }
    return pads;
  }

  private static boolean clearPath(
      RoutingBoard board,
      Point[] corners,
      int layer,
      int halfWidth,
      int net,
      int traceClass,
      Geometry g) {
    // The pads next to the pad a member starts from may already be closer than the rules allow; the
    // board came with that, so they do not count against the trace leaving the pad.
    Set<Item> ignored =
        ExitStubs.obstaclesTooClose(
            board, corners[0].toFloat().round(), layer, halfWidth, net, traceClass, g.allowance());
    double slack =
        ExitStubs.pathSlack(
            board, corners, layer, halfWidth, net, traceClass, g.allowance(), ignored);
    if (slack < 0) {
      debug(
          "  slack "
              + slack
              + " (half width "
              + halfWidth
              + ", "
              + ignored.size()
              + " pad(s) next to the start ignored)");
    }
    return slack >= 0;
  }

  private static boolean clearVia(
      RoutingBoard board, ViaInfo via, IntPoint at, int net, Geometry g) {
    Padstack padstack = via.getPadstack();
    int[] holder = new int[1];
    for (int layer = padstack.fromLayer(); layer <= padstack.toLayer(); layer++) {
      var shape = padstack.getShape(layer);
      if (shape == null) {
        continue;
      }
      double slack =
          ExitStubs.slack(
              board,
              at,
              at,
              layer,
              (int) Math.ceil(shape.maxWidth() / 2),
              net,
              via.getClearanceClassIndex(),
              g.allowance(),
              holder);
      if (slack < 0) {
        return false;
      }
    }
    return true;
  }

  private static IntPoint[] member(
      IntPoint centre,
      int dx,
      int dy,
      double k,
      double f,
      double q,
      double forward,
      double qTarget,
      double leg) {
    List<IntPoint> points = new ArrayList<>();
    points.add(centre);
    double straight = forward - f - leg;
    if (leg > 0.5 && straight > 0.5) {
      points.add(at(dx, dy, k, f + straight, q));
    }
    points.add(at(dx, dy, k, forward, qTarget));
    return points.toArray(new IntPoint[0]);
  }

  /** The point {@code f} forward and {@code q} to the left of the origin, along (dx, dy). */
  private static IntPoint at(int dx, int dy, double k, double f, double q) {
    int nx = -dy;
    int ny = dx;
    return new IntPoint(
        (int) Math.round((dx * f + nx * q) * k), (int) Math.round((dy * f + ny * q) * k));
  }

  /** How far the pad reaches forward beyond its centre along (dx, dy). */
  private static int forwardExtent(Pin pin, int dx, int dy) {
    IntBox box = pin.boundingBox();
    IntPoint c = centre(pin);
    double k = unit(dx, dy);
    double centreF = (dx * c.x + dy * c.y) * k;
    double best = 0;
    for (int x : new int[] {box.ll.x, box.ur.x}) {
      for (int y : new int[] {box.ll.y, box.ur.y}) {
        best = Math.max(best, (dx * x + dy * y) * k - centreF);
      }
    }
    return (int) Math.ceil(best);
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
      Geometry g,
      End a,
      End b,
      List<Integer> itemIds) {
    List<Item> placed = new ArrayList<>();
    try {
      return tryRoute(board, settings, thread, plan, g, a, b, itemIds, placed);
    } catch (RuntimeException e) {
      // Whatever went wrong, the pair goes back to the ordinary router and the board is as before.
      removeAll(board, placed);
      board.clearTransientAutorouteState();
      FRLogger.warn("Pair " + pair.baseName() + ": " + e);
      return "an error stopped the attempt (" + e.getClass().getSimpleName() + ")";
    }
  }

  private static String tryRoute(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      ConnectionPlan plan,
      Geometry g,
      End a,
      End b,
      List<Integer> itemIds,
      List<Item> placed) {
    int layer = g.layer();
    // The breakouts and via pairs of both ends go in first. They are copper the pair will have, and
    // they keep the wide virtual trace out of the space between its gather points and the pads, so
    // it cannot reach a gather point from the wrong side.
    if (!placeEnd(board, a, g, placed) || !placeEnd(board, b, g, placed)) {
      removeAll(board, placed);
      return "the breakouts cannot be placed";
    }
    List<IntPoint> centreLine;
    List<Item> stubs = new ArrayList<>();
    try {
      PolylineTrace stubA = stub(board, a, g);
      PolylineTrace stubB = stub(board, b, g);
      if (stubA != null) {
        stubs.add(stubA);
      }
      if (stubB != null) {
        stubs.add(stubB);
      }
      if (stubA == null || stubB == null) {
        removeAll(board, placed);
        return "the gather point cannot be placed";
      }
      // The stubs carry the hidden net, so that no pad of either member counts as a destination.
      AutorouteControl control = new AutorouteControl(board, g.netP(), settings);
      control.netNumber = Nets.hidden_net_number;
      control.traceClearanceClassIndex = g.classP();
      control.viasAllowed = false;
      control.ripupAllowed = false;
      control.removeUnconnectedVias = false;
      for (int i = 0; i < control.layerCount; i++) {
        control.layerActive[i] = control.layerActive[i] && i == layer;
      }
      int compensation = control.compensatedTraceHalfWidth[layer] - control.traceHalfWidth[layer];
      control.traceHalfWidth[layer] = g.virtualHalf();
      control.compensatedTraceHalfWidth[layer] = g.virtualHalf() + compensation;
      AutorouteEngine engine =
          board.initAutoroute(
              Nets.hidden_net_number, g.classP(), thread, TimeLimit.ofSteps(SEARCH_STEPS), false);
      List<FoundConnectionLocator.Segment> segments =
          engine.locateConnection(Set.of(stubA), Set.of(stubB), control);
      if (segments == null || segments.isEmpty()) {
        removeAll(board, placed);
        return "no free corridor for the pair on layer " + layer;
      }
      centreLine = centreLine(a, b, g, segments, layer);
      if (centreLine == null) {
        removeAll(board, placed);
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

    double half = g.spacing() / 2.0;
    List<IntPoint> positiveLine = coupled(centreLine, half * a.positiveSide());
    List<IntPoint> negativeLine = coupled(centreLine, -half * a.positiveSide());
    if (positiveLine == null || negativeLine == null) {
      removeAll(board, placed);
      return "a bend of the corridor is too tight to couple the members";
    }
    Piece positiveMiddle = middle(a.routePositive(), b.routePositive(), positiveLine);
    Piece negativeMiddle = middle(a.routeNegative(), b.routeNegative(), negativeLine);
    boolean inserted =
        insertPieces(board, List.of(positiveMiddle), g.halfWidth(), g.netP(), g.classP(), placed)
            && insertPieces(
                board, List.of(negativeMiddle), g.halfWidth(), g.netN(), g.classN(), placed);
    if (inserted) {
      String violation = firstViolation(placed);
      if (violation != null) {
        removeAll(board, placed);
        return "the coupled traces would violate a clearance (" + violation + ")";
      }
    }
    boolean ok =
        inserted
            && plan.firstP().getConnectedSet(g.netP()).contains(plan.secondP())
            && plan.firstN().getConnectedSet(g.netN()).contains(plan.secondN());
    if (!ok) {
      removeAll(board, placed);
      return "the coupled traces did not connect the pads";
    }
    for (Item item : placed) {
      // Fixed only now: a fixed item cannot be removed again, which a failed attempt needs.
      item.setFixedState(PLACED_STATE);
      itemIds.add(item.getId());
    }
    double lengthP = memberLength(a.positive(), b.positive(), positiveMiddle);
    double lengthN = memberLength(a.negative(), b.negative(), negativeMiddle);
    int viaPairs = (a.vias().size() + b.vias().size()) / 2;
    return String.format(
        Locale.ROOT,
        "routed on layer %d%s, lengths %.3f and %.3f mm, skew %.3f mm",
        layer,
        viaPairs == 0 ? "" : " with " + viaPairs + " via pair(s)",
        toMm(board, lengthP),
        toMm(board, lengthN),
        toMm(board, Math.abs(lengthP - lengthN)));
  }

  /** Inserts the breakout traces and via pair of one end. */
  private static boolean placeEnd(RoutingBoard board, End end, Geometry g, List<Item> placed) {
    boolean ok = true;
    for (ViaSpot spot : end.vias()) {
      Via via =
          board.insertVia(
              spot.info().getPadstack(),
              spot.at(),
              new int[] {spot.net()},
              spot.info().getClearanceClassIndex(),
              FixedState.UNFIXED,
              true);
      if (via == null) {
        ok = false;
      } else {
        placed.add(via);
      }
    }
    ok &= insertPieces(board, end.positive(), g.halfWidth(), g.netP(), g.classP(), placed);
    ok &= insertPieces(board, end.negative(), g.halfWidth(), g.netN(), g.classN(), placed);
    return ok;
  }

  private static void removeAll(RoutingBoard board, List<Item> items) {
    for (Item item : items) {
      if (item.isOnTheBoard()) {
        board.removeItem(item);
      }
    }
    items.clear();
  }

  /**
   * Describes the first clearance violation of the new copper, or null: the exact check after the
   * quick one, which catches the members of the pair against each other and vias against traces.
   */
  private static String firstViolation(List<Item> placed) {
    for (Item item : placed) {
      for (var v : item.clearanceViolations()) {
        return item.getClass().getSimpleName()
            + " against "
            + v.secondItem.getClass().getSimpleName()
            + " on layer "
            + v.layer;
      }
    }
    return null;
  }

  /** A member's length over all its traces. */
  private static double memberLength(List<Piece> first, List<Piece> second, Piece middle) {
    return totalLength(first) + totalLength(second) + pathLength(middle.corners());
  }

  /**
   * The route-layer trace between the two gather side points of a member: the coupled path with its
   * first and last corner replaced by those points.
   */
  private static Piece middle(Piece fromA, Piece fromB, List<IntPoint> coupled) {
    List<IntPoint> all = new ArrayList<>();
    all.add(fromA.corners()[fromA.corners().length - 1]);
    for (int i = 1; i + 1 < coupled.size(); i++) {
      all.add(coupled.get(i));
    }
    all.add(fromB.corners()[fromB.corners().length - 1]);
    return new Piece(fromA.layer(), simplify(all).toArray(new IntPoint[0]));
  }

  private static <T> List<T> concat(List<T> first, List<T> second) {
    List<T> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  private static double totalLength(List<Piece> pieces) {
    double length = 0;
    for (Piece piece : pieces) {
      length += pathLength(piece.corners());
    }
    return length;
  }

  private static boolean insertPieces(
      RoutingBoard board,
      List<Piece> pieces,
      int halfWidth,
      int net,
      int traceClass,
      List<Item> placed) {
    boolean ok = true;
    for (Piece piece : pieces) {
      PolylineTrace trace =
          board.insertTraceWithoutCleaning(
              new Polyline(piece.corners()),
              piece.layer(),
              halfWidth,
              new int[] {net},
              traceClass,
              FixedState.UNFIXED);
      if (trace == null) {
        ok = false;
      } else {
        placed.add(trace);
      }
    }
    return ok;
  }

  private static double toMm(RoutingBoard board, double boardUnits) {
    return Unit.scale(
        boardUnits / Math.max(1, board.communication.resolution),
        board.communication.unit,
        Unit.MM);
  }

  private static PolylineTrace stub(RoutingBoard board, End end, Geometry g) {
    return board.insertTraceWithoutCleaning(
        new Polyline(new Point[] {start(end, g), tip(end, g)}),
        g.layer(),
        g.virtualHalf(),
        new int[] {Nets.hidden_net_number},
        g.classP(),
        FixedState.UNFIXED);
  }

  /** The virtual path from gather point to gather point, simplified. */
  private static List<IntPoint> centreLine(
      End a, End b, Geometry g, List<FoundConnectionLocator.Segment> segments, int layer) {
    List<IntPoint> route = new ArrayList<>();
    for (FoundConnectionLocator.Segment segment : segments) {
      if (segment.layer() != layer) {
        return null;
      }
      List<IntPoint> corners = new ArrayList<>(List.of(segment.corners()));
      IntPoint reference = route.isEmpty() ? a.gather() : route.get(route.size() - 1);
      if (corners.get(0).toFloat().distance(reference.toFloat())
          > corners.get(corners.size() - 1).toFloat().distance(reference.toFloat())) {
        java.util.Collections.reverse(corners);
      }
      route.addAll(corners);
    }
    // The route may run along a stub after entering it; the pair leaves by the first corner there.
    trimOnStub(route, a, g, true);
    trimOnStub(route, b, g, false);
    List<IntPoint> line = new ArrayList<>();
    line.add(a.gather());
    line.add(start(a, g));
    line.addAll(route);
    line.add(start(b, g));
    line.add(b.gather());
    return simplify(line);
  }

  /** Drops the corners of the route after the first one on the stub of the end. */
  private static void trimOnStub(List<IntPoint> route, End end, Geometry g, boolean atFront) {
    IntPoint start = start(end, g);
    IntPoint tip = tip(end, g);
    while (route.size() >= 2) {
      IntPoint last = atFront ? route.get(0) : route.get(route.size() - 1);
      IntPoint next = atFront ? route.get(1) : route.get(route.size() - 2);
      if (onSegment(last, start, tip) && onSegment(next, start, tip)) {
        route.remove(atFront ? 0 : route.size() - 1);
      } else {
        break;
      }
    }
  }

  /**
   * Where the virtual stub of an end starts: past the gather point by a half width, the width of a
   * member and a clearance, so that the stub's rear end keeps its distance from the breakouts that
   * end at the gather point.
   */
  private static IntPoint start(End end, Geometry g) {
    return tipAt(end.gather(), end.dx(), end.dy(), g.padding());
  }

  private static IntPoint tip(End end, Geometry g) {
    return tipAt(end.gather(), end.dx(), end.dy(), g.padding() + g.virtualHalf());
  }

  private static boolean onSegment(IntPoint p, IntPoint a, IntPoint b) {
    double dx = b.x - a.x;
    double dy = b.y - a.y;
    double lengthSquare = dx * dx + dy * dy;
    if (lengthSquare == 0) {
      return p.equals(a);
    }
    double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquare;
    if (t < 0 || t > 1) {
      return false;
    }
    double nearestX = a.x + t * dx;
    double nearestY = a.y + t * dy;
    return Math.hypot(p.x - nearestX, p.y - nearestY) <= 2;
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
   * right), with mitred corners. A segment shorter than the offset needs at a bend turns around on
   * the inner side; such a segment is dropped and its neighbours meet at their crossing, as the
   * offset of a polyline does. Returns null when the line cannot be offset that way.
   */
  private static List<IntPoint> coupled(List<IntPoint> line, double offset) {
    int segments = line.size() - 1;
    if (segments < 1) {
      return null;
    }
    double[][] direction = new double[segments][];
    double[][] origin = new double[segments][];
    for (int i = 0; i < segments; i++) {
      direction[i] = unit(line.get(i), line.get(i + 1));
      origin[i] =
          new double[] {
            line.get(i).x - direction[i][1] * offset, line.get(i).y + direction[i][0] * offset
          };
    }
    List<Integer> active = new ArrayList<>();
    for (int i = 0; i < segments; i++) {
      active.add(i);
    }
    List<double[]> corners;
    for (; ; ) {
      corners = new ArrayList<>();
      int first = active.get(0);
      int last = active.get(active.size() - 1);
      corners.add(origin[first]);
      for (int k = 0; k + 1 < active.size(); k++) {
        double[] crossing =
            crossing(
                origin[active.get(k)],
                direction[active.get(k)],
                origin[active.get(k + 1)],
                direction[active.get(k + 1)]);
        if (crossing == null) {
          return null;
        }
        corners.add(crossing);
      }
      IntPoint end = line.get(line.size() - 1);
      corners.add(
          new double[] {end.x - direction[last][1] * offset, end.y + direction[last][0] * offset});
      int inverted = -1;
      for (int k = 0; k < active.size(); k++) {
        double[] d = direction[active.get(k)];
        double along =
            (corners.get(k + 1)[0] - corners.get(k)[0]) * d[0]
                + (corners.get(k + 1)[1] - corners.get(k)[1]) * d[1];
        if (along < 1) {
          inverted = k;
          break;
        }
      }
      if (inverted < 0) {
        break;
      }
      if (inverted == 0 || inverted == active.size() - 1) {
        return null;
      }
      active.remove(inverted);
    }
    List<IntPoint> result = new ArrayList<>();
    for (double[] corner : corners) {
      result.add(new IntPoint((int) Math.round(corner[0]), (int) Math.round(corner[1])));
    }
    return result;
  }

  /** Where the line through p1 along d1 meets the line through p2 along d2, or null if parallel. */
  private static double[] crossing(double[] p1, double[] d1, double[] p2, double[] d2) {
    double denominator = d1[0] * d2[1] - d1[1] * d2[0];
    if (Math.abs(denominator) < 1e-9) {
      return null;
    }
    double t = ((p2[0] - p1[0]) * d2[1] - (p2[1] - p1[1]) * d2[0]) / denominator;
    return new double[] {p1[0] + t * d1[0], p1[1] + t * d1[1]};
  }

  private static double[] unit(IntPoint from, IntPoint to) {
    double dx = to.x - from.x;
    double dy = to.y - from.y;
    double length = Math.hypot(dx, dy);
    return new double[] {dx / length, dy / length};
  }
}
