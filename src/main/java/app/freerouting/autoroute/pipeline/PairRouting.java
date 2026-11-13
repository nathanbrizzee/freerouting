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

  /**
   * Most searches one pair connection may use over all its layers, gather points and the two search
   * widths (see {@code tryRoute}).
   */
  private static final int MAX_SEARCHES = 24;

  /** Skew a routed pair may keep when the board gives no rule and no setting is made. */
  private static final double DEFAULT_MAX_SKEW_UM = 100;

  /** Cost of a via pair in coupled spacings of breakout length. */
  private static final double VIA_PAIR_COST = 40;

  /**
   * A coupled route up to this many times the direct (45 degree) distance between the two ends is
   * taken at once; a longer one is kept as a fallback while the other layers are tried, and the
   * shortest is used.
   */
  private static final double GOOD_DETOUR = 1.5;

  /** Default of {@code pair_max_detour}: the longest coupled route, in direct distances. */
  private static final double DEFAULT_MAX_DETOUR = 2.5;

  /** What a via pair adds to a route's length when layers are compared, in coupled spacings. */
  private static final double VIA_PAIR_LENGTH_PENALTY = 10;

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
    List<Item> rings = padRingKeepouts(board);
    try {
      attempted = routePairs(board, settings, thread, pairs, itemIds, lines);
    } finally {
      removeAll(board, rings);
      board.clearTransientAutorouteState();
    }
    String returnLine = placeReturnVias(board, settings, pairs, itemIds);
    for (String line : lines) {
      if (line.contains("): routed on layer")) {
        connections++;
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
    if (returnLine != null) {
      lines.add(returnLine);
    }
    return lines;
  }

  /**
   * Routes the open connections of one pair, as {@link #apply} does on the empty board, for the
   * re-route of a pair as a unit ({@link PairReroute}). Returns the lines to log; every one starts
   * with {@code "Pair "} and a routed connection's contains {@code "): routed on layer"}.
   */
  static List<String> routeOne(
      RouterSettings settings,
      RoutingBoard board,
      StoppableThread thread,
      DifferentialPair pair,
      List<Integer> itemIds) {
    List<String> lines = new ArrayList<>();
    List<Item> rings = padRingKeepouts(board);
    try {
      routePairs(board, settings, thread, List.of(pair), itemIds, lines);
    } finally {
      removeAll(board, rings);
      board.clearTransientAutorouteState();
    }
    String returnLine = placeReturnVias(board, settings, List.of(pair), itemIds);
    if (returnLine != null) {
      lines.add(returnLine);
    }
    return lines;
  }

  /** Whether the pair has a connection whose two members are both still open. */
  static boolean hasOpenConnection(RoutingBoard board, DifferentialPair pair) {
    return !plan(board, pair, new ArrayList<>()).isEmpty();
  }

  /** Routes every connection of every pair; returns the number of connections tried. */
  private static int routePairs(
      RoutingBoard board,
      RouterSettings settings,
      StoppableThread thread,
      List<DifferentialPair> pairs,
      List<Integer> itemIds,
      List<String> lines) {
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
      boolean replaceFanout =
          settings.autorouter == null
              || settings.autorouter.pairFanout == null
              || !settings.autorouter.pairFanout.equalsIgnoreCase("keep");
      for (ConnectionPlan plan : plans) {
        attempted++;
        debugContext = pair.baseName() + " (" + plan.describe() + ")";
        List<Runnable> restore = replaceFanout ? liftFanout(board, plan) : List.of();
        String outcome = routeConnection(board, settings, thread, pair, plan, itemIds);
        if (outcome.startsWith("routed")) {
          if (!restore.isEmpty()) {
            outcome += ", replacing " + restore.size() + " fanout item(s)";
          }
        } else {
          restore.forEach(Runnable::run);
          board.clearTransientAutorouteState();
        }
        lines.add("Pair " + pair.baseName() + " (" + plan.describe() + "): " + outcome);
      }
    }
    return attempted;
  }

  /** Parts with at least this many pins can have a pad ring a pair must not run inside. */
  private static final int PAD_RING_MIN_PINS = 8;

  /**
   * Temporary wire keepouts over the inside of every pad ring: a part with at least {@link
   * #PAD_RING_MIN_PINS} pins on all four sides and none inside the box of its pin centres shrunk by
   * its largest pad (QFP, QFN without an exposed pad; not a BGA, a two-row header or an SOIC). A
   * pair inside such a ring would run in front of the inner ends of the other pins and close their
   * escapes, so no breakout, corridor or length bump of a pair may enter it. On every signal layer,
   * because escape vias pass through all.
   */
  private static List<Item> padRingKeepouts(RoutingBoard board) {
    TreeMap<Integer, List<Pin>> byComponent = new TreeMap<>();
    for (Pin pin : board.getPins()) {
      byComponent.computeIfAbsent(pin.getComponentId(), k -> new ArrayList<>()).add(pin);
    }
    List<Item> keepouts = new ArrayList<>();
    for (List<Pin> pins : byComponent.values()) {
      if (pins.size() < PAD_RING_MIN_PINS) {
        continue;
      }
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      int pad = 0;
      for (Pin pin : pins) {
        IntPoint c = centre(pin);
        minX = Math.min(minX, c.x);
        minY = Math.min(minY, c.y);
        maxX = Math.max(maxX, c.x);
        maxY = Math.max(maxY, c.y);
        IntBox box = pin.boundingBox();
        pad = Math.max(pad, Math.max(box.ur.x - box.ll.x, box.ur.y - box.ll.y) / 2);
      }
      IntBox inside = new IntBox(minX + pad, minY + pad, maxX - pad, maxY - pad);
      if (inside.ll.x >= inside.ur.x || inside.ll.y >= inside.ur.y) {
        continue;
      }
      // A ring has pins on all four sides and none inside; two-row parts (headers, SOIC,
      // transformers) and area arrays (BGA) are left alone.
      boolean ring = true;
      int[] sides = new int[4];
      for (Pin pin : pins) {
        IntPoint c = centre(pin);
        if (c.x > inside.ll.x && c.x < inside.ur.x && c.y > inside.ll.y && c.y < inside.ur.y) {
          ring = false;
          break;
        }
        // Only pins between the corners count for a side, so a grid's corner pins do not.
        boolean midX = c.x > minX + pad && c.x < maxX - pad;
        boolean midY = c.y > minY + pad && c.y < maxY - pad;
        sides[0] += c.x <= minX + pad && midY ? 1 : 0;
        sides[1] += c.x >= maxX - pad && midY ? 1 : 0;
        sides[2] += c.y <= minY + pad && midX ? 1 : 0;
        sides[3] += c.y >= maxY - pad && midX ? 1 : 0;
      }
      if (!ring || Math.min(Math.min(sides[0], sides[1]), Math.min(sides[2], sides[3])) < 2) {
        continue;
      }
      for (int layer = 0; layer < board.getLayerCount(); layer++) {
        if (board.layerStructure.layers[layer].isSignal) {
          // Unfixed: a fixed item cannot be removed again after the pair stage.
          Item keepout = board.insertWireObstacle(inside, layer, 0, FixedState.UNFIXED);
          if (keepout != null) {
            keepouts.add(keepout);
          }
        }
      }
    }
    if (!keepouts.isEmpty()) {
      board.clearTransientAutorouteState();
    }
    return keepouts;
  }

  /**
   * One line per differential pair whose two members' traces ended on different layers (a pair the
   * pair router left to the ordinary router), naming the layers of each; impedance matching needs
   * both on the same layers. Empty when every pair shares its layers.
   */
  public static List<String> layerSplits(RoutingBoard board) {
    List<String> lines = new ArrayList<>();
    List<DifferentialPair> pairs = new ArrayList<>(board.rules.differentialPairs.all());
    pairs.sort(Comparator.comparing(DifferentialPair::baseName, String.CASE_INSENSITIVE_ORDER));
    for (DifferentialPair pair : pairs) {
      Set<Integer> p = traceLayers(board, pair.positiveNet().netNumber);
      Set<Integer> n = traceLayers(board, pair.negativeNet().netNumber);
      if (!p.isEmpty() && !n.isEmpty() && !p.equals(n)) {
        lines.add(
            "Pair "
                + pair.baseName()
                + ": the members are on different layers ("
                + pair.positiveNet().name
                + " on "
                + layerNames(board, p)
                + ", "
                + pair.negativeNet().name
                + " on "
                + layerNames(board, n)
                + "); impedance matching needs both on the same layers.");
      }
    }
    return lines;
  }

  static Set<Integer> traceLayers(RoutingBoard board, int net) {
    Set<Integer> layers = new java.util.TreeSet<>();
    for (Item item : board.getConnectableItems(net)) {
      if (item instanceof app.freerouting.board.model.items.Trace trace) {
        layers.add(trace.getLayer());
      }
    }
    return layers;
  }

  static String layerNames(RoutingBoard board, Set<Integer> layers) {
    List<String> names = new ArrayList<>();
    for (int layer : layers) {
      names.add(board.layerStructure.layers[layer].name);
    }
    return String.join(", ", names);
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

  /**
   * Lifts the fanout copper already on the four pins of a connection, so the pair can leave the
   * pads itself: per pin, the traces and vias connected to it, when they reach no other pin, are
   * all movable and carry only that net (a fanout stub and its via, or a dead-end route). Copper
   * that is fixed or reaches another pin stays. Returns one action per lifted item that puts it
   * back.
   */
  private static List<Runnable> liftFanout(RoutingBoard board, ConnectionPlan plan) {
    List<Runnable> restore = new ArrayList<>();
    for (Pin pin : new Pin[] {plan.firstP(), plan.firstN(), plan.secondP(), plan.secondN()}) {
      int net = pin.getNetNumber(0);
      List<Item> copper = new ArrayList<>();
      boolean movable = true;
      for (Item item : pin.getConnectedSet(net)) {
        if (item == pin) {
          continue;
        }
        if (!(item instanceof PolylineTrace || item instanceof Via)
            || item.getFixedState() != FixedState.UNFIXED
            || item.netCount() != 1) {
          movable = false;
          break;
        }
        copper.add(item);
      }
      if (!movable || copper.isEmpty()) {
        continue;
      }
      // Deterministic order, vias first, so traces come back onto their vias.
      copper.sort(
          Comparator.<Item>comparingInt(i -> i instanceof Via ? 0 : 1)
              .thenComparingInt(Item::getId));
      for (Item item : copper) {
        restore.add(restorer(board, item));
        board.removeItem(item);
      }
    }
    if (!restore.isEmpty()) {
      board.clearTransientAutorouteState();
    }
    return restore;
  }

  /** An action that inserts a copy of a lifted trace or via. */
  private static Runnable restorer(RoutingBoard board, Item item) {
    int[] nets = item.netNumbers.clone();
    int clearanceClass = item.clearanceClassIndex();
    if (item instanceof Via via) {
      Padstack padstack = via.getPadstack();
      Point centre = via.getCenter();
      boolean attach = via.attachAllowed;
      return () ->
          board.insertVia(padstack, centre, nets, clearanceClass, FixedState.UNFIXED, attach);
    }
    PolylineTrace trace = (PolylineTrace) item;
    Polyline polyline = trace.polyline();
    int layer = trace.getLayer();
    int halfWidth = trace.getHalfWidth();
    return () ->
        board.insertTraceWithoutCleaning(
            polyline, layer, halfWidth, nets, clearanceClass, FixedState.UNFIXED);
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
      NetClass netClassP,
      int skewTarget,
      int tuneTarget) {}

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
    boolean limitReached = false;
    double maxDetour = maxDetour(settings);
    double direct = directDistance(plan);
    // With the limit off the first route found is taken, as before.
    double acceptWithin =
        maxDetour > 0 ? Math.min(GOOD_DETOUR, maxDetour) * direct : Double.POSITIVE_INFINITY;
    Fallback fallback = null;
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
              classP,
              skewTarget(board, settings, pair),
              tuneTarget(board, settings, pair));
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
        boolean found = false;
        // First with the corridor sized for the whole pair; when that gives no good route, again
        // with the narrow trace's corridor, which finds gaps the pair fits through but the wide
        // search rejects. The exact check of the members decides in both cases.
        for (int mode = 0; mode < 2; mode++) {
          boolean narrow = mode == 1;
          if (searches++ >= MAX_SEARCHES) {
            reasons.add("search limit of " + MAX_SEARCHES + " tries reached");
            limitReached = true;
            break;
          }
          double[] score = new double[2];
          String outcome =
              tryRoute(
                  board,
                  settings,
                  thread,
                  pair,
                  plan,
                  g,
                  c[0],
                  c[1],
                  itemIds,
                  narrow,
                  acceptWithin,
                  score);
          if (outcome.startsWith("routed")) {
            return outcome;
          }
          String reason = "layer " + layer + (narrow ? " (narrow search): " : ": ") + outcome;
          if (score[0] > 0) {
            // A route too long to take at once: remember it and try the other layers.
            if (fallback == null || score[0] < fallback.score()) {
              fallback = new Fallback(g, c[0], c[1], narrow, score[0], score[1]);
            }
            found = true;
          }
          if (!reasons.contains(reason)) {
            reasons.add(reason);
          }
          if (thread != null && thread.isStopRequested()) {
            return "left to the router: " + summary(reasons);
          }
        }
        if (found || limitReached) {
          break;
        }
      }
      if (limitReached) {
        break;
      }
    }
    if (fallback != null) {
      if (maxDetour > 0 && fallback.length() > maxDetour * direct) {
        return String.format(
            Locale.ROOT,
            "left to the router: the shortest coupled route is %.1f mm for %.1f mm direct"
                + " (pair_max_detour %.1f)",
            toMm(board, fallback.length()),
            toMm(board, direct),
            maxDetour);
      }
      double[] score = new double[2];
      String outcome =
          tryRoute(
              board,
              settings,
              thread,
              pair,
              plan,
              fallback.geometry(),
              fallback.first(),
              fallback.second(),
              itemIds,
              fallback.narrow(),
              Double.POSITIVE_INFINITY,
              score);
      if (outcome.startsWith("routed")) {
        return outcome
            + String.format(
                Locale.ROOT,
                ", %.1f times the direct distance, the shortest of the layers tried",
                fallback.length() / Math.max(1.0, direct));
      }
      reasons.add(outcome);
    }
    return "left to the router: " + (reasons.isEmpty() ? lastFailure : summary(reasons));
  }

  /** A long coupled route that was found: where to find it again, and its score. */
  private record Fallback(
      Geometry geometry, End first, End second, boolean narrow, double score, double length) {}

  /** The longest coupled route as a multiple of the direct distance; 0 when the check is off. */
  private static double maxDetour(RouterSettings settings) {
    Double ratio = settings.autorouter != null ? settings.autorouter.pairMaxDetour : null;
    if (ratio == null) {
      return DEFAULT_MAX_DETOUR;
    }
    return ratio < 1 ? 0 : ratio;
  }

  /**
   * The shortest a route between the two ends of the connection can be with 45 degree bends, in
   * board units, measured between the middles of the two pad pairs.
   */
  private static double directDistance(ConnectionPlan plan) {
    FloatPoint from = middle(plan.firstP(), plan.firstN());
    FloatPoint to = middle(plan.secondP(), plan.secondN());
    double dx = Math.abs(from.x - to.x);
    double dy = Math.abs(from.y - to.y);
    return Math.max(dx, dy) + (Math.sqrt(2) - 1) * Math.min(dx, dy);
  }

  /** The pair connection the debug lines are about. */
  private static String debugContext = "";

  private static void debug(String message) {
    if (System.getProperty("pairs.debug") != null) {
      FRLogger.info("PAIRDBG " + debugContext + ": " + message);
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

  static int umToBoard(RoutingBoard board, double um) {
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
    IntPoint tip = tipAt(e.gather(), dx, dy, g.padding() + stubLength(g));
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
      debug(
          String.format(
              Locale.ROOT,
              "stub blocked on layer %d %s -> %s, slack %.1f um",
              layer,
              start,
              tip,
              toMm(board, stub) * 1000));
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
          String.format(
              Locale.ROOT,
              "  slack %.1f um (half width %.1f um, %d pad(s) next to the start ignored)",
              toMm(board, slack) * 1000,
              toMm(board, halfWidth) * 1000,
              ignored.size()));
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
      List<Integer> itemIds,
      boolean narrow,
      double acceptWithin,
      double[] score) {
    List<Item> placed = new ArrayList<>();
    try {
      return tryRoute(
          board, settings, thread, plan, g, a, b, itemIds, placed, narrow, acceptWithin, score);
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
      List<Item> placed,
      boolean narrow,
      double acceptWithin,
      double[] score) {
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
      // The free-space rooms of the search are sized by this offset, which the control computed
      // from the narrow trace width. Recomputed for the wide trace, the search keeps out of gaps
      // only one member fits; the narrow search keeps the narrow trace's offset and leaves it to
      // the exact check to refuse a route where the members do not fit.
      if (!narrow) {
        control.updateTraceClearanceOffset(layer, board);
      }
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
        insertPieces(board, List.of(positiveMiddle), g.halfWidth(), g.netP(), g.classP(), placed);
    Item middleP = inserted ? placed.get(placed.size() - 1) : null;
    inserted =
        inserted
            && insertPieces(
                board, List.of(negativeMiddle), g.halfWidth(), g.netN(), g.classN(), placed);
    Item middleN = inserted ? placed.get(placed.size() - 1) : null;
    if (inserted) {
      String violation = firstViolation(board, placed);
      if (violation != null) {
        debug("rejected: " + violation);
        debug("  centre line " + centreLine);
        debug("  P middle " + java.util.Arrays.toString(positiveMiddle.corners()));
        debug("  N middle " + java.util.Arrays.toString(negativeMiddle.corners()));
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
    debug("accepted on layer " + layer + ", centre line " + centreLine);
    double lengthP = memberLength(a.positive(), b.positive(), positiveMiddle);
    double lengthN = memberLength(a.negative(), b.negative(), negativeMiddle);
    String tuning = "";
    if (g.tuneTarget() >= 0 && Math.abs(lengthP - lengthN) > g.tuneTarget()) {
      boolean shorterP = lengthP < lengthN;
      double skewBefore = Math.abs(lengthP - lengthN);
      // How far each end's breakout leaves the shorter member behind; the bumps go nearest the end
      // that does it most (TI SPRACP4A: as close to the mismatched end as possible).
      double sign = shorterP ? -1 : 1;
      double shortAtA = sign * (totalLength(a.positive()) - totalLength(a.negative()));
      double shortAtB = sign * (totalLength(b.positive()) - totalLength(b.negative()));
      boolean fromB = shortAtB > shortAtA && shortAtB > 0;
      double added =
          tuneSkew(
              board,
              g,
              shorterP ? positiveMiddle : negativeMiddle,
              shorterP ? middleP : middleN,
              shorterP ? a.positiveSide() : -a.positiveSide(),
              shorterP ? g.netP() : g.netN(),
              shorterP ? g.classP() : g.classN(),
              skewBefore,
              fromB,
              placed);
      if (added > 0) {
        if (shorterP) {
          lengthP += added;
        } else {
          lengthN += added;
        }
        tuning = String.format(Locale.ROOT, ", tuned from %.3f mm", toMm(board, skewBefore));
      } else {
        tuning = ", not tuned: no room for length bumps beside the shorter member";
      }
    }
    int viaPairs = (a.vias().size() + b.vias().size()) / 2;
    String text =
        String.format(
            Locale.ROOT,
            "on layer %d%s, lengths %.3f and %.3f mm, skew %.3f mm (%s)%s",
            layer,
            viaPairs == 0 ? "" : " with " + viaPairs + " via pair(s)",
            toMm(board, lengthP),
            toMm(board, lengthN),
            toMm(board, Math.abs(lengthP - lengthN)),
            skewTargetText(board, g.skewTarget()),
            tuning);
    double length = Math.max(lengthP, lengthN) + viaPairs * VIA_PAIR_LENGTH_PENALTY * g.spacing();
    if (length > acceptWithin) {
      // Too long to take at once: the board goes back as it was and the caller may try another
      // layer.
      removeAll(board, placed);
      board.clearTransientAutorouteState();
      score[0] = length;
      score[1] = Math.max(lengthP, lengthN);
      return "a long coupled route was found " + text;
    }
    for (Item item : placed) {
      // Fixed only now: a fixed item cannot be removed again, which a failed attempt needs.
      item.setFixedState(PLACED_STATE);
      itemIds.add(item.getId());
    }
    return "routed " + text;
  }

  /**
   * The skew a pair may keep, in board units, or -1 for no tuning: the pair's own rule when the
   * board gives one, else {@code pair_max_skew_um} (negative: off).
   */
  static int skewTarget(RoutingBoard board, RouterSettings settings, DifferentialPair pair) {
    if (pair.maxSkew() > 0) {
      return (int) Math.round(pair.maxSkew());
    }
    Double um = settings.autorouter != null ? settings.autorouter.pairMaxSkewUm : null;
    if (um == null) {
      um = DEFAULT_MAX_SKEW_UM;
    }
    return um < 0 ? -1 : umToBoard(board, um);
  }

  /**
   * The skew above which the pair router adds length bumps, in board units, or -1 for none: the
   * tighter of the pair's own rule and {@code pair_max_skew_um}. A pair's rule looser than the
   * global target is a limit, not a goal: where there is room the members are still matched as
   * closely as the other pairs, and the looser rule only decides what is accepted (the pair
   * re-route's skew check). Leaving the bumps out changed the copper the rest of the board routes
   * around: on a private board with four Ethernet pairs, a 1.27 mm rule left two pairs untuned and
   * the board ended at 3 open where the same board with the bumps reached 0.
   */
  static int tuneTarget(RoutingBoard board, RouterSettings settings, DifferentialPair pair) {
    int own = skewTarget(board, settings, pair);
    Double um = settings.autorouter != null ? settings.autorouter.pairMaxSkewUm : null;
    if (um == null) {
      um = DEFAULT_MAX_SKEW_UM;
    }
    if (um < 0 || own < 0) {
      return own;
    }
    return Math.min(own, umToBoard(board, um));
  }

  /** The skew target for a log line: {@code target 1.270 mm}, or {@code no target} when off. */
  static String skewTargetText(RoutingBoard board, int target) {
    return target < 0
        ? "no target"
        : String.format(Locale.ROOT, "target %.3f mm", toMm(board, target));
  }

  /** Extra length of a 45 degree bump per unit of its height: two legs of sqrt(2) for 2 along. */
  private static final double BUMP_GAIN = 2 * (Math.sqrt(2) - 1);

  /**
   * Intra-pair skew tuning: adds trapezoid bumps (45 degrees out, a short top, 45 degrees back) to
   * the middle trace of the shorter member, on the side away from its partner, so both members end
   * up the same length. Bumps are at most twice the gap high, kept clear of the bends, placed
   * nearest the end the skew comes from first ({@code fromB}: the second end of the middle trace),
   * and each is checked against the board's clearances before it is used; the new trace is checked
   * exactly. Returns the length added, or 0 when the trace is left as it was.
   */
  private static double tuneSkew(
      RoutingBoard board,
      Geometry g,
      Piece middle,
      Item oldTrace,
      int outward,
      int net,
      int traceClass,
      double skew,
      boolean fromB,
      List<Item> placed) {
    if (!(oldTrace instanceof PolylineTrace)) {
      return 0;
    }
    // Worked from the end the bumps should be nearest; reversing the trace swaps its sides.
    IntPoint[] corners = middle.corners().clone();
    int side = outward;
    if (fromB) {
      java.util.Collections.reverse(java.util.Arrays.asList(corners));
      side = -outward;
    }
    int gap = g.spacing() - g.width();
    double maxHeight = Math.max(g.width(), 2.0 * gap);
    int count = (int) Math.ceil(skew / (BUMP_GAIN * maxHeight));
    double top = Math.max(2.0 * g.width(), maxHeight);
    double between = Math.max(3.0 * g.width(), maxHeight);
    double margin = g.spacing() + maxHeight;
    // Candidate spots, nearest the chosen end first, each checked at the full height.
    record Spot(int segment, double at) {}
    List<Spot> spots = new ArrayList<>();
    for (int i = 0; i + 1 < corners.length && spots.size() < count; i++) {
      IntPoint from = corners[i];
      IntPoint to = corners[i + 1];
      double length = from.toFloat().distance(to.toFloat());
      for (double at = margin;
          at + 2 * maxHeight + top <= length - margin && spots.size() < count;
          at += 2 * maxHeight + top + between) {
        IntPoint[] bump = bump(from, to, at, maxHeight, top, side);
        if (ExitStubs.pathSlack(
                board, bump, g.layer(), g.halfWidth(), net, traceClass, g.allowance())
            >= 0) {
          spots.add(new Spot(i, at));
        }
      }
    }
    if (spots.isEmpty()) {
      debug("no room for length bumps on layer " + g.layer());
      return 0;
    }
    // Lower bumps inside the checked outline when fewer are needed.
    double height = Math.min(maxHeight, skew / (BUMP_GAIN * spots.size()));
    List<IntPoint> tuned = new ArrayList<>();
    for (int i = 0; i + 1 < corners.length; i++) {
      tuned.add(corners[i]);
      for (Spot spot : spots) {
        if (spot.segment() == i) {
          tuned.addAll(List.of(bump(corners[i], corners[i + 1], spot.at(), height, top, side)));
        }
      }
    }
    tuned.add(corners[corners.length - 1]);
    if (fromB) {
      java.util.Collections.reverse(tuned);
      corners = middle.corners();
    }
    PolylineTrace old = (PolylineTrace) oldTrace;
    board.removeItem(old);
    PolylineTrace replacement =
        board.insertTraceWithoutCleaning(
            new Polyline(simplify(tuned).toArray(new IntPoint[0])),
            g.layer(),
            g.halfWidth(),
            new int[] {net},
            traceClass,
            FixedState.UNFIXED);
    if (replacement != null && replacement.clearanceViolations().isEmpty()) {
      placed.set(placed.indexOf(old), replacement);
      return pathLength(replacement.polyline().corners()) - pathLength(corners);
    }
    debug("length bumps rejected by the exact check on layer " + g.layer());
    if (replacement != null) {
      board.removeItem(replacement);
    }
    PolylineTrace back =
        board.insertTraceWithoutCleaning(
            new Polyline(corners),
            g.layer(),
            g.halfWidth(),
            new int[] {net},
            traceClass,
            FixedState.UNFIXED);
    placed.set(placed.indexOf(old), back);
    return 0;
  }

  /**
   * The four corners of one bump on the segment from {@code from} to {@code to}, starting {@code
   * at} along it: out at 45 degrees by {@code height} to the {@code outward} side (+1 left of the
   * direction of travel), {@code top} along, back at 45 degrees.
   */
  private static IntPoint[] bump(
      IntPoint from, IntPoint to, double at, double height, double top, int outward) {
    double[] d = unit(from, to);
    double nx = -d[1] * outward;
    double ny = d[0] * outward;
    double[][] p = {
      {at, 0}, {at + height, height}, {at + height + top, height}, {at + 2 * height + top, 0}
    };
    IntPoint[] result = new IntPoint[4];
    for (int k = 0; k < 4; k++) {
      result[k] =
          new IntPoint(
              (int) Math.round(from.x + d[0] * p[k][0] + nx * p[k][1]),
              (int) Math.round(from.y + d[1] * p[k][0] + ny * p[k][1]));
    }
    return result;
  }

  /** Default of {@code pair_return_via_mm}: off, as they cost completion on a crowded board. */
  private static final double DEFAULT_RETURN_VIA_MM = -1;

  /**
   * The farthest a ground return via may sit from its signal via, centre to centre, in board units,
   * or -1 when return vias are off ({@code pair_return_via_mm} negative).
   */
  static int returnViaReach(RoutingBoard board, RouterSettings settings) {
    Double mm = settings.autorouter != null ? settings.autorouter.pairReturnViaMm : null;
    if (mm == null) {
      mm = DEFAULT_RETURN_VIA_MM;
    }
    return mm < 0 ? -1 : umToBoard(board, mm * 1000);
  }

  /**
   * The ground net for return vias: the net of a plane (conduction area) named GND, else of a plane
   * whose name contains GND; 0 when the board has no such plane.
   */
  private static int groundNet(RoutingBoard board) {
    int contains = 0;
    for (var area : board.getConductionAreas()) {
      if (area.netCount() == 0) {
        continue;
      }
      Net net = board.rules.nets.get(area.getNetNumber(0));
      if (net == null) {
        continue;
      }
      String name = net.name.toUpperCase(Locale.ROOT);
      if (name.equals("GND")) {
        return net.netNumber;
      }
      if (contains == 0 && name.contains("GND")) {
        contains = net.netNumber;
      }
    }
    return contains;
  }

  /**
   * Ground return vias beside the via pairs of the routed pairs, placed after every pair is routed
   * so they never take the room a later pair needs: where a pair changes layer, its return current
   * needs a way between the reference planes too. For each signal via of a via pair, one ground via
   * as close as the clearances allow, tried on the outer side in line with the pair first, then on
   * either side across the pair; never between the two signal vias, and never farther than {@code
   * pair_return_via_mm}. The vias are fixed with the pair copper and their ids join {@code
   * itemIds}. Returns the line to log, or null when no pair has a via pair or return vias are off.
   */
  private static String placeReturnVias(
      RoutingBoard board,
      RouterSettings settings,
      List<DifferentialPair> pairs,
      List<Integer> itemIds) {
    int reach = returnViaReach(board, settings);
    if (reach < 0) {
      return null;
    }
    // The via pairs: each P via of the placed copper with the nearest N via of its pair.
    record ViaPair(Via p, Via n) {}
    List<ViaPair> viaPairs = new ArrayList<>();
    for (DifferentialPair pair : pairs) {
      List<Via> p = new ArrayList<>();
      List<Via> n = new ArrayList<>();
      for (int id : itemIds) {
        if (board.getItem(id) instanceof Via via) {
          if (via.containsNet(pair.positiveNet().netNumber)) {
            p.add(via);
          } else if (via.containsNet(pair.negativeNet().netNumber)) {
            n.add(via);
          }
        }
      }
      for (Via pv : p) {
        Via nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Via nv : n) {
          double d = pv.getCenter().toFloat().distance(nv.getCenter().toFloat());
          if (d < best) {
            best = d;
            nearest = nv;
          }
        }
        if (nearest != null) {
          viaPairs.add(new ViaPair(pv, nearest));
        }
      }
    }
    if (viaPairs.isEmpty()) {
      return null;
    }
    int ground = groundNet(board);
    if (ground <= 0) {
      return "Return vias: none, the board has no ground plane (a plane named GND).";
    }
    Net groundNet = board.rules.nets.get(ground);
    int last = board.getLayerCount() - 1;
    ViaInfo via = groundNet.getNetClass() != null ? viaFor(groundNet.getNetClass(), 0, last) : null;
    if (via == null) {
      return "Return vias: none, the ground net has no through via.";
    }
    int groundRadius = viaRadius(via, 0, last);
    int allowance = ExitStubs.copperAllowance(board, settings);
    int wanted = 0;
    int done = 0;
    int tooFar = 0;
    double needed = 0;
    for (ViaPair vp : viaPairs) {
      for (Via signal : List.of(vp.p(), vp.n())) {
        wanted++;
        Via other = signal == vp.p() ? vp.n() : vp.p();
        int signalRadius = 0;
        int clearance = 0;
        for (int layer = signal.firstLayer(); layer <= signal.lastLayer(); layer++) {
          var shape = signal.getPadstack().getShape(layer);
          if (shape != null) {
            signalRadius = Math.max(signalRadius, (int) Math.ceil(shape.maxWidth() / 2));
          }
          clearance =
              Math.max(
                  clearance,
                  board.rules.clearanceMatrix.getValue(
                      via.getClearanceClassIndex(), signal.clearanceClassIndex(), layer, false));
        }
        double distance = signalRadius + groundRadius + clearance + allowance + 2;
        if (distance > reach) {
          tooFar++;
          needed = Math.max(needed, distance);
          continue;
        }
        IntPoint centre = (IntPoint) signal.getCenter();
        double[] out = unit((IntPoint) other.getCenter(), centre);
        double[][] directions = {out, {-out[1], out[0]}, {out[1], -out[0]}};
        for (double[] d : directions) {
          IntPoint at =
              new IntPoint(
                  (int) Math.round(centre.x + d[0] * distance),
                  (int) Math.round(centre.y + d[1] * distance));
          if (!clearReturnVia(board, via, at, ground, allowance)) {
            continue;
          }
          Via inserted =
              board.insertVia(
                  via.getPadstack(),
                  at,
                  new int[] {ground},
                  via.getClearanceClassIndex(),
                  FixedState.UNFIXED,
                  true);
          if (inserted == null) {
            continue;
          }
          if (inserted.clearanceViolations().isEmpty()) {
            inserted.setFixedState(PLACED_STATE);
            itemIds.add(inserted.getId());
            done++;
            break;
          }
          board.removeItem(inserted);
        }
      }
    }
    board.clearTransientAutorouteState();
    String line =
        "Return vias: "
            + done
            + " of "
            + wanted
            + " placed beside "
            + viaPairs.size()
            + " via pair(s) of the differential pairs";
    if (tooFar > 0) {
      line +=
          String.format(
              Locale.ROOT,
              "; %d would sit %.2f mm from the signal via, over pair_return_via_mm",
              tooFar,
              toMm(board, needed));
    }
    if (done + tooFar < wanted) {
      line += "; " + (wanted - done - tooFar) + " had no room";
    }
    return line + ".";
  }

  /** Whether a via of the ground net fits at {@code at} on every layer it spans. */
  private static boolean clearReturnVia(
      RoutingBoard board, ViaInfo via, IntPoint at, int net, int allowance) {
    Padstack padstack = via.getPadstack();
    int[] holder = new int[1];
    for (int layer = padstack.fromLayer(); layer <= padstack.toLayer(); layer++) {
      var shape = padstack.getShape(layer);
      if (shape == null) {
        continue;
      }
      if (ExitStubs.slack(
              board,
              at,
              at,
              layer,
              (int) Math.ceil(shape.maxWidth() / 2),
              net,
              via.getClearanceClassIndex(),
              allowance,
              holder)
          < 0) {
        return false;
      }
    }
    return true;
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
  private static String firstViolation(RoutingBoard board, List<Item> placed) {
    for (Item item : placed) {
      for (var v : item.clearanceViolations()) {
        FloatPoint at = v.shape.centreOfGravity();
        return item.getClass().getSimpleName()
            + " against "
            + v.secondItem.getClass().getSimpleName()
            + (v.secondItem instanceof Pin pin ? " " + pin.componentName() + "-" + pin.name() : "")
            + " on layer "
            + v.layer
            + String.format(
                Locale.ROOT,
                " at (%.3f, %.3f) mm, %.3f of %.3f mm",
                toMm(board, at.x),
                toMm(board, at.y),
                toMm(board, Math.max(0, v.actualClearance)),
                toMm(board, v.expectedClearance));
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

  static double toMm(RoutingBoard board, double boardUnits) {
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

  /**
   * Length of the virtual stub at a gather point: long enough that its far part lies in free space
   * where the search can still reach it after shrinking the room by the wide trace's clearance
   * offset; the near part starts one clearance from the breakouts.
   */
  private static int stubLength(Geometry g) {
    return 3 * g.virtualHalf();
  }

  private static IntPoint tip(End end, Geometry g) {
    return tipAt(end.gather(), end.dx(), end.dy(), g.padding() + stubLength(g));
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
