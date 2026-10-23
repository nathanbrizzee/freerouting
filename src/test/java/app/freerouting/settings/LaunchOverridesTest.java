package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.freerouting.util.gson.GsonProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Launch options (command line, environment) must not end up in the saved settings file. */
class LaunchOverridesTest {

  @AfterEach
  void forget() {
    LaunchOverrides.clear();
  }

  private static JsonObject saved(GlobalSettings settings) {
    JsonElement tree = GsonProvider.GSON.toJsonTree(settings);
    return LaunchOverrides.restore(tree).getAsJsonObject();
  }

  @Test
  @SuppressWarnings("deprecation") // CLI flags write into the JSON/CLI routerSettings bridge.
  void launchOptionsAreLeftOutOfTheSavedFile() {
    GlobalSettings settings = new GlobalSettings();
    JsonElement fromFile = GlobalSettings.snapshot(settings);

    settings.applyCommandLineArguments(
        new String[] {
          "-de",
          "board.dsn",
          "-dct",
          "0",
          "--router.job_timeout=00:00:30",
          "--router.status_file=/tmp/run/s.json"
        });
    GlobalSettings.rememberLaunchOverrides(fromFile, settings);

    JsonObject saved = saved(settings);
    JsonObject router = saved.getAsJsonObject("router");
    assertFalse(router.has("job_timeout"), router.toString());
    assertFalse(router.has("status_file"), router.toString());
    assertEquals(5, saved.getAsJsonObject("gui").get("dialog_confirmation_timeout").getAsInt());
    // The running session still uses the launch values.
    assertEquals("00:00:30", settings.routerSettings.jobTimeoutString);
    assertEquals(0, settings.guiSettings.dialogConfirmationTimeout);
  }

  @Test
  void changesMadeDuringTheSessionAreSaved() {
    GlobalSettings settings = new GlobalSettings();
    JsonElement fromFile = GlobalSettings.snapshot(settings);
    settings.applyCommandLineArguments(new String[] {"-dct", "0"});
    GlobalSettings.rememberLaunchOverrides(fromFile, settings);

    // The user sets the timeout to 3 in the GUI's settings window.
    settings.guiSettings.dialogConfirmationTimeout = 3;

    assertEquals(
        3, saved(settings).getAsJsonObject("gui").get("dialog_confirmation_timeout").getAsInt());
  }

  @Test
  void withoutLaunchOptionsTheFileIsSavedAsItIs() {
    GlobalSettings settings = new GlobalSettings();
    JsonElement fromFile = GlobalSettings.snapshot(settings);
    GlobalSettings.rememberLaunchOverrides(fromFile, settings);

    assertEquals(0, LaunchOverrides.count());
    assertTrue(saved(settings).equals(GsonProvider.GSON.toJsonTree(settings)));
  }
}
