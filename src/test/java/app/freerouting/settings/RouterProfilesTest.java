package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.settings.sources.CliSettings;
import app.freerouting.settings.sources.DefaultSettings;
import org.junit.jupiter.api.Test;

/** A profile replaces the hardcoded defaults; every other source still overrides it. */
class RouterProfilesTest {

  private static RouterSettings merged(String... cliArgs) {
    return new SettingsMerger(new DefaultSettings(), new CliSettings(cliArgs)).merge();
  }

  @Test
  void standardIsTheDefault() {
    RouterSettings settings = merged();
    assertEquals("standard", settings.profile);
    assertEquals("bga", settings.fanout.strategy);
    assertTrue(settings.fanout.protectEscapesGridOnly);
    assertFalse(settings.autorouter.forceViaInPad, "a profile can switch a setting off");
    assertTrue(settings.optimizer.enabled);
    assertFalse(settings.autorouter.gridFinisher, "not before it is measured");
  }

  @Test
  void classicKeepsTheHardcodedDefaults() {
    RouterSettings settings = merged("--router.profile=classic");
    RouterSettings hardcoded = new DefaultSettings().getSettings();
    assertEquals("classic", settings.profile);
    assertEquals(hardcoded.fanout.strategy, settings.fanout.strategy);
    assertEquals(hardcoded.fanout.protectEscapes, settings.fanout.protectEscapes);
    assertEquals(hardcoded.autorouter.forceViaInPad, settings.autorouter.forceViaInPad);
    assertEquals(hardcoded.autorouter.viaSpanCostFactor, settings.autorouter.viaSpanCostFactor);
  }

  @Test
  void quickDropsTheFallbacksAndTheOptimizer() {
    RouterSettings settings = merged("--router.profile=Quick");
    assertEquals("quick", settings.profile);
    assertEquals("none", settings.autorouter.fallbackStrategies);
    assertFalse(settings.optimizer.enabled);
    assertEquals("bga", settings.fanout.strategy);
  }

  @Test
  void thoroughRacesTheFallbacks() {
    RouterSettings settings = merged("--router.profile=thorough");
    assertTrue(settings.autorouter.fallbackRace);
    assertEquals(0, settings.autorouter.fallbackThreads);
    assertEquals(0.0, settings.autorouter.fallbackWorkRatio);
    assertTrue(settings.autorouter.gridFinisher);
  }

  @Test
  void anExplicitSettingOverridesTheProfile() {
    RouterSettings settings = merged("--router.profile=quick", "--router.fanout.strategy=planned");
    assertEquals("planned", settings.fanout.strategy);
    assertFalse(settings.optimizer.enabled, "the rest of the profile stays");
  }

  @Test
  void anUnknownProfileFallsBackToTheDefault() {
    assertEquals("standard", merged("--router.profile=turbo").profile);
  }
}
