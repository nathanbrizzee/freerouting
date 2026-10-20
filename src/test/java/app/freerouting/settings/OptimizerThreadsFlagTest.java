package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.settings.sources.CliSettings;
import app.freerouting.settings.sources.DefaultSettings;
import org.junit.jupiter.api.Test;

/** {@code -mt N} sets the optimizer pool, and {@code -mt 0} turns the optimizer off. */
class OptimizerThreadsFlagTest {

  private static RouterSettings merged(String... args) {
    return new SettingsMerger(new DefaultSettings(), new CliSettings(args)).merge();
  }

  @Test
  void zeroTurnsTheOptimizerOff() {
    assertFalse(merged("-de", "a.dsn", "-do", "a.ses", "-mt", "0").getRunOptimizer());
  }

  @Test
  void countSetsTheOptimizerPool() {
    RouterSettings settings = merged("-de", "a.dsn", "-do", "a.ses", "-mt", "3");
    assertTrue(settings.getRunOptimizer());
    assertEquals(3, settings.optimizer.maxThreads);
  }
}
