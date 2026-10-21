package app.freerouting.autoroute.plan;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.structure.Unit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The oracle corridor experiment (docs/research/global-planner-design.md, step 2): one run writes
 * corridors from its finished copper, a later run of the same board routes with a cost for leaving
 * them. It shows what a perfect global plan could gain before any planner exists. Set by system
 * properties only; with none set, nothing changes.
 *
 * <ul>
 *   <li>{@code freerouting.experiment.corridor_out}: file to write after the routing stage.
 *   <li>{@code freerouting.experiment.corridor_in}: file to route with.
 *   <li>{@code freerouting.experiment.corridor_cost}: cost factor k for a move outside the corridor
 *       (default 2: twice the normal cost).
 *   <li>{@code freerouting.experiment.corridor_margin}: cells added around each corridor (1 by
 *       default).
 *   <li>{@code freerouting.experiment.corridor_cell_mm}: cell size when writing (default 1.0).
 * </ul>
 */
public final class OracleCorridors {

  private static final String OUT = System.getProperty("freerouting.experiment.corridor_out");
  private static final String IN = System.getProperty("freerouting.experiment.corridor_in");
  private static final double COST =
      Double.parseDouble(System.getProperty("freerouting.experiment.corridor_cost", "2"));
  private static final int MARGIN =
      Integer.parseInt(System.getProperty("freerouting.experiment.corridor_margin", "1"));
  private static final double CELL_MM =
      Double.parseDouble(System.getProperty("freerouting.experiment.corridor_cell_mm", "1.0"));

  private static CorridorMap loaded;
  private static boolean loadTried;

  private OracleCorridors() {}

  /** The corridors to route with, or null when the experiment is off or the file is unreadable. */
  public static synchronized CorridorMap active() {
    if (IN == null) {
      return null;
    }
    if (!loadTried) {
      loadTried = true;
      try {
        loaded = CorridorMap.read(Path.of(IN)).dilated(MARGIN);
      } catch (IOException | RuntimeException e) {
        loaded = null;
      }
    }
    return loaded;
  }

  /** Extra cost factor for the part of a move outside the corridor: k - 1. */
  public static double extraCostFactor() {
    return Math.max(0, COST - 1);
  }

  /** Writes the corridors of the fully connected nets on {@code board}, when requested. */
  public static void writeIfRequested(RoutingBoard board, Consumer<String> log) {
    if (OUT == null || board == null) {
      return;
    }
    double cell = CELL_MM * 1000.0 * board.communication.getResolution(Unit.UM);
    CorridorMap map = CorridorMap.fromRoutedBoard(board, cell);
    try {
      map.write(Path.of(OUT));
      log.accept(
          "Oracle corridors: wrote " + map.netCount() + " fully connected nets to " + OUT + ".");
    } catch (IOException e) {
      log.accept("Oracle corridors: could not write " + OUT + ": " + e.getMessage());
    }
  }

  /** A line for the log when the experiment routes with corridors, or null. */
  public static String describeActive() {
    CorridorMap map = active();
    if (IN == null) {
      return null;
    }
    if (map == null) {
      return "Oracle corridors: could not read " + IN + "; routing without them.";
    }
    return "Oracle corridors: routing with "
        + map.netCount()
        + " net corridors from "
        + IN
        + " (cost factor "
        + COST
        + ", margin "
        + MARGIN
        + " cells).";
  }
}
