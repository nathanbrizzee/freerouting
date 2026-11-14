package app.freerouting.autoroute.pipeline;

import app.freerouting.autoroute.maze.AutorouteControl;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ComponentOutline;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.DrillItem;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.items.Trace;
import app.freerouting.board.model.items.Via;
import app.freerouting.board.model.items.ViaObstacleArea;
import app.freerouting.board.model.structure.FixedState;
import app.freerouting.core.library.Padstack;
import app.freerouting.geometry.planar.ConvexShape;
import app.freerouting.geometry.planar.FloatPoint;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.TileShape;
import app.freerouting.rules.Net;
import app.freerouting.rules.ViaInfo;
import app.freerouting.settings.RouterSettings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the copper groups of a net that can never be joined to the rest of it, because copper the
 * router cannot move walls them off: other nets' pads, keepouts, other nets' planes, the board edge
 * and copper fixed by the input file. The passes then skip the searches that could only end at such
 * a group; each one cost a full search budget in every pass and could never succeed.
 *
 * <p>The check errs only towards "reachable", so a group it calls unreachable is unreachable:
 *
 * <ul>
 *   <li>the whole board is searched, not a window;
 *   <li>a trace is taken at the narrowest width the router may give it (a pin neck: the board's
 *       minimum trace width, else half the net class width);
 *   <li>every obstacle is grown by its clearance plus that half width and then shrunk by half a
 *       cell diagonal, so the cells a real trace's centre line passes through are all free and
 *       joined (eight neighbours);
 *   <li>a layer change is allowed wherever one of the net's vias clears every layer of its span,
 *       measured the same way with the via's narrowest pad;
 *   <li>copper the router may move is left out: unfixed traces and vias, and traces and vias fixed
 *       only by a stage (pairs, direct pin routes).
 * </ul>
 *
 * <p>Deterministic: the result depends only on the board and the settings. The work is bounded by a
 * cell budget; nets left unchecked when it runs out count as reachable.
 */
final class UnreachableGroups {

  /** Cells per layer at most; a larger board gets coarser cells, which only weakens the check. */
  static final int MAX_CELLS_PER_LAYER = 1_000_000;

  /** Cells painted and flooded in all nets together, at most. */
  static final long DEFAULT_CELL_BUDGET = 400_000_000L;

