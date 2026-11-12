package app.freerouting.autoroute.pipeline;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.core.RoutingJob;
import app.freerouting.drc.AirLine;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rips a routed differential pair and routes it again as a unit, when connections are still open
 * after the routing stage.
 *
 * <p>The pair stage routes the pairs first, on the empty board, and the pair copper stays fixed
 * while the other stages run, so the router cannot move a pair that sits where an open connection
 * needs to pass. Here, nearest pair first, the pair's copper is taken off a copy of the board, the
 * open connections are routed on the copy with the pair's own nets held back, and then the pair is
 * routed again around them. The copy replaces the board only when the pair is still coupled on
 * every connection, fewer connections are open and no clearance violation is new; otherwise the
 * board stays as it was. Every step is a search with a step budget, so the result does not depend
 * on time.
 */
final class PairReroute {

  /** Default of {@code pair_reroute}: the most pairs tried. */
  static final int DEFAULT_TRIES = 4;

  /** Routing passes for the open connections while the pair is off the board. */
  private static final int PASSES = 4;

  private PairReroute() {}

  /**
   * Tries the pairs nearest the open connections, up to {@code pair_reroute} of them, and keeps
   * each copy that is better. {@code pairItems} holds the ids of the placed pair copper; it is
   * updated for a pair that was routed again. Returns the lines to log, empty when nothing was
   * tried.
   */
  static List<String> run(RoutingJob job, List<Integer> pairItems) {
    List<String> lines = new ArrayList<>();
    RouterSettings settings = job.routerSettings;
    int tries = tries(settings);
    if (tries <= 0 || pairItems.isEmpty() || job.board == null) {
      return lines;
    }
    if (stopped(job)) {
      return lines;
    }
    // The pass loop ends with its own stop request set; the passes here and the pair stage would
    // read it as a stop.
    job.thread.resetAutoRouterStopRequest();
    Set<String> tried = new TreeSet<>();
    while (tries > 0 && !stopped(job)) {
      RoutingBoard board = job.board;
      List<FloatPoint> openEnds = openEnds(board);
      if (openEnds.isEmpty()) {
        break;
      }
      DifferentialPair pair = nearestPair(board, pairItems, openEnds, tried);
      if (pair == null) {
        break;
      }
      tried.add(pair.baseName());
      tries--;
      lines.addAll(tryPair(job, pair, pairItems));
    }
    return lines;
  }

  static int tries(RouterSettings settings) {
    Integer value = settings.autorouter != null ? settings.autorouter.pairReroute : null;
    return value != null ? value : DEFAULT_TRIES;
  }

  /**
   * One pair: off the copy, the open connections, the pair again, then the comparison. Returns the
   * outcome line and, when the copy is kept, the pair router's line for each connection, with its
   * new layer, lengths and skew.
   */
  private static List<String> tryPair(
      RoutingJob job, DifferentialPair pair, List<Integer> pairItems) {
    RoutingBoard board = job.board;
    RoutingFallback.Outcome before =
        RoutingFallback.Outcome.of("pair re-route", board, job.routerSettings);
    var baseline = RoutingFallback.captureClearance(board, before.violations);

    RoutingBoard copy = board.deepCopy();
    List<Integer> ownIds = pairItemIds(copy, pairItems, pair);
    List<Item> own = new ArrayList<>();
    for (int id : ownIds) {
      Item item = copy.getItem(id);
      item.setFixedState(FixedState.UNFIXED);
      own.add(item);
    }
    copy.removeItems(own);
    copy.clearTransientAutorouteState();

    RouterSettings settings = job.routerSettings.clone();
    settings.setFanoutEnabled(false);
    BatchAutorouter router = BatchAutorouter.createAttempt(job, copy, settings, false);
    router.netsLeftUnrouted = Set.of(pair.positiveNet().netNumber, pair.negativeNet().netNumber);
    for (int pass = 1; pass <= PASSES && !stopped(job); pass++) {
      if (!router.autoroutePass(pass)) {
        break;
      }
    }
    router.removeTails(Item.StopConnectionOption.NONE);
    copy.clearTransientAutorouteState();

    List<Integer> newIds = new ArrayList<>();
    List<String> pairLines = PairRouting.routeOne(settings, copy, job.thread, pair, newIds);
    int routed = 0;
    int connections = 0;
    for (String line : pairLines) {
      if (line.contains("): ")) {
        connections++;
        routed += line.contains("): routed on layer") ? 1 : 0;
      }
    }
    boolean coupled = connections > 0 && routed == connections;
    RoutingFallback.Outcome after =
        RoutingFallback.Outcome.of("pair re-route", copy, job.routerSettings, baseline);
    boolean better =
        coupled && after.unrouted < before.unrouted && after.newOrChanged == 0 && !stopped(job);
    String line =
        String.format(
            Locale.US,
            "Pair re-route %s: %d open before, %d after with the pair %s, %d new violation(s); %s",
            pair.baseName(),
            before.unrouted,
            after.unrouted,
            coupled ? "coupled again" : "not coupled again (" + summary(pairLines) + ")",
            after.newOrChanged,
            better ? "kept." : "not better, the board before it is kept.");
    List<String> result = new ArrayList<>();
    result.add(line);
    if (better) {
      pairItems.removeAll(ownIds);
      pairItems.addAll(newIds);
      job.board = copy;
      for (String pairLine : pairLines) {
        if (pairLine.contains("): ")) {
          result.add("  " + pairLine);
        }
      }
    }
    return result;
  }

