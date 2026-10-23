package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps the user docs in step with the router settings: every setting has an entry in {@code
 * docs/settings.md}, and every router option the agent guide names exists.
 */
class SettingsDocumentationTest {

  private static final Path REPO = repoRoot();

  // An option the agent guide names: "--router.a.b" or, in a table cell, "`a.b=value`".
  private static final Pattern GUIDE_OPTION =
      Pattern.compile("--router\\.([a-z0-9_.]+)|`([a-z0-9_]+(?:\\.[a-z0-9_]+)*)=");

  @Test
  void everyRouterSettingHasAnEntryInSettingsMd() throws IOException {
    String doc = Files.readString(REPO.resolve("docs/settings.md"));
    List<String> missing = new ArrayList<>();
    for (String path : settingPaths()) {
      String name = path.substring(path.lastIndexOf('.') + 1);
      if (!doc.contains("`" + name + "`") && !doc.contains("." + name + "`")) {
        missing.add(path);
      }
    }
    assertTrue(
        missing.isEmpty(),
        "docs/settings.md has no entry for these router settings: "
            + missing
            + ". Document them where the others of their section are.");
  }

  @Test
  void everyRouterOptionTheAgentGuideNamesExists() throws IOException {
    String guide = Files.readString(REPO.resolve("docs/agent-guide.md"));
    Set<String> paths = settingPaths();
    List<String> unknown = new ArrayList<>();
    Matcher m = GUIDE_OPTION.matcher(guide);
    while (m.find()) {
      String option = m.group(1) != null ? m.group(1) : m.group(2);
      if (!paths.contains(option)) {
        unknown.add(option);
      }
    }
    assertTrue(
        unknown.isEmpty(),
        "docs/agent-guide.md names router options that do not exist: " + unknown);
  }

  @Test
  void theSettingPathsIncludeNestedOnes() {
    Set<String> paths = settingPaths();
    assertTrue(paths.contains("job_timeout"), paths.toString());
    assertTrue(paths.contains("autorouter.pad_neck"), paths.toString());
    assertTrue(paths.contains("fanout.strategy"), paths.toString());
    assertTrue(paths.contains("layers.bend_cost"), paths.toString());
  }

  /** Dotted names of every router setting, as the CLI takes them after "--router.". */
  static Set<String> settingPaths() {
    Set<String> paths = new TreeSet<>();
    collect(RouterSettings.class, "", paths);
    return paths;
  }

  private static void collect(Class<?> type, String prefix, Set<String> paths) {
    for (Field field : type.getDeclaredFields()) {
      SerializedName name = field.getAnnotation(SerializedName.class);
      if (name == null || Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      String path = prefix + name.value();
      paths.add(path);
      Class<?> fieldType =
          field.getType().isArray() ? field.getType().getComponentType() : field.getType();
      if (fieldType.getPackageName().equals(RouterSettings.class.getPackageName())
          && fieldType != type) {
        collect(fieldType, path + ".", paths);
      }
    }
  }

  private static Path repoRoot() {
    String fixtures = System.getProperty("freerouting.test.fixtures.dir");
    if (fixtures != null) {
      return Path.of(fixtures).getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
