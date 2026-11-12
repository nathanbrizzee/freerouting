package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.AutorouteAttemptResult;
import app.freerouting.autoroute.AutorouteAttemptState;
import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.core.library.Padstack;
import app.freerouting.datastructures.TimeLimit;
import app.freerouting.drc.OpenConnections;
import app.freerouting.geometry.planar.IntPoint;
import app.freerouting.logger.FRLogger;
import app.freerouting.rules.DifferentialPair;
import app.freerouting.rules.Net;
import app.freerouting.rules.ViaInfo;
import app.freerouting.rules.ViaRule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/** Executes one autoroute connection, including necked retry and strict-DRC recovery. */
final class AutorouteConnectionRouter {

  private final BatchAutorouter router;

  AutorouteConnectionRouter(BatchAutorouter router) {
    this.router = router;
  }

  AutorouteAttemptResult route(
      Item item,
      int routeNetNo,
      SortedSet<Item> rippedItemList,
      Map<Item, Integer> ripupCosts,
      int ripupPassNo) {
    TimeLimit timeLimit = connectionLimit(item, ripupPassNo);
    AutorouteAttemptResult result =
        routeWithin(item, routeNetNo, rippedItemList, ripupCosts, ripupPassNo, timeLimit);
    router.passSearchWork.record(timeLimit, result.state, router.thread);
    if (router.searchStepsLeft > 0) {
      router.searchStepsLeft = Math.max(0, router.searchStepsLeft - timeLimit.steps());
    }
    return result;
  }

  /**
   * The search limit for one connection: the step budget when it is set, otherwise the time limit;
   * both double with each pass, except that a connection that failed in an earlier pass gets {@code
   * max_search_steps_after_failure}. In the optimizer, the optimizer's own step cap bounds every
   * search.
   */
  private TimeLimit connectionLimit(Item item, int ripupPassNo) {
    double factor = Math.pow(2, ripupPassNo - 1);
    Long steps =
        router.settings.autorouter != null
            ? router.settings.autorouter.maxSearchStepsPerConnection
            : null;
    Long optimizerCap =
        router.isOptimizerAutorouter && router.settings.optimizer != null
            ? router.settings.optimizer.maxSearchStepsPerConnection
            : null;
    if (optimizerCap != null && optimizerCap > 0) {
      long budget =
          steps != null && steps > 0
              ? (long) Math.min(steps * factor, Long.MAX_VALUE)
              : optimizerCap;
      budget = Math.min(budget, optimizerCap);
      if (router.searchStepsLeft > 0) {
        budget = Math.min(budget, router.searchStepsLeft);
      }
      return TimeLimit.ofSteps(budget);
    }
    if (steps != null && steps > 0) {
      // A connection that already failed gets a flat budget: it rarely succeeds with twice as much,
      // and the grid finisher takes what is still open after the passes.
      Long afterFailure =
          router.settings.autorouter != null
              ? router.settings.autorouter.maxSearchStepsAfterFailure
              : null;
      if (afterFailure != null
          && afterFailure > 0
          && item != null
          && router.board.failureLog.getFailureCount(item) > 0) {
        return TimeLimit.ofSteps(
            Math.min(afterFailure, (long) Math.min(steps * factor, Long.MAX_VALUE)));
      }
      return TimeLimit.ofSteps((long) Math.min(steps * factor, Long.MAX_VALUE));
    }
    double maxMilliseconds = Math.min(100000 * factor, Integer.MAX_VALUE);
    return new TimeLimit((int) maxMilliseconds);
  }

