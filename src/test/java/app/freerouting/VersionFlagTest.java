package app.freerouting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.constants.Constants;
import org.junit.jupiter.api.Test;

class VersionFlagTest {

  @Test
  void versionFlagsAreRecognised() {
    assertTrue(VersionFlag.requested(new String[] {"--version"}));
    assertTrue(VersionFlag.requested(new String[] {"-version"}));
    assertTrue(VersionFlag.requested(new String[] {"-de", "board.dsn", "--VERSION"}));
    assertFalse(VersionFlag.requested(new String[] {"-de", "board.dsn"}));
    assertFalse(VersionFlag.requested(null));
  }

  @Test
  void theLineNamesTheVersionAndBuildDate() {
    String text = VersionFlag.text();
    assertTrue(text.startsWith("Freerouting " + Constants.FREEROUTING_VERSION), text);
    assertTrue(text.contains("build-date: " + Constants.FREEROUTING_BUILD_DATE), text);
  }
}
