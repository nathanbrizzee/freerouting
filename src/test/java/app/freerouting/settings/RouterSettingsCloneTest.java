package app.freerouting.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every plain setting survives {@link RouterSettings#clone()}. A fallback attempt runs on a clone,
 * and a timeout during it leaves the clone in place, so a field the clone drops is lost for the
 * rest of the job: the result manifest and the status file paths were lost this way.
 */
class RouterSettingsCloneTest {

  @Test
  void cloneCopiesEveryPlainField() throws IllegalAccessException {
    RouterSettings settings = new RouterSettings();
    List<Field> plainFields = new ArrayList<>();
    for (Field field : RouterSettings.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      Object value = sampleValue(field.getType());
      if (value != null) {
        field.set(settings, value);
        plainFields.add(field);
      }
    }
    assertTrue(plainFields.size() > 10, "found the plain fields: " + plainFields);

    RouterSettings copy = settings.clone();
    List<String> dropped = new ArrayList<>();
    for (Field field : plainFields) {
      if (!field.get(settings).equals(field.get(copy))) {
        dropped.add(field.getName());
      }
    }
    assertEquals(List.of(), dropped, "fields clone() does not copy");
  }

  private static Object sampleValue(Class<?> type) {
    if (type == String.class) {
      return "sample";
    } else if (type == Boolean.class) {
      return Boolean.TRUE;
    } else if (type == Integer.class) {
      return 7;
    } else if (type == Double.class) {
      return 1.5;
    } else if (type == Float.class) {
      return 2.5f;
    } else if (type == Long.class) {
      return 9L;
    }
    return null;
  }
}