  private AutorouteAttemptResult routeWithin(
      Item item,
      int routeNetNo,
      SortedSet<Item> rippedItemList,
      Map<Item, Integer> ripupCosts,
      int ripupPassNo,
      TimeLimit timeLimit) {
    try {
      Net routeNet = router.board.rules.nets.get(routeNetNo);
      boolean containsPlane = routeNet != null && routeNet.containsPlane();
      int currentViaCosts =
          containsPlane ? router.settings.getPlaneViaCosts() : router.settings.getViaCosts();

      AutorouteControl autorouteControl =
          new AutorouteControl(
              router.board, routeNetNo, router.settings, currentViaCosts, router.getTraceCosts());
      autorouteControl.ripupAllowed = true;
      autorouteControl.ripupCosts = router.getStartRipupCosts() * ripupPassNo;
      // Enables the rip-up cost randomization in MazeRipupResolver from pass 4; it is seeded from
      // ripupCosts, so it stays deterministic.
      autorouteControl.ripupPassNo = ripupPassNo;
      autorouteControl.netsNotToRip = router.ripupMemory.netsNotToRip(routeNetNo);
      autorouteControl.removeUnconnectedVias = router.isRemoveUnconnectedVias();
      router.applySearchCosts(autorouteControl);
      holdToPartnerLayers(autorouteControl, routeNetNo, ripupPassNo);

      Set<Item> unconnectedSet = item.getUnconnectedSet(routeNetNo);
      if (unconnectedSet.isEmpty()) {
        return new AutorouteAttemptResult(AutorouteAttemptState.NO_UNCONNECTED_NETS);
      }

      Set<Item> connectedSet = item.getConnectedSet(routeNetNo);
      Set<Item> routeStartSet;
      Set<Item> routeDestSet;
      if (containsPlane) {
        for (Item currentItem : connectedSet) {
          if (currentItem instanceof ConductionArea) {
            return new AutorouteAttemptResult(AutorouteAttemptState.CONNECTED_TO_PLANE);
          }
        }
        routeStartSet = connectedSet;
        routeDestSet = unconnectedSet;
      } else {
        routeStartSet = unconnectedSet;
        routeDestSet = connectedSet;
      }

      router.setAirLine(AutorouteAirlineCalculator.calculateAirline(routeStartSet, routeDestSet));

      Pin closedIn = closedInEnd(routeStartSet, routeDestSet);
      if (closedIn != null) {
        return new AutorouteAttemptResult(
            AutorouteAttemptState.FAILED,
            "pin " + closedIn.componentName() + "-" + closedIn.name() + " is closed in");
      }

      boolean isStrictPass = router.settings.isStrictDrc();

      // start_necked: a net that needed the neck before routes at the neck width, ripping like any
      // search, and
      // that result stands. The class-width search it skips would use up the space before the
      // necked retry
      // and, when it routes, displace the neighbours the neck fits beside.
      if (router.startNecked
          && router.settings.getNeckWidthUm() > 0
          && router.neckedRouteNets.contains(routeNetNo)) {
        int beforeStart = router.board.communication.idGenerator.maxGeneratedId();
        byte[] startSnapshot = isStrictPass ? router.board.serialize(false) : null;
        AutorouteAttemptResult started =
            retryConnectionNecked(
                routeNetNo,
                autorouteControl,
                currentViaCosts,
                routeStartSet,
                routeDestSet,
                rippedItemList,
                ripupCosts,
                ripupPassNo,
                timeLimit,
                /* planned= */ true,
                /* quiet= */ true);
        if (started != null) {
          if (started.state != AutorouteAttemptState.ROUTED) {
            router.startNeckedFailed++;
            return started;
          }
          AutorouteAttemptResult rejection =
              applyStrictDrcAfterRoute(routeNetNo, beforeStart, startSnapshot, ripupPassNo);
          if (rejection != null) {
            return rejection;
          }
          router.startNeckedRouted++;
          return started;
        }
      }

      AutorouteEngine autorouteEngine =
          router.board.initAutoroute(
              routeNetNo,
              autorouteControl.traceClearanceClassIndex,
              router.thread,
              timeLimit,
              router.isRetainAutorouteDatabase());
      int maxItemIdBeforeRoute = router.board.communication.idGenerator.maxGeneratedId();
      // Strict DRC needs this snapshot to undo a rejected route together with the ripups it made,
      // so it is applied only when enabled explicitly: snapshotting every connection of every late
      // pass would serialize the whole board per connection.
      byte[] strictDrcBoardSnapshot = isStrictPass ? router.board.serialize(false) : null;

      long mazeSearchStart = BatchAutorouter.isBenchmarkProfileEnabled() ? System.nanoTime() : 0;
      final AutorouteAttemptResult autorouteResult =
          autorouteEngine.autorouteConnection(
              routeStartSet, routeDestSet, autorouteControl, rippedItemList, ripupCosts);
      if (BatchAutorouter.isBenchmarkProfileEnabled()) {
        router.addProfileMazeSearchNanos(System.nanoTime() - mazeSearchStart);
      }

      if (autorouteResult.state == AutorouteAttemptState.ROUTED) {
        int maxItemIdBeforeOpt = router.board.communication.idGenerator.maxGeneratedId();
        FRLogger.trace(
            "compare_trace_opt_changed_area_before net="
                + routeNetNo
                + ", maxItemId="
                + maxItemIdBeforeOpt);
        long pullTightStart = BatchAutorouter.isBenchmarkProfileEnabled() ? System.nanoTime() : 0;
        router.board.optChangedArea(
            new int[0],
            null,
            router.getTracePullTightAccuracy(),
            autorouteControl.traceCosts,
            router.thread);
        if (BatchAutorouter.isBenchmarkProfileEnabled()) {
          router.addProfileOptChangedAreaNanos(System.nanoTime() - pullTightStart);
        }
        int maxItemIdAfterOpt = router.board.communication.idGenerator.maxGeneratedId();
        FRLogger.trace(
            "compare_trace_opt_changed_area_after net="
                + routeNetNo
                + ", maxItemId="
                + maxItemIdAfterOpt
                + ", delta="
                + (maxItemIdAfterOpt - maxItemIdBeforeOpt));
      }

      if ((autorouteResult.state == AutorouteAttemptState.FAILED
              || autorouteResult.state == AutorouteAttemptState.INSERT_ERROR)
          && router.settings.getNeckWidthUm() > 0) {
        AutorouteAttemptResult neckedResult =
            retryConnectionNecked(
                routeNetNo,
                autorouteControl,
                currentViaCosts,
                routeStartSet,
                routeDestSet,
                rippedItemList,
                ripupCosts,
                ripupPassNo,
                timeLimit,
                /* planned= */ false,
                /* quiet= */ false);
        if (neckedResult != null) {
          AutorouteAttemptResult strictResult =
              applyStrictDrcAfterRoute(
                  routeNetNo, maxItemIdBeforeRoute, strictDrcBoardSnapshot, ripupPassNo);
          if (strictResult != null) {
            return strictResult;
          }
          return neckedResult;
        }
      }

      if (autorouteResult.state == AutorouteAttemptState.ROUTED) {
        AutorouteAttemptResult strictResult =
            applyStrictDrcAfterRoute(
                routeNetNo, maxItemIdBeforeRoute, strictDrcBoardSnapshot, ripupPassNo);
        if (strictResult != null) {
          return strictResult;
        }
      }

      if (autorouteResult.state == AutorouteAttemptState.FAILED) {
        noteClosedIn(routeStartSet, routeNetNo);
        noteClosedIn(routeDestSet, routeNetNo);
      }
      return autorouteResult;
    } catch (Exception e) {
      FRLogger.error("Error during routing passes", e);
      return new AutorouteAttemptResult(AutorouteAttemptState.FAILED);
    }
  }