  /** The ids in pairItems of the copper on the pair's two nets. */
  private static List<Integer> pairItemIds(
      RoutingBoard board, List<Integer> pairItems, DifferentialPair pair) {
    List<Integer> result = new ArrayList<>();
    for (int id : pairItems) {
      Item item = board.getItem(id);
      if (item != null
          && (item.containsNet(pair.positiveNet().netNumber)
              || item.containsNet(pair.negativeNet().netNumber))) {
        result.add(id);
      }
    }
    return result;
  }

  /**
   * The placed pair whose copper comes nearest an open end, not tried yet; ties by name. Null when
   * no such pair is left.
   */
  private static DifferentialPair nearestPair(
      RoutingBoard board, List<Integer> pairItems, List<FloatPoint> openEnds, Set<String> tried) {
    List<DifferentialPair> pairs = new ArrayList<>(board.rules.differentialPairs.all());
    pairs.sort(Comparator.comparing(DifferentialPair::baseName, String.CASE_INSENSITIVE_ORDER));
    DifferentialPair best = null;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (DifferentialPair pair : pairs) {
      if (tried.contains(pair.baseName())) {
        continue;
      }
      double distance = Double.POSITIVE_INFINITY;
      for (int id : pairItemIds(board, pairItems, pair)) {
        IntBox box = board.getItem(id).boundingBox();
        for (FloatPoint end : openEnds) {
          distance = Math.min(distance, distance(box, end));
        }
      }
      if (distance < bestDistance) {
        bestDistance = distance;
        best = pair;
      }
    }
    return best;
  }

  private static double distance(IntBox box, FloatPoint p) {
    double dx = Math.max(Math.max(box.ll.x - p.x, 0), p.x - box.ur.x);
    double dy = Math.max(Math.max(box.ll.y - p.y, 0), p.y - box.ur.y);
    return Math.hypot(dx, dy);
  }

  /** Both ends of every open connection. */
  private static List<FloatPoint> openEnds(RoutingBoard board) {
    List<FloatPoint> ends = new ArrayList<>();
    for (AirLine airline : new DesignRulesChecker(board, null).getAllAirlines()) {
      ends.add(airline.fromCorner);
      ends.add(airline.toCorner);
    }
    return ends;
  }

  private static String summary(List<String> pairLines) {
    if (pairLines.isEmpty()) {
      return "no open connection of the pair found";
    }
    for (String line : pairLines) {
      if (line.contains("): ") && !line.contains("): routed on layer")) {
        int colon = line.indexOf("): ");
        return colon >= 0 ? line.substring(colon + 3) : line;
      }
    }
    return "";
  }

  private static boolean stopped(RoutingJob job) {
    // Only a stop of the job: the pass loop leaves its own stop request set when it ends.
    return job.thread != null && job.thread.isStopRequested();
  }
}
