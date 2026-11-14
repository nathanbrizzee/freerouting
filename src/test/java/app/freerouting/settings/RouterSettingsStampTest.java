package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.settings.sources.JsonFileSettings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Router values in {@code freerouting.json} saved against other defaults, or before the stamp
 * existed, are reset with a backup; values saved under the current stamp are kept.
 */
class RouterSettingsStampTest {

  @TempDir Path dir;

  private static final String OLD_FILE =
      """
      {
        "gui": { "input_directory": "C:/boards" },
        "router": {
          "autorouter": { "max_passes": 3 },
          "fanout": { "enabled": false, "max_passes": 3 },
          "optimizer": {}
        },
        "version": "2.5.0-nb.6"
      }
      """;

  @BeforeEach
  void forgetWarnings() {
    RouterSettingsStamp.resetWarnings();
  }

  private Path write(String text) throws IOException {
    Path file = dir.resolve("freerouting.json");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }

  private static JsonObject read(Path file) throws IOException {
    return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
  }

  @Test
  void anUnstampedFileHasItsRouterValuesResetWithABackup() throws IOException {
    Path file = write(OLD_FILE);
    JsonFileSettings source = new JsonFileSettings(file);
    assertNull(source.getSettings().autorouter.maxPasses, "the old pass cap must not apply");

    JsonObject rewritten = read(file);
    assertEquals(0, rewritten.getAsJsonObject("router").size());
    assertEquals(
        RouterSettingsStamp.current(), rewritten.get(RouterSettingsStamp.FIELD).getAsString());
    assertEquals(
        "C:/boards",
        rewritten.getAsJsonObject("gui").get("input_directory").getAsString(),
        "other sections are kept");
    Path backup = dir.resolve("freerouting.json" + RouterSettingsStamp.BACKUP_SUFFIX);
    assertTrue(Files.exists(backup));
    assertTrue(Files.readString(backup).contains("\"max_passes\": 3"));
  }

  @Test
  void valuesStampedAgainstOtherDefaultsAreReset() throws IOException {
    Path file =
        write(
            OLD_FILE.replace(
                "\"version\"", "\"" + RouterSettingsStamp.FIELD + "\": \"x/0\", \"version\""));
    assertNull(new JsonFileSettings(file).getSettings().autorouter.maxPasses);
    assertEquals(0, read(file).getAsJsonObject("router").size());
  }

  @Test
  void valuesStampedWithTheCurrentDefaultsAreKept() throws IOException {
    String stamped =
        OLD_FILE.replace(
            "\"version\"",
            "\""
                + RouterSettingsStamp.FIELD
                + "\": \""
                + RouterSettingsStamp.current()
                + "\", \"version\"");
    Path file = write(stamped);
    assertEquals(3, new JsonFileSettings(file).getSettings().autorouter.maxPasses);
    assertEquals(stamped, Files.readString(file), "the file is left as it was");
    assertFalse(Files.exists(dir.resolve("freerouting.json" + RouterSettingsStamp.BACKUP_SUFFIX)));
  }

  @Test
  void aFileWithoutRouterValuesIsLeftAlone() throws IOException {
    String empty = "{ \"router\": { \"autorouter\": {}, \"fanout\": {} }, \"version\": \"2.5.0\" }";
    Path file = write(empty);
    new JsonFileSettings(file);
    assertEquals(empty, Files.readString(file));
  }

  @Test
  void theFingerprintIsStable() {
    assertEquals(
        RouterSettingsStamp.defaultsFingerprint(), RouterSettingsStamp.defaultsFingerprint());
    assertTrue(
        RouterSettingsStamp.current().endsWith("/" + RouterSettingsStamp.defaultsFingerprint()));
  }
}