  /**
   * A member of a differential pair the pair router left to this router keeps to the layers its
   * partner's traces already use, in the first {@code pair_layer_hold_passes} passes, so the two
   * members stay on the same layers and reference planes, as impedance matching needs. Later passes
   * search on every layer, so the hold never costs a connection for good. The optimizer holds in
   * every pass: a candidate is kept only when no connection is lost, so the hold costs it none, and
   * it must not move a member away from its partner.
   */
  private void holdToPartnerLayers(AutorouteControl control, int netNo, int passNo) {
    Integer holdPasses =
        router.settings.autorouter != null ? router.settings.autorouter.pairLayerHoldPasses : null;
    if (holdPasses == null
        || holdPasses < 0
        || (passNo > holdPasses && !router.isOptimizerAutorouter)) {
      return;
    }
    holdToPartnerLayers(router.board, control.layerActive, netNo);
  }

  /**
   * When {@code netNo} is a member of a differential pair whose partner has traces, narrows {@code
   * layerActive} to the partner's trace layers, unless that would leave no layer. Shared by the
   * maze router and the grid finisher, so neither puts a member on a layer its partner never uses.
   */
  static void holdToPartnerLayers(RoutingBoard board, boolean[] layerActive, int netNo) {
    DifferentialPair pair = board.rules.differentialPairs.findForNet(netNo);
    if (pair == null) {
      return;
    }
    Net partner = pair.getPartner(board.rules.nets.get(netNo));
    if (partner == null) {
      return;
    }
    boolean[] used = new boolean[layerActive.length];
    boolean any = false;
    for (Item item : board.getConnectableItems(partner.netNumber)) {
      if (item instanceof Trace trace && trace.getLayer() < used.length) {
        used[trace.getLayer()] = true;
        any = true;
      }
    }
    if (!any) {
      return;
    }
    boolean left = false;
    for (int i = 0; i < layerActive.length; i++) {
      left |= layerActive[i] && used[i];
    }
    if (!left) {
      return;
    }
    for (int i = 0; i < layerActive.length; i++) {
      layerActive[i] &= used[i];
    }
  }

