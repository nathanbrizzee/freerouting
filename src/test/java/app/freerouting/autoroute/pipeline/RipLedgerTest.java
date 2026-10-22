package app.freerouting.autoroute.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.freerouting.board.model.items.Item;
import java.util.List;
import org.junit.jupiter.api.Test;

class RipLedgerTest {

  private static Item onNet(int net) {
    Item item = mock(Item.class);
    when(item.netCount()).thenReturn(1);
    when(item.getNetNumber(0)).thenReturn(net);
    return item;
  }

  @Test
  void countsPairsThatRipEachOtherAcrossPasses() {
    RipLedger ledger = new RipLedger();
    ledger.startPass();
    assertNull(ledger.describe());
    ledger.record(1, List.of(onNet(2), onNet(2)));
    ledger.record(3, List.of(onNet(4)));
    assertEquals(0, ledger.backAndForthPairs());
    assertTrue(ledger.describe().startsWith("Rip-up: 3 items of 2 other nets ripped by 2 "));

    ledger.startPass();
    ledger.record(2, List.of(onNet(1)));
    ledger.record(2, List.of(onNet(1)));
    ledger.record(4, List.of(onNet(5)));
    assertEquals(1, ledger.backAndForthPairs());
    assertTrue(ledger.describe().endsWith(" so far, 1 of them new in this pass."));

    ledger.startPass();
    ledger.record(1, List.of(onNet(2)));
    assertEquals(1, ledger.backAndForthPairs());
    assertTrue(ledger.describe().endsWith(" so far, 0 of them new in this pass."));
  }

  @Test
  void ignoresItemsOfTheRoutedNet() {
    RipLedger ledger = new RipLedger();
    ledger.startPass();
    ledger.record(1, List.of(onNet(1)));
    assertNull(ledger.describe());
  }
}
