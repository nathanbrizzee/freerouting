package app.freerouting.autoroute.drill;

import app.freerouting.autoroute.AutorouteDiagnostic;
import app.freerouting.autoroute.expansion.CompleteExpansionRoom;
import app.freerouting.autoroute.expansion.ExpandableObject;
import app.freerouting.autoroute.maze.AutorouteEngine;
import app.freerouting.autoroute.maze.MazeSearchElement;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.searchtree.ShapeSearchTree;
import app.freerouting.datastructures.ShapeTree.TreeEntry;
import app.freerouting.datastructures.Stoppable;
import app.freerouting.geometry.planar.IntBox;
import app.freerouting.geometry.planar.Point;
import app.freerouting.geometry.planar.PolylineArea;
import app.freerouting.geometry.planar.TileShape;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

public class DrillPage implements ExpandableObject {

  /** The shape of the page. */
  public final IntBox shape;

  private final MazeSearchElement[] mazeSearchElements;
  private final RoutingBoard board;

  /** The list of expansion drills on this page. Null, if not yet calculated. */
  private Collection<ExpansionDrill> drills;

  /** The number of the net, for which the drills are calculated. */
  private int netNumber = -1;

  /** Creates a new instance of DrillPage. */
  public DrillPage(IntBox shape, RoutingBoard board) {
    this.shape = shape;
    this.board = board;
    mazeSearchElements = new MazeSearchElement[board.getLayerCount()];
    for (int i = 0; i < mazeSearchElements.length; i++) {
      mazeSearchElements[i] = new MazeSearchElement();
    }
  }

  /**
   * Looks if drillShape contains the center of a drillable Pin on layer. Returns null if no. such
   * Pin was found.
   */
  private static Point calcPinCenterInDrill(TileShape drillShape, int layer, RoutingBoard board) {
    Collection<Item> overlappingItems = board.overlappingItems(drillShape, layer);
    Point result = null;
    for (Item currentItem : overlappingItems) {
      if (currentItem instanceof Pin currentPin) {
        if (currentPin.drillAllowed() && drillShape.containsInside(currentPin.getCenter())) {
          result = currentPin.getCenter();
        }
      }
    }
    return result;
  }

  /** Returns the drills on this page for through vias; attachSmd allows drilling to SMD pins. */
  public Collection<ExpansionDrill> getDrills(AutorouteEngine autorouteEngine, boolean attachSmd) {
    return getDrills(autorouteEngine, attachSmd, null);
  }

  /**
   * Returns the drills on this page; attachSmd allows drilling to SMD pins. The list holds the
   * distinct layer ranges {first, last} of the net's vias. The drills of a range are cut only by
   * the copper on its layers, so a blind or buried via gets sites where a through via has none.
   * Null means one range over the whole stack, as on a board with through vias only.
   */
  public Collection<ExpansionDrill> getDrills(
      AutorouteEngine autorouteEngine, boolean attachSmd, java.util.List<int[]> viaSpans) {
    if (this.drills == null || autorouteEngine.getNetNumber() != this.netNumber) {
      this.netNumber = autorouteEngine.getNetNumber();
      this.drills = new LinkedList<>();
      int lastBoardLayer = this.board.getLayerCount() - 1;
      java.util.List<int[]> spans =
          viaSpans == null || viaSpans.isEmpty()
              ? java.util.List.of(new int[] {0, lastBoardLayer})
              : viaSpans;
      for (int[] span : spans) {
        if (!addDrills(autorouteEngine, attachSmd, span[0], span[1])) {
          // Interrupted, not failed: do not cache an empty page for later searches.
          this.drills = null;
          return new LinkedList<>();
        }
      }
    }
    return this.drills;
  }