  /** The single pin of an end set that was found closed in, or null. */
  private Pin closedInEnd(Set<Item> start, Set<Item> dest) {
    for (Set<Item> end : List.of(start, dest)) {
      if (end.size() == 1
          && end.iterator().next() instanceof Pin pin
          && router.closedInPins.contains(pin.getId())) {
        return pin;
      }
    }
    return null;
  }

  /**
   * After a failed search: an end that is a lone pin no trace can leave even with every route
   * around it ripped is remembered, and its later searches are skipped. With vias in pads, only
   * when no via of its net fits in the pad either, since such a via can leave a pin the trace stubs
   * cannot. Not in the optimizer.
   */
  private void noteClosedIn(Set<Item> end, int netNo) {
    // An optimizer candidate's router lives for one item, so it would only log.
    if (router.isOptimizerAutorouter
        || end.size() != 1
        || !(end.iterator().next() instanceof Pin pin)
        || router.closedInPins.contains(pin.getId())) {
      return;
    }
    boolean viaInPad =
        router.settings.autorouter != null
            && Boolean.TRUE.equals(router.settings.autorouter.viaInPad);
    if (OpenConnections.closedForTheRouter(router.board, pin, netNo)
        && !(viaInPad && viaFitsInPad(pin, netNo))) {
      router.closedInPins.add(pin.getId());
      String line =
          "Pin "
              + pin.componentName()
              + "-"
              + pin.name()
              + " is closed in by copper the router does not move (pads, fixed copper, keepouts);"
              + " its later searches are skipped.";
      if (router.job != null) {
        router.job.logInfo(line);
      } else {
        FRLogger.info(line);
      }
    }
  }

  /**
   * Whether a via of the net's class fits at the centre of the pin, clear of everything but its own
   * net on every layer it spans: with vias in pads, such a via can leave a pin no trace stub can.
   */
  private boolean viaFitsInPad(Pin pin, int netNo) {
    Net net = router.board.rules.nets.get(netNo);
    if (net == null || net.getNetClass() == null || !(pin.getCenter() instanceof IntPoint at)) {
      return true;
    }
    ViaRule rule = net.getNetClass().getViaRule();
    int allowance = ExitStubs.copperAllowance(router.board, router.settings);
    int[] holder = new int[1];
    for (int i = 0; i < rule.viaCount(); i++) {
      ViaInfo via = rule.getVia(i);
      Padstack padstack = via.getPadstack();
      if (padstack.fromLayer() > pin.lastLayer() || padstack.toLayer() < pin.firstLayer()) {
        continue;
      }
      boolean fits = true;
      for (int layer = padstack.fromLayer(); fits && layer <= padstack.toLayer(); layer++) {
        var shape = padstack.getShape(layer);
        fits =
            shape == null
                || ExitStubs.slack(
                        router.board,
                        at,
                        at,
                        layer,
                        (int) Math.ceil(shape.maxWidth() / 2),
                        netNo,
                        via.getClearanceClassIndex(),
                        allowance,
                        holder)
                    >= 0;
      }
      if (fits) {
        return true;
      }
    }
    return false;
  }

