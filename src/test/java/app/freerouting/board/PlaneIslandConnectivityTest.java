package app.freerouting.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.TestFixtures;
import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.ConductionArea;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.drc.DesignRulesChecker;
import app.freerouting.io.BoardReadResult;
import app.freerouting.io.specctra.DsnReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A copper pour that a trace cuts into two islands, and an inner plane of the same net that joins
 * them through a via in each island: the two pads, one in each island, are one connection. Walking
 * the connected items from either pad has to give the same set, whichever way it goes.
 */
class PlaneIslandConnectivityTest {

  private static RoutingBoard load() throws Exception {
    try (InputStream in = new FileInputStream(TestFixtures.resolveFile("PlaneIslandBridge.dsn"))) {
      BoardReadResult result = DsnReader.readBoard(in, null, null);
      return switch (result) {
        case BoardReadResult.Success s -> (RoutingBoard) s.board();
        case BoardReadResult.OutlineMissing o -> (RoutingBoard) o.board();
        default -> throw new IllegalStateException("The fixture does not load: " + result);
      };
    }
  }

  private static List<Pin> gndPins(RoutingBoard board, int gnd) {
    List<Pin> pins = new ArrayList<>();
    for (Item item : board.getItems()) {
      if (item instanceof Pin pin && pin.containsNet(gnd)) {
        pins.add(pin);
      }
    }
    return pins;
  }

  @Test
  void padsInDifferentIslandsOfAPourAreConnectedThroughTheInnerPlane() throws Exception {
    RoutingBoard board = load();
    int gnd = board.rules.nets.get("GND", 1).netNumber;
    ConductionArea outer = null;
    for (Item item : board.getItems()) {
      if (item instanceof ConductionArea area && area.containsNet(gnd) && area.getLayer() == 0) {
        outer = area;
      }
    }
    assertTrue(outer != null && outer.isFractured(), "the trace has to cut the pour in two");

    List<Pin> pins = gndPins(board, gnd);
    assertEquals(2, pins.size());
    Set<Item> fromFirst = pins.get(0).getConnectedSet(gnd);
    Set<Item> fromSecond = pins.get(1).getConnectedSet(gnd);
    assertTrue(fromFirst.contains(pins.get(1)), "the first pad reaches the second");
    assertTrue(fromSecond.contains(pins.get(0)), "the second pad reaches the first");
    assertEquals(fromFirst, fromSecond, "the walk must not depend on where it starts");
    assertEquals(0, new DesignRulesChecker(board, null).getAllAirlines().length);
  }
}