  /** Adds the drills of one layer range to the page. Returns false when the search was stopped. */
  private boolean addDrills(
      AutorouteEngine autorouteEngine, boolean attachSmd, int drillFirstLayer, int drillLastLayer) {
    {
      ShapeSearchTree searchTree = autorouteEngine.autorouteSearchTree;
      Collection<TreeEntry> overlaps = new LinkedList<>();
      if (drillFirstLayer == 0 && drillLastLayer == this.board.getLayerCount() - 1) {
        searchTree.overlappingTreeEntries(this.shape, -1, overlaps);
      } else {
        for (int layer = drillFirstLayer; layer <= drillLastLayer; layer++) {
          searchTree.overlappingTreeEntries(this.shape, layer, overlaps);
        }
      }
      Collection<TileShape> cutoutShapes = new LinkedList<>();
      // drills on top of existing vias are used in the ripup algorithm
      TileShape prevObstacleShape = IntBox.EMPTY;
      for (TreeEntry currentEntry : overlaps) {
        if (!(currentEntry.object instanceof Item currentItem)) {
          continue;
        }
        if (currentItem.isDrillable(this.netNumber)) {
          continue;
        }
        if (currentItem instanceof Pin pin) {
          if (attachSmd && pin.drillAllowed() && pin.containsNet(this.netNumber)) {
            continue;
          }
        }
        TileShape currentObstacleShape =
            currentItem.getTreeShape(searchTree, currentEntry.shapeIndexInObject);
        if (!prevObstacleShape.contains(currentObstacleShape)) {
          // Checked to avoid multiple cutout for example for vias with the same shape on all
          // layers.
          TileShape currentCutoutShape = currentObstacleShape.intersection(this.shape);
          if (currentCutoutShape.dimension() == 2) {
            cutoutShapes.add(currentCutoutShape);
          }
        }
        prevObstacleShape = currentObstacleShape;
      }
      TileShape[] holes = new TileShape[cutoutShapes.size()];
      Iterator<TileShape> it = cutoutShapes.iterator();
      for (int i = 0; i < holes.length; i++) {
        holes[i] = it.next();
      }
      PolylineArea shapeWithHoles = new PolylineArea(this.shape, holes);
      // Use the engine's stop check, which includes the per-connection time limit. The split
      // has no work cap, and a page with many holes (a footprint full of drill holes) could
      // otherwise run far past the connection's budget.
      TileShape[] drillShapes = shapeWithHoles.splitToConvex(new EngineStop(autorouteEngine));
      if (drillShapes == null) {
        return !autorouteEngine.isStopRequested();
      }

      // Use the center points of these drill shapes to try making a via.
      for (int i = 0; i < drillShapes.length; i++) {
        TileShape currentDrillShape = drillShapes[i];
        Point pinCenter = null;
        if (attachSmd) {
          pinCenter =
              calcPinCenterInDrill(currentDrillShape, drillFirstLayer, autorouteEngine.board);
          if (pinCenter == null) {
            pinCenter =
                calcPinCenterInDrill(currentDrillShape, drillLastLayer, autorouteEngine.board);
          }
        }
        Point currentDrillLocation = pinCenter;
        if (currentDrillLocation == null) {
          currentDrillLocation = currentDrillShape.centreOfGravity().round();
        }
        ExpansionDrill newDrill =
            new ExpansionDrill(
                currentDrillShape, currentDrillLocation, drillFirstLayer, drillLastLayer);
        if (newDrill.calculateExpansionRooms(autorouteEngine)) {
          this.drills.add(newDrill);
        }
        if (pinCenter != null) {
          Point centreOfGravity = currentDrillShape.centreOfGravity().round();
          if (!centreOfGravity.equals(pinCenter)) {
            ExpansionDrill cogDrill =
                new ExpansionDrill(
                    currentDrillShape, centreOfGravity, drillFirstLayer, drillLastLayer);
            if (cogDrill.calculateExpansionRooms(autorouteEngine)) {
              this.drills.add(cogDrill);
            }
          }
        }
      }
    }
    return true;
  }

  @Override
  public TileShape getShape() {
    return this.shape;
  }

  @Override
  public int getDimension() {
    return 2;
  }

  @Override
  public int mazeSearchElementCount() {
    return this.mazeSearchElements.length;
  }

  @Override
  public MazeSearchElement getMazeSearchElement(int index) {
    return this.mazeSearchElements[index];
  }

  /** Resets all drills of this page for autorouting the next connection. */
  @Override
  public void reset() {
    if (this.drills != null) {
      for (ExpansionDrill currentDrill : this.drills) {
        currentDrill.reset();
      }
    }
    for (MazeSearchElement currentInfo : mazeSearchElements) {
      currentInfo.reset();
    }
  }

  /**
   * Invalidates the drills of this page so that they are recalculated at the next call of
   * getDrills().
   */
  public void invalidate() {
    this.drills = null;
  }

  /** Emits optional diagnostics for the drills on this page. */
  public void emitDiagnostics(AutorouteDiagnostic.Sink sink, double intensity) {
    if (sink == null || intensity <= 0 || drills == null) {
      return;
    }
    for (ExpansionDrill currentDrill : drills) {
      currentDrill.emitDiagnostic(sink, intensity);
    }
  }

  @Override
  public CompleteExpansionRoom otherRoom(CompleteExpansionRoom room) {
    return null;
  }

  @Override
  public int getId() {
    // Stable hash of shape and netNumber
    return 31 * shape.getId() + netNumber;
  }

  /** Adapts the engine's stop check, which includes its time limit, to the Stoppable interface. */
  private record EngineStop(AutorouteEngine engine) implements Stoppable {
    @Override
    public void requestStop() {
      if (engine.stoppableThread != null) {
        engine.stoppableThread.requestStop();
      }
    }

    @Override
    public boolean isStopRequested() {
      return engine.isStopRequested();
    }
  }
}