  private AutorouteAttemptResult retryConnectionNecked(
      int routeNetNo,
      AutorouteControl originalControl,
      int viaCosts,
      Set<Item> routeStartSet,
      Set<Item> routeDestSet,
      SortedSet<Item> rippedItemList,
      Map<Item, Integer> ripupCosts,
      int ripupPassNo,
      TimeLimit timeLimit,
      boolean planned,
      boolean quiet) {
    int neckHalfWidth = AutorouteControl.neckHalfWidth(router.board, router.settings);
    boolean narrowerSomewhere = false;
    for (int i = 0; i < originalControl.layerCount; i++) {
      if (originalControl.layerActive[i] && originalControl.traceHalfWidth[i] > neckHalfWidth) {
        narrowerSomewhere = true;
        break;
      }
    }
    if (!narrowerSomewhere) {
      return null;
    }

    AutorouteControl neckControl =
        new AutorouteControl(
            router.board, routeNetNo, router.settings, viaCosts, router.getTraceCosts());
    neckControl.ripupAllowed = true;
    neckControl.ripupCosts = router.getStartRipupCosts() * ripupPassNo;
    neckControl.ripupPassNo = ripupPassNo;
    neckControl.netsNotToRip = originalControl.netsNotToRip;
    neckControl.removeUnconnectedVias = router.isRemoveUnconnectedVias();
    router.applySearchCosts(neckControl);
    for (int i = 0; i < neckControl.layerCount; i++) {
      int compensation = neckControl.compensatedTraceHalfWidth[i] - neckControl.traceHalfWidth[i];
      neckControl.traceHalfWidth[i] = Math.min(neckControl.traceHalfWidth[i], neckHalfWidth);
      neckControl.compensatedTraceHalfWidth[i] = neckControl.traceHalfWidth[i] + compensation;
      neckControl.updateTraceClearanceOffset(i, router.board);
    }

    AutorouteEngine neckEngine =
        router.board.initAutoroute(
            routeNetNo,
            neckControl.traceClearanceClassIndex,
            router.thread,
            timeLimit,
            router.isRetainAutorouteDatabase());
    long neckMazeSearchStart = BatchAutorouter.isBenchmarkProfileEnabled() ? System.nanoTime() : 0;
    AutorouteAttemptResult neckResult =
        neckEngine.autorouteConnection(
            routeStartSet, routeDestSet, neckControl, rippedItemList, ripupCosts);
    if (BatchAutorouter.isBenchmarkProfileEnabled()) {
      router.addProfileMazeSearchNanos(System.nanoTime() - neckMazeSearchStart);
    }
    if (neckResult.state != AutorouteAttemptState.ROUTED) {
      return planned ? neckResult : null;
    }

    router.neckedRouteNets.add(routeNetNo);

    long neckPullTightStart = BatchAutorouter.isBenchmarkProfileEnabled() ? System.nanoTime() : 0;
    router.board.optChangedArea(
        new int[0],
        null,
        router.getTracePullTightAccuracy(),
        neckControl.traceCosts,
        router.thread);
    if (BatchAutorouter.isBenchmarkProfileEnabled()) {
      router.addProfileOptChangedAreaNanos(System.nanoTime() - neckPullTightStart);
    }
    if (!quiet) {
      Net routeNet = router.board.rules.nets.get(routeNetNo);
      FRLogger.info(
          (planned ? "Retry plan routed net '" : "Necked retry routed net '")
              + (routeNet != null ? routeNet.name : "#" + routeNetNo)
              + "' at "
              + router.settings.getNeckWidthUm()
              + " um trace width.");
    }
    return neckResult;
  }

  /**
   * Rejects the route when its new items carry clearance violations and restores the board from the
   * snapshot taken before it. Without a snapshot the route is kept: removing only its new items
   * would leave the connections it ripped up unrouted.
   */
  private AutorouteAttemptResult applyStrictDrcAfterRoute(
      int routeNetNo, int maxItemIdBefore, byte[] boardSnapshotBeforeRoute, int ripupPassNo) {
    if (boardSnapshotBeforeRoute == null) {
      return null;
    }
    AutorouteAttemptResult rejection =
        BatchAutorouter.enforceStrictDrc(router.board, routeNetNo, maxItemIdBefore);
    if (rejection != null) {
      FRLogger.trace(
          "AutorouteConnectionRouter.apply_strict_drc",
          "strict_drc_rejection",
          "pass=" + ripupPassNo + ", net=" + routeNetNo + ", reason=" + rejection.details,
          "",
          null);
      if (boardSnapshotBeforeRoute != null) {
        RoutingBoard restored = (RoutingBoard) BasicBoard.deserialize(boardSnapshotBeforeRoute);
        restored.clearanceBaseline = router.board.clearanceBaseline;
        router.board = restored;
      }
    }
    return rejection;
  }
}
