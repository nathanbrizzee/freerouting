package app.freerouting.autoroute.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.geometry.planar.FloatPoint;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Corridor cells, the share of a move outside them, widening, and the file round trip. */
class CorridorMapTest {

  private static CorridorMap horizontalCorridor() {
    // 10 x 10 cells of 10 units; net 3 has the row y = 5 (cells 0..9) on layer 1.
    CorridorMap map = new CorridorMap(0, 0, 10, 10, 10, 2);
    map.markSegment(3, 1, new FloatPoint(0, 55), new FloatPoint(99, 55), 0);
    return map;
  }

  @Test
  void movesAlongTheCorridorAreInside() {
    CorridorMap map = horizontalCorridor();
    assertTrue(map.hasNet(3));
    assertFalse(map.hasNet(4));
    assertEquals(
        0.0, map.outsideFraction(3, 1, new FloatPoint(5, 55), new FloatPoint(95, 55)), 1e-9);
    assertEquals(
        1.0, map.outsideFraction(3, 0, new FloatPoint(5, 55), new FloatPoint(95, 55)), 1e-9);
    assertEquals(
        0.0, map.outsideFraction(4, 1, new FloatPoint(5, 5), new FloatPoint(95, 95)), 1e-9);
  }

  @Test
  void crossingMoveIsPartlyOutside() {
    CorridorMap map = horizontalCorridor();
    double outside = map.outsideFraction(3, 1, new FloatPoint(55, 5), new FloatPoint(55, 95));
    assertTrue(outside > 0.7 && outside < 1.0, "only the crossing cell is inside: " + outside);
    CorridorMap wide = map.dilated(2);
    double wider = wide.outsideFraction(3, 1, new FloatPoint(55, 5), new FloatPoint(55, 95));
    assertTrue(wider < outside, "a margin of 2 cells covers more of the move: " + wider);
  }

  @Test
  void writeAndReadGiveTheSameCorridors(@TempDir Path dir) throws Exception {
    CorridorMap map = horizontalCorridor();
    Path file = dir.resolve("c.corr");
    map.write(file);
    assertTrue(Files.size(file) > 0);
    CorridorMap read = CorridorMap.read(file);
    assertEquals(1, read.netCount());
    FloatPoint a = new FloatPoint(55, 5);
    FloatPoint b = new FloatPoint(55, 95);
    assertEquals(map.outsideFraction(3, 1, a, b), read.outsideFraction(3, 1, a, b), 1e-12);
  }
}
