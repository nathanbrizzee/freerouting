package app.freerouting.settings;

import app.freerouting.logger.FRLogger;
import app.freerouting.settings.sources.DefaultSettings;
import app.freerouting.util.gson.GsonProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guards the router values in {@code freerouting.json} against outliving the defaults they were
 * chosen against.
 *
 * <p>The GUI saves the whole settings object, router values included, and every later run on the
 * machine (headless ones too) then takes them over the defaults. A value picked once in a menu
 * could so cap every run at three passes long after the defaults changed, with nothing in the log.
 * The file now carries a stamp: the build's version and a fingerprint of the default router
 * settings of every profile. On load:
 *
 * <ul>
 *   <li>router values saved under the current stamp are used, and each run logs one warning that
 *       names them;
 *   <li>router values saved under another stamp, or under none (every file written before the stamp
 *       existed), are reset: the file is copied to {@code freerouting.json.router-backup}, its
 *       router section is emptied and stamped, and a warning names what was reset.
 * </ul>
 *
 * <p>The fingerprint changes whenever a default router setting changes, so the reset follows the
 * defaults even between builds that report the same version.
 */
public final class RouterSettingsStamp {

  /** The JSON field the stamp is stored in. */
  public static final String FIELD = "router_settings_stamp";

  /** The name of the user settings file the guard applies to. */
  public static final String SETTINGS_FILE = "freerouting.json";

  /** The suffix of the copy kept of a file whose router values were reset. */
  public static final String BACKUP_SUFFIX = ".router-backup";

  private static String fingerprint;

  private static final Set<Path> warned = new HashSet<>();

  private RouterSettingsStamp() {}

  /** The stamp of this build: {@code <version>/<fingerprint of the default router settings>}. */
  public static String current() {
    return GlobalSettings.getReleaseSafeVersion() + "/" + defaultsFingerprint();
  }

  /**
   * The first 12 hex digits of the SHA-256 of the default router settings, once per profile.
   * Deterministic: the defaults do not depend on the machine.
   */
  static synchronized String defaultsFingerprint() {
    if (fingerprint == null) {
      StringBuilder text = new StringBuilder();
      text.append(GsonProvider.GSON.toJson(new DefaultSettings().getSettings()));
      for (String profile : RouterProfiles.NAMES) {
        RouterSettings settings = new DefaultSettings().getSettings();
        RouterProfiles.apply(profile, settings);
        text.append('\n').append(profile).append('\n').append(GsonProvider.GSON.toJson(settings));
      }
      try {
        byte[] hash =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 6; i++) {
          hex.append(String.format("%02x", hash[i]));
        }
        fingerprint = hex.toString();
      } catch (NoSuchAlgorithmException e) {
        fingerprint = Integer.toHexString(text.toString().hashCode());
      }
    }
    return fingerprint;
  }

  /**
   * Applies the guard to a parsed settings file and returns the object to read it from: the same
   * object, with its router section emptied when the values were reset. A reset also rewrites
   * {@code file} (through a temporary file) after copying it to the backup.
   */
  public static JsonObject apply(JsonObject root, Path file) {
    if (root == null) {
      return null;
    }
    JsonElement router = root.get("router");
    List<String> values = new ArrayList<>();
    if (router != null && router.isJsonObject()) {
      leaves(router.getAsJsonObject(), "", values);
    }
    if (values.isEmpty()) {
      return root;
    }
    String stamp =
        root.get(FIELD) instanceof JsonPrimitive primitive && primitive.isString()
            ? primitive.getAsString()
            : null;
    String current = current();
    if (current.equals(stamp)) {
      warnOnce(
          file,
          "freerouting.json at '"
              + file
              + "' sets router values that override the defaults on every run: "
              + String.join(", ", values)
              + ". Remove them from the file, or change them back in the GUI, to route with the"
              + " defaults.");
      return root;
    }
    Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
    root.add("router", new JsonObject());
    root.addProperty(FIELD, current);
    String outcome;
    try {
      if (Files.exists(file)) {
        Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
      }
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
        GsonProvider.GSON.toJson(root, writer);
      }
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      outcome = "the old file is kept as '" + backup + "'";
    } catch (IOException | UnsupportedOperationException e) {
      outcome = "the file could not be rewritten (" + e.getMessage() + "), so this repeats";
    }
    warnOnce(
        file,
        "freerouting.json at '"
            + file
            + "' held router values saved "
            + (stamp == null
                ? "before router values were stamped"
                : "against other defaults (" + stamp + ", now " + current + ")")
            + ": "
            + String.join(", ", values)
            + ". They were reset so this build's defaults apply; "
            + outcome
            + ". Set a value again in the GUI or on the command line if it is still wanted.");
    return root;
  }

  /** Collects {@code path=value} for every value set in the object, depth first, in file order. */
  static void leaves(JsonObject object, String prefix, List<String> out) {
    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
      String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      JsonElement value = entry.getValue();
      if (value == null || value.isJsonNull()) {
        continue;
      }
      if (value.isJsonObject()) {
        leaves(value.getAsJsonObject(), path, out);
      } else {
        out.add(path + "=" + value);
      }
    }
  }

  private static void warnOnce(Path file, String message) {
    synchronized (warned) {
      if (!warned.add(file.toAbsolutePath())) {
        return;
      }
    }
    FRLogger.warn(message);
  }

  /** For tests: forget which files were warned about. */
  static void resetWarnings() {
    synchronized (warned) {
      warned.clear();
    }
  }
}
