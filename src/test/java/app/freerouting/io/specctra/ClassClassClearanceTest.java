package app.freerouting.io.specctra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.board.facade.RoutingBoard;
import app.freerouting.board.model.items.Pin;
import app.freerouting.io.BoardReadResult;
import app.freerouting.rules.ClearanceMatrix;
import app.freerouting.rules.NetClass;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * A class_class rule between a high-voltage class and a low-voltage class must reach every item the
 * router places or checks against: traces, vias and pads of both classes.
 */
class ClassClassClearanceTest {

  // KiCad-style export: a structure rule with default_smd and smd_smd types, both classes with
  // their own rule, and a class_class rule of 0.51 mm between them.
  private static final String DSN =
      """
      (pcb classclass
        (resolution um 10)
        (unit um)
        (structure
          (layer F.Cu (type signal))
          (layer B.Cu (type signal))
          (boundary (rect pcb 0 0 50000 50000))
          (via "Via[0-1]_600:300_um")
          (rule (width 200) (clearance 200) (clearance 200 (type default_smd))
                (clearance 50 (type smd_smd))))
        (placement
          (component R_0603 (place R1 10000 10000 front 0))
          (component R_0603 (place R2 20000 10000 front 0)))
        (library
          (image R_0603
            (pin Pad_800x950_um 1 -800 0)
            (pin Pad_800x950_um 2 800 0))
          (padstack Pad_800x950_um (shape (rect F.Cu -400 -475 400 475)) (attach off))
          (padstack "Via[0-1]_600:300_um"
            (shape (circle F.Cu 600)) (shape (circle B.Cu 600)) (attach off)))
        (network
          (net LV1 (pins R1-1 R2-1))
          (net HV1 (pins R1-2 R2-2))
          (class kicad_default "" LV1
            (circuit (use_via "Via[0-1]_600:300_um"))
            (rule (width 200) (clearance 200)))
          (class HV HV1
            (circuit (use_via "Via[0-1]_600:300_um"))
            (rule (width 200) (clearance 200)))
          (class_class (classes HV kicad_default) (rule (clearance 510)))))
      """;

  private static RoutingBoard loadBoard(String dsn) {
    BoardReadResult result =
        DsnReader.readBoard(
            new ByteArrayInputStream(dsn.getBytes(StandardCharsets.UTF_8)), null, null);
    assertTrue(result instanceof BoardReadResult.Success, "board should load");
    return (RoutingBoard) ((BoardReadResult.Success) result).board();
  }

  @Test
  void classClassRuleReachesTracesViasAndPads() {
    RoutingBoard board = loadBoard(DSN);
    ClearanceMatrix matrix = board.rules.clearanceMatrix;
    NetClass hv = board.rules.netClasses.get("HV");
    NetClass lv = board.rules.getDefaultNetClass();

    int hvTrace = hv.getTraceClearanceClass();
    int hvVia = hv.getViaRule().getVia(0).getClearanceClassIndex();
    int lvTrace = lv.getTraceClearanceClass();
    int lvVia = lv.getViaRule().getVia(0).getClearanceClassIndex();
    int lvPad = -1;
    int hvPad = -1;
    for (Pin pin : board.getPins()) {
      String net = board.rules.nets.get(pin.getNetNumber(0)).name;
      if ("LV1".equals(net)) {
        lvPad = pin.clearanceClassIndex();
      } else if ("HV1".equals(net)) {
        hvPad = pin.clearanceClassIndex();
      }
    }
    int[][] pairs = {
      {hvTrace, lvTrace}, {hvTrace, lvPad}, {hvTrace, lvVia},
      {hvVia, lvTrace}, {hvVia, lvPad}, {hvVia, lvVia},
      {hvPad, lvTrace}, {hvPad, lvPad}, {hvPad, lvVia}
    };
    String[] names = {
      "HV trace-LV trace", "HV trace-LV pad", "HV trace-LV via",
      "HV via-LV trace", "HV via-LV pad", "HV via-LV via",
      "HV pad-LV trace", "HV pad-LV pad", "HV pad-LV via"
    };
    StringBuilder report = new StringBuilder();
    boolean allOk = true;
    for (int i = 0; i < pairs.length; i++) {
      // Board units are 0.1 um at (resolution um 10); 510 um is 5100 units.
      int value = matrix.getValue(pairs[i][0], pairs[i][1], 0, false);
      report
          .append(names[i])
          .append(" [")
          .append(matrix.getName(pairs[i][0]))
          .append(" / ")
          .append(matrix.getName(pairs[i][1]))
          .append("] = ")
          .append(value)
          .append('\n');
      allOk &= value >= 5100;
    }
    System.out.println(report);
    assertTrue(allOk, "class_class 510 um missing for some pairs:\n" + report);
  }

  @Test
  void zeroClassClassRuleIsExactForOwnClassesAndNeverLowersSharedOnes() {
    // Held-net workflows use clearance 0 between twin classes. That value must stay exact for
    // the classes named after the net classes, and must not lower the shared "smd" class.
    RoutingBoard board = loadBoard(DSN.replace("(rule (clearance 510))", "(rule (clearance 0))"));
    ClearanceMatrix matrix = board.rules.clearanceMatrix;
    int hv = matrix.getNo("HV");
    int lvOwn = matrix.getNo("default");
    int smd = matrix.getNo("smd");
    assertEquals(0, matrix.getValue(hv, lvOwn, 0, false));
    assertEquals(2000, matrix.getValue(hv, smd, 0, false));
  }
}
