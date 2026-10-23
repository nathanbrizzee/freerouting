package app.freerouting.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The settings a launch set through the command line or environment variables, so that saving the
 * settings file keeps them out of it. Without this, a GUI started with {@code -mt 0} or {@code
 * --router.job_timeout=00:00:30} saved those values into {@code freerouting.json} when it saved its
 * settings, and every later run without those options inherited them.
 *
 * <p>The values are compared as JSON trees: each leaf the launch changed is remembered with the
 * value it had before and the value the launch gave it. When the settings are saved, a leaf that
 * still has the launch's value gets its earlier value back; a leaf the session changed since, for
 * example in the GUI's settings window, is saved as it is.
 */
final class LaunchOverrides {

  private record Override(List<String> path, JsonElement before, JsonElement launch) {}

  private static volatile List<Override> overrides = List.of();

  private LaunchOverrides() {}

  /** Remembers the leaves that differ between the settings before and after the launch options. */
  static void remember(JsonElement before, JsonElement afterLaunch) {
    List<Override> found = new ArrayList<>();
    diff(new ArrayList<>(), before, afterLaunch, found);
    overrides = List.copyOf(found);
  }

  /** Forgets the remembered launch options (for tests). */
  static void clear() {
    overrides = List.of();
  }

  /** How many settings the launch changed. */
  static int count() {
    return overrides.size();
  }

  /**
   * Returns the tree with the earlier value back in every leaf that still has the launch's value.
   * Changes the tree in place.
   */
  static JsonElement restore(JsonElement tree) {
    for (Override o : overrides) {
      JsonElement current = get(tree, o.path());
      if (Objects.equals(current, o.launch())) {
        set(tree, o.path(), o.before());
      }
    }
    return tree;
  }

  private static void diff(
      List<String> path, JsonElement before, JsonElement after, List<Override> out) {
    JsonElement b = before == null ? JsonNull.INSTANCE : before;
    JsonElement a = after == null ? JsonNull.INSTANCE : after;
    if (b.isJsonObject() && a.isJsonObject()) {
      JsonObject bo = b.getAsJsonObject();
      JsonObject ao = a.getAsJsonObject();
      Set<String> keys = new TreeSet<>(bo.keySet());
      keys.addAll(ao.keySet());
      for (String key : keys) {
        path.add(key);
        diff(path, bo.get(key), ao.get(key), out);
        path.remove(path.size() - 1);
      }
    } else if (!b.equals(a)) {
      out.add(new Override(List.copyOf(path), b.deepCopy(), a.deepCopy()));
    }
  }

  private static JsonElement get(JsonElement tree, List<String> path) {
    JsonElement node = tree;
    for (String key : path) {
      if (node == null || !node.isJsonObject()) {
        return JsonNull.INSTANCE;
      }
      node = node.getAsJsonObject().get(key);
    }
    return node == null ? JsonNull.INSTANCE : node;
  }

  private static void set(JsonElement tree, List<String> path, JsonElement value) {
    JsonElement node = tree;
    for (int i = 0; i < path.size() - 1; i++) {
      if (node == null || !node.isJsonObject()) {
        return;
      }
      node = node.getAsJsonObject().get(path.get(i));
    }
    if (node == null || !node.isJsonObject()) {
      return;
    }
    String key = path.get(path.size() - 1);
    if (value.isJsonNull()) {
      node.getAsJsonObject().remove(key);
    } else {
      node.getAsJsonObject().add(key, value.deepCopy());
    }
  }

  /** The remembered paths, for log and test messages. */
  static List<String> paths() {
    List<String> result = new ArrayList<>();
    for (Override o : overrides) {
      result.add(String.join(".", o.path()));
    }
    return result;
  }
}
