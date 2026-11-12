package app.freerouting.autoroute.pipeline;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.core.RoutingJob;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Moves a member of a differential pair onto its partner's layers when the routing stage left the
 * two members on different layers, which breaks impedance matching.
 *
 * <p>The layer hold keeps the members together only in the first passes, so a member ripped and
 * routed again later can end on a layer its partner never uses. Here, per split pair, the member
 * with layers its partner lacks has its copper taken off a copy of the board and is routed again
 * with every search held to the partner's trace layers; connections it rips are routed again too.
 * The copy replaces the board only when the members then share their layers, no more connections
 * are open and no clearance violation is new. Every search has a step budget, so the result does
 * not depend on time.
 */
final class PairLayerRepair {

  /**
   * Routing passes on the copy: the member usually goes in at once, and the passes after put back
   * the connections it ripped on its now narrower layers.
   */
  private static final int PASSES = 10;

  private PairLayerRepair() {}

  /**
   * Repairs each split pair in name order. Off when {@code pair_layer_hold_passes} is negative.
   * Returns the lines to log, empty when no pair is split.
   */
  static List<String> run(RoutingJob job) {
    List<String> lines = new ArrayList<>();
    RouterSettings settings = job.routerSettings;
    Integer hold = settings.autorouter != null ? settings.autorouter.pairLayerHoldPasses : null;
    if (hold == null || hold < 0 || job.board == null) {
      return lines;
    }
    List<DifferentialPair> pairs = new ArrayList<>(job.board.rules.differentialPairs.all());
    pairs.sort(Comparator.comparing(DifferentialPair::baseName, String.CASE_INSENSITIVE_ORDER));
    boolean reset = false;
    for (DifferentialPair pair : pairs) {
      if (stopped(job)) {
        break;
      }
      Set<Integer> p = PairRouting.traceLayers(job.board, pair.positiveNet().netNumber);
      Set<Integer> n = PairRouting.traceLayers(job.board, pair.negativeNet().netNumber);
      if (p.isEmpty() || n.isEmpty() || p.equals(n)) {
        continue;
      }
      if (!reset) {
        // The pass loop ends with its own stop request set; the passes here would read it as a
        // stop.
        job.thread.resetAutoRouterStopRequest();
        reset = true;
      }
      // The member with layers its partner lacks moves; when both have such layers, the one on
      // more layers moves first, then the other.
      List<Net> movers = new ArrayList<>();
      boolean pExtra = !n.containsAll(p);
      boolean nExtra = !p.containsAll(n);
      if (pExtra && (!nExtra || p.size() > n.size())) {
        movers.add(pair.positiveNet());
        if (nExtra) {
          movers.add(pair.negativeNet());
        }
      } else {
        movers.add(pair.negativeNet());
        if (pExtra) {
          movers.add(pair.positiveNet());
        }
      }
      String outcome = null;
      for (Net mover : movers) {
        outcome = tryMove(job, pair, mover);
        if (outcome.endsWith("kept.")) {
          break;
        }
      }
      lines.add(outcome);
    }
    return lines;
  }

  /** One try: {@code mover} off the copy and routed again on its partner's layers. */
  private static String tryMove(RoutingJob job, DifferentialPair pair, Net mover) {
    RoutingBoard board = job.board;
    Net partner = pair.getPartner(mover);
    Set<Integer> partnerLayers = PairRouting.traceLayers(board, partner.netNumber);
    RoutingBoard copy = board.deepCopy();
    List<Item> own = new ArrayList<>();
    for (Item item : copy.getConnectableItems(mover.netNumber)) {
      if ((item instanceof Trace || item instanceof Via) && !item.isUserFixed()) {
        own.add(item);
      }
    }
    String head =
        String.format(
            Locale.US,
            "Pair %s: %s moved to the layers of %s (%s): ",
            pair.baseName(),
            mover.name,
            partner.name,
            PairRouting.layerNames(board, partnerLayers));
    if (own.isEmpty()) {
      return head + "it has no copper the router may move; the board before it is kept.";
    }
    RoutingFallback.Outcome before =
        RoutingFallback.Outcome.of("pair layer repair", board, job.routerSettings);
    var baseline = RoutingFallback.captureClearance(board, before.violations);
    copy.removeItems(own);
    copy.clearTransientAutorouteState();

    RouterSettings settings = job.routerSettings.clone();
    settings.setFanoutEnabled(false);
    // Every pass holds a pair member to its partner's layers.
    settings.autorouter.pairLayerHoldPasses = Integer.MAX_VALUE;
    BatchAutorouter router = BatchAutorouter.createAttempt(job, copy, settings, false);
    // Only the mover and what it rips are routed: the connections open before stay as they were.
    router.netsLeftUnrouted = new TreeSet<>(board.routingLedger().incompleteNetNumbers());
    router.netsLeftUnrouted.remove(mover.netNumber);
    for (int pass = 1; pass <= PASSES && !stopped(job); pass++) {
      if (!router.autoroutePass(pass)) {
        break;
      }
    }
    router.removeTails(Item.StopConnectionOption.NONE);
    copy.clearTransientAutorouteState();

    RoutingFallback.Outcome after =
        RoutingFallback.Outcome.of("pair layer repair", copy, job.routerSettings, baseline);
    Set<Integer> moved = PairRouting.traceLayers(copy, mover.netNumber);
    boolean together = !moved.isEmpty() && partnerLayers.containsAll(moved);
    boolean better =
        together && after.unrouted <= before.unrouted && after.newOrChanged == 0 && !stopped(job);
    if (better) {
      job.board = copy;
    }
    return head
        + String.format(
            Locale.US,
            "%d open before, %d after, now on %s, %d new violation(s); %s",
            before.unrouted,
            after.unrouted,
            moved.isEmpty() ? "no layer" : PairRouting.layerNames(copy, moved),
            after.newOrChanged,
            better ? "kept." : "not better, the board before it is kept.");
  }

  private static boolean stopped(RoutingJob job) {
    return job.thread != null && job.thread.isStopRequested();
  }
}
