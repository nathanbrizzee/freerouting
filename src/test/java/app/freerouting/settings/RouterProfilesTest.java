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
    assertTrue(settings.sessionRoutesMovable, "a continued board's routes may be ripped up");
    // Every finishing method in one run: fail fast in the maze passes, race the alternatives,
    // then the grid finisher with lifting.
    assertEquals(2_000_000L, settings.autorouter.maxSearchStepsPerConnection);
    assertEquals(2_000_000L, settings.autorouter.maxSearchStepsAfterFailure);
    assertTrue(settings.autorouter.fallbackRace);
    assertEquals(0, settings.autorouter.fallbackThreads, "cores, bounded by the heap");
    assertEquals(1.0, settings.autorouter.fallbackWorkRatio, "the attempts keep their budget");
    assertTrue(settings.autorouter.gridFinisher);
    assertTrue(settings.autorouter.gridFinisherRipup);
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
    assertFalse(settings.sessionRoutesMovable, "the original router keeps loaded routes fixed");
  }

  @Test
  void quickDropsTheFallbacksAndTheOptimizer() {
    RouterSettings settings = merged("--router.profile=Quick");
    assertEquals("quick", settings.profile);
    assertEquals("none", settings.autorouter.fallbackStrategies);
    assertFalse(settings.optimizer.enabled);
    assertEquals("bga", settings.fanout.strategy);
    assertFalse(settings.autorouter.fallbackRace, "the race uses its own strategy list");
    assertFalse(settings.autorouter.gridFinisher);
  }

  @Test
  void thoroughIsNowStandard() {
    RouterSettings settings = merged("--router.profile=thorough");
    assertEquals("standard", settings.profile);
    assertTrue(settings.autorouter.fallbackRace);
    assertTrue(settings.autorouter.gridFinisher);
    assertEquals(1.0, settings.autorouter.fallbackWorkRatio, "no unbudgeted attempts any more");
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