  private static final int[][] DIRECTIONS = {
    {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
  };

  /** Per checked net: pin id to the index of its reachability cluster. */
  private final Map<Integer, Map<Integer, Integer>> clusters = new HashMap<>();

  /**
   * Per split net: one pin of each cluster other than the largest; each is one connection that can
   * never be made.
   */
  private final Map<Integer, List<Pin>> unreachable = new TreeMap<>();

  private int checkedNets;
  private int skippedNets;
  private long cellsUsed;

  private UnreachableGroups() {}

  /**
   * Checks every net with open connections (planes and nets the autorouter ignores aside), in net
   * order, within {@code cellBudget} cells.
   */
  static UnreachableGroups check(RoutingBoard board, RouterSettings settings, long cellBudget) {
    UnreachableGroups result = new UnreachableGroups();
    Map<Integer, List<Pin>> pinsByNet = new TreeMap<>();
    for (Pin pin : board.getPins()) {
      if (pin.netCount() == 1) {
        pinsByNet.computeIfAbsent(pin.getNetNumber(0), k -> new ArrayList<>()).add(pin);
      }
    }
    for (Map.Entry<Integer, List<Pin>> entry : pinsByNet.entrySet()) {
      int net = entry.getKey();
      Net rulesNet = board.rules.nets.get(net);
      if (rulesNet == null
          || rulesNet.containsPlane()
          || (rulesNet.getNetClass() != null && rulesNet.getNetClass().isIgnoredByAutorouter)) {
        continue;
      }
      List<Pin> pins = entry.getValue();
      pins.sort(Comparator.comparingInt(Item::getId));
      List<Set<Item>> groups = new ArrayList<>();
      Map<Integer, Integer> groupOfPin = new HashMap<>();
      for (Pin pin : pins) {
        if (groupOfPin.containsKey(pin.getId())) {
          continue;
        }
        Set<Item> group = pin.getConnectedSet(net);
        int index = groups.size();
        groups.add(group);
        for (Item item : group) {
          if (item instanceof Pin) {
            groupOfPin.put(item.getId(), index);
          }
        }
      }
      if (groups.size() < 2) {
        continue;
      }
      if (result.cellsUsed >= cellBudget) {
        result.skippedNets++;
        continue;
      }
      NetCheck netCheck = new NetCheck(board, settings, net, groups);
      int[] root = netCheck.run();
      result.cellsUsed += netCheck.cellsUsed;
      result.checkedNets++;
      if (root == null) {
        continue; // no layer or no grid: unknown, so reachable
      }
      Map<Integer, Integer> pinCluster = new HashMap<>();
      int[] clusterSize = new int[groups.size()];
      for (int g = 0; g < groups.size(); g++) {
        clusterSize[root[g]]++;
      }
      for (Map.Entry<Integer, Integer> pinGroup : groupOfPin.entrySet()) {
        pinCluster.put(pinGroup.getKey(), root[pinGroup.getValue()]);
      }
      result.clusters.put(net, pinCluster);
      // The largest cluster is the net's main body (ties: the lowest root); each other cluster is
      // one connection that can never be made, named by its first group's first pin.
      int main = 0;
      for (int g = 0; g < groups.size(); g++) {
        if (clusterSize[g] > clusterSize[main]) {
          main = g;
        }
      }
      for (int g = 0; g < groups.size(); g++) {
        if (root[g] == g && g != main) {
          Pin first = null;
          for (Item item : groups.get(g)) {
            if (item instanceof Pin pin && (first == null || pin.getId() < first.getId())) {
              first = pin;
            }
          }
          if (first != null) {
            result.unreachable.computeIfAbsent(net, k -> new ArrayList<>()).add(first);
          }
        }
      }
    }
    return result;
  }

  /**
   * Whether the item's copper group may still be joined to another group of the net: false only
   * when the check proved that every group it could reach is joined to it already.
   */
  boolean canStillConnect(RoutingBoard board, Item item, int net) {
    Map<Integer, Integer> pinCluster = clusters.get(net);
    if (pinCluster == null) {
      return true;
    }
    Set<Item> own = item.getConnectedSet(net);
    Integer mine = null;
    for (Item member : own) {
      if (member instanceof Pin) {
        mine = pinCluster.get(member.getId());
        if (mine != null) {
          break;
        }
      }
    }
    if (mine == null) {
      return true;
    }
    for (Item other : board.getConnectableItems(net)) {
      if (other instanceof Pin && !own.contains(other)) {
        Integer cluster = pinCluster.get(other.getId());
        if (cluster == null || cluster.equals(mine)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The number of connections that can never be made: per net, its clusters less one. */
  int unreachableConnectionCount() {
    int count = 0;
    for (List<Pin> pins : unreachable.values()) {
      count += pins.size();
    }
    return count;
  }

  /** Whether any net was split into groups that cannot all be joined. */
  boolean isEmpty() {
    return unreachable.isEmpty() && clusters.values().stream().noneMatch(UnreachableGroups::split);
  }

  private static boolean split(Map<Integer, Integer> pinCluster) {
    return pinCluster.values().stream().distinct().count() > 1;
  }

  /** The nets split into groups that cannot all be joined. */
  int splitNetCount() {
    return (int) clusters.values().stream().filter(UnreachableGroups::split).count();
  }

  /**
   * One log line: how many groups and nets are walled off, the first pins by name, and the work.
   */
  String describe(RoutingBoard board) {
    StringBuilder text = new StringBuilder();
    text.append(
        String.format(
            java.util.Locale.ROOT,
            "Unreachable connections: %d connection(s) on %d net(s) can never be made past copper"
                + " the router cannot move (pads, keepouts, planes, the board edge, input-fixed"
                + " copper); the passes skip them. %d net(s) checked",
            unreachableConnectionCount(),
            splitNetCount(),
            checkedNets));
    if (skippedNets > 0) {
      text.append(", ").append(skippedNets).append(" left unchecked at the cell budget");
    }
    text.append(", ").append(cellsUsed).append(" cells.");
    int shown = 0;
    for (Map.Entry<Integer, List<Pin>> entry : unreachable.entrySet()) {
      Net net = board.rules.nets.get(entry.getKey());
      for (Pin pin : entry.getValue()) {
        if (shown == 20) {
          text.append(" ...");
          return text.toString();
        }
        text.append(shown == 0 ? " " : ", ")
            .append(pinName(board, pin))
            .append(" (")
            .append(net != null ? net.name : "net " + entry.getKey())
            .append(')');
        shown++;
      }
    }
    return text.toString();
  }

  private static String pinName(RoutingBoard board, Pin pin) {
    return pin.componentName() + "-" + pin.name();
  }

  /** Whether the router may never move or remove the item during the passes. */
  static boolean isImmovable(Item item) {
    if (item instanceof Trace || item instanceof Via) {
      return item.getFixedState() == FixedState.INPUT_FIXED || item.hasIgnoredNets();
    }
    return true;
  }

  /** The grid and flood for one net. */
  private static final class NetCheck {
    private final RoutingBoard board;
    private final int net;
    private final List<Set<Item>> groups;
    private final AutorouteControl ctrl;
    private final List<Integer> layers = new ArrayList<>();
    private final int[] layerIndex;
    private double cell;
    private double halfDiagonal;
    private int cols;
    private int rows;
    private double originX;
    private double originY;
    private boolean[][] blocked;
    private int checkHalfWidth;
    private int[] label;
    private int[] parent;
    private int[] queue;
    private int tail;
    private int sets;
    long cellsUsed;

    NetCheck(RoutingBoard board, RouterSettings settings, int net, List<Set<Item>> groups) {
      this.board = board;
      this.net = net;
      this.groups = groups;
      this.ctrl = new AutorouteControl(board, net, settings);
      this.layerIndex = new int[ctrl.layerCount];
      java.util.Arrays.fill(layerIndex, -1);
      for (int layer = 0; layer < ctrl.layerCount; layer++) {
        if (ctrl.layerActive[layer]) {
          layerIndex[layer] = layers.size();
          layers.add(layer);
        }
      }
    }

    /** Returns each group's cluster root (the smallest group index joined to it), or null. */
    int[] run() {
      IntBox box = board.getBoundingBox();
      if (layers.isEmpty() || box == null || box.isEmpty()) {
        return null;
      }
      int classHalf = Integer.MAX_VALUE;
      for (int layer : layers) {
        classHalf = Math.min(classHalf, ctrl.traceHalfWidth[layer]);
      }
      int minHalf = board.rules.getMinAllowedTraceHalfWidth();
      checkHalfWidth = Math.max(1, minHalf > 0 ? Math.min(classHalf, minHalf) : classHalf / 2);
      int smallestNeed = Integer.MAX_VALUE;
      for (int layer : layers) {
        smallestNeed =
            Math.min(
                smallestNeed,
                board.clearanceValue(
                        ctrl.traceClearanceClassIndex, ctrl.traceClearanceClassIndex, layer)
                    + checkHalfWidth);
      }
      double width = box.ur.x - box.ll.x;
      double height = box.ur.y - box.ll.y;
      cell = Math.max(1.0, smallestNeed / 3.0);
      cell = Math.max(cell, Math.sqrt(width * height / MAX_CELLS_PER_LAYER));
      halfDiagonal = cell * Math.sqrt(0.5);
      cols = (int) Math.ceil(width / cell) + 1;
      rows = (int) Math.ceil(height / cell) + 1;
      originX = box.ll.x;
      originY = box.ll.y;
      int cells = cols * rows;
      blocked = new boolean[layers.size()][cells];
      List<Item> obstacles = new ArrayList<>();
      for (Item item : board.getItems()) {
        if (!(item instanceof ComponentOutline) && !item.containsNet(net) && isImmovable(item)) {
          obstacles.add(item);
        }
      }
      for (Item item : obstacles) {
        if (!isTraceObstacle(item)) {
          continue;
        }
        for (int li = 0; li < layers.size(); li++) {
          int layer = layers.get(li);
          double need =
              board.clearanceValue(ctrl.traceClearanceClassIndex, item.clearanceClassIndex(), layer)
                  + checkHalfWidth
                  - halfDiagonal;
          paint(item, layer, need, blocked[li]);
        }
      }
      List<boolean[]> viaSpans = new ArrayList<>();
      List<int[]> viaLayers = new ArrayList<>();
      if (ctrl.viasAllowed && ctrl.viaRule != null && layers.size() > 1) {
        for (int v = 0; v < ctrl.viaRule.viaCount(); v++) {
          ViaInfo via = ctrl.viaRule.getVia(v);
          Padstack padstack = via.getPadstack();
          boolean[] free = new boolean[cells];
          java.util.Arrays.fill(free, true);
          for (int layer = padstack.fromLayer(); layer <= padstack.toLayer(); layer++) {
            ConvexShape shape = padstack.getShape(layer);
            if (shape == null) {
              continue;
            }
            double radius = 0.5 * shape.minWidth();
            boolean[] taken = new boolean[cells];
            for (Item item : obstacles) {
              double need =
                  board.clearanceValue(
                          via.getClearanceClassIndex(), item.clearanceClassIndex(), layer)
                      + radius
                      - halfDiagonal;
              paint(item, layer, need, taken);
            }
            for (int i = 0; i < cells; i++) {
              free[i] &= !taken[i];
            }
            cellsUsed += cells;
          }
          viaSpans.add(free);
          viaLayers.add(new int[] {padstack.fromLayer(), padstack.toLayer()});
        }
      }
      return flood(viaSpans, viaLayers);
    }

    private boolean isTraceObstacle(Item item) {
      if (item instanceof ViaObstacleArea) {
        return false;
      }
      if (item instanceof ConductionArea area) {
        return area.isTraceObstacle(net);
      }
      return item.isTraceObstacle(net);
    }

    /** Marks the cells closer than {@code need} to the item's shapes on the layer. */
    private void paint(Item item, int layer, double need, boolean[] mask) {
      if (need <= 0) {
        return;
      }
      for (int i = 0; i < item.tileShapeCount(); i++) {
        if (item.shapeLayer(i) != layer) {
          continue;
        }
        TileShape shape = item.getTileShape(i);
        IntBox box = shape.boundingBox();
        int c0 = Math.max(0, (int) Math.floor((box.ll.x - need - originX) / cell));
        int c1 = Math.min(cols - 1, (int) Math.ceil((box.ur.x + need - originX) / cell));
        int r0 = Math.max(0, (int) Math.floor((box.ll.y - need - originY) / cell));
        int r1 = Math.min(rows - 1, (int) Math.ceil((box.ur.y + need - originY) / cell));
        for (int r = r0; r <= r1; r++) {
          for (int c = c0; c <= c1; c++) {
            int index = r * cols + c;
            cellsUsed++;
            if (!mask[index]
                && shape.distance(new FloatPoint(originX + c * cell, originY + r * cell)) < need) {
              mask[index] = true;
            }
          }
        }
      }
    }

    /** Floods from every group at once and joins the groups whose floods meet. */
    private int[] flood(List<boolean[]> viaSpans, List<int[]> viaLayers) {
      int cells = cols * rows;
      int n = layers.size();
      label = new int[n * cells];
      java.util.Arrays.fill(label, -1);
      parent = new int[groups.size()];
      for (int g = 0; g < parent.length; g++) {
        parent[g] = g;
      }
      sets = parent.length;
      queue = new int[n * cells];
      int head = 0;
      tail = 0;
      for (int g = 0; g < groups.size(); g++) {
        for (Item item : groups.get(g)) {
          for (int li = 0; li < n; li++) {
            for (int index : inside(item, layers.get(li))) {
              int state = li * cells + index;
              blocked[li][index] = false;
              visit(state, g);
            }
          }
        }
      }
      while (head < tail && sets > 1) {
        int state = queue[head++];
        cellsUsed++;
        int li = state / cells;
        int index = state % cells;
        int c = index % cols;
        int r = index / cols;
        int g = label[state];
        for (int[] d : DIRECTIONS) {
          int nc = c + d[0];
          int nr = r + d[1];
          if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) {
            continue;
          }
          int next = nr * cols + nc;
          if (blocked[li][next]) {
            continue;
          }
          visit(li * cells + next, g);
        }
        int layer = layers.get(li);
        for (int v = 0; v < viaSpans.size(); v++) {
          int[] span = viaLayers.get(v);
          if (layer < span[0] || layer > span[1] || !viaSpans.get(v)[index]) {
            continue;
          }
          for (int other = span[0]; other <= span[1]; other++) {
            int oli = layerIndex[other];
            if (oli >= 0 && oli != li && !blocked[oli][index]) {
              visit(oli * cells + index, g);
            }
          }
        }
      }
      int[] root = new int[groups.size()];
      for (int g = 0; g < root.length; g++) {
        root[g] = find(parent, g);
      }
      return root;
    }

    /** Labels a free state with group g's flood, or joins g with the flood already there. */
    private void visit(int state, int g) {
      if (label[state] < 0) {
        label[state] = g;
        queue[tail++] = state;
      } else if (union(parent, label[state], g)) {
        sets--;
      }
    }

    /** The cells inside the item's copper on the layer, and a drill item's centre cell. */
    private List<Integer> inside(Item item, int layer) {
      List<Integer> result = new ArrayList<>();
      if (item instanceof ConductionArea) {
        return result;
      }
      for (int i = 0; i < item.tileShapeCount(); i++) {
        if (item.shapeLayer(i) != layer) {
          continue;
        }
        TileShape shape = item.getTileShape(i);
        IntBox box = shape.boundingBox();
        int c0 = Math.max(0, (int) Math.ceil((box.ll.x - originX) / cell));
        int c1 = Math.min(cols - 1, (int) Math.floor((box.ur.x - originX) / cell));
        int r0 = Math.max(0, (int) Math.ceil((box.ll.y - originY) / cell));
        int r1 = Math.min(rows - 1, (int) Math.floor((box.ur.y - originY) / cell));
        for (int r = r0; r <= r1; r++) {
          for (int c = c0; c <= c1; c++) {
            if (shape.distance(new FloatPoint(originX + c * cell, originY + r * cell)) <= 0) {
              result.add(r * cols + c);
            }
          }
        }
      }
      if (item instanceof DrillItem drill && item.isOnLayer(layer)) {
        FloatPoint centre = drill.getCenter().toFloat();
        int c = (int) Math.round((centre.x - originX) / cell);
        int r = (int) Math.round((centre.y - originY) / cell);
        if (c >= 0 && r >= 0 && c < cols && r < rows) {
          result.add(r * cols + c);
        }
      }
      return result;
    }

    private static int find(int[] parent, int g) {
      while (parent[g] != g) {
        parent[g] = parent[parent[g]];
        g = parent[g];
      }
      return g;
    }

    /**
     * Joins two sets, the smaller index becoming the root, so roots are deterministic. Returns
     * whether they were apart.
     */
    private static boolean union(int[] parent, int a, int b) {
      int ra = find(parent, a);
      int rb = find(parent, b);
      if (ra == rb) {
        return false;
      }
      parent[Math.max(ra, rb)] = Math.min(ra, rb);
      return true;
    }
  }
}
