package app.freerouting.core;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.Locale;

/**
 * How much parallel work the heap and the machine's free physical memory can hold.
 *
 * <p>A heap that grows past the physical memory left free makes the operating system page, and a
 * whole desktop can freeze long before the JVM reports anything, so parallel attempts and optimizer
 * workers are sized by both: the heap not yet live, and the physical memory the heap may still
 * take. The thread counts it gives change how fast a run goes, not what it finds, where the work is
 * split into attempts with budgets of their own.
 */
public final class MemoryBudget {

  /** Physical memory left to the operating system and the other programs. */
  static final long SYSTEM_RESERVE = 1L << 30;

  private MemoryBudget() {}

  /** The heap live after the last collection, or the used heap when no collection has run. */
  public static long liveHeap() {
    long live = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getType() == MemoryType.HEAP && pool.getCollectionUsage() != null) {
        live += pool.getCollectionUsage().getUsed();
      }
    }
    if (live <= 0) {
      Runtime runtime = Runtime.getRuntime();
      live = runtime.totalMemory() - runtime.freeMemory();
    }
    return live;
  }

  /** The machine's free physical memory in bytes, or -1 when the platform does not say. */
  public static long freePhysical() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      return os.getFreeMemorySize();
    }
    return -1;
  }

  /** The machine's physical memory in bytes, or -1 when the platform does not say. */
  public static long totalPhysical() {
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      return os.getTotalMemorySize();
    }
    return -1;
  }

  /**
   * The memory this JVM may still give to new work: the heap not yet live, but no more than the
   * committed heap not yet live plus the physical memory free beyond {@link #SYSTEM_RESERVE}.
   */
  public static long available() {
    Runtime runtime = Runtime.getRuntime();
    long live = liveHeap();
    long heapLeft = Math.max(0, runtime.maxMemory() - live);
    long free = freePhysical();
    if (free < 0) {
      return heapLeft;
    }
    long committedLeft = Math.max(0, runtime.totalMemory() - live);
    long physicalLeft = committedLeft + Math.max(0, free - SYSTEM_RESERVE);
    return Math.min(heapLeft, physicalLeft);
  }

  /**
   * At most {@code wanted} parallel units of work of {@code perUnit} bytes each that fit in {@link
   * #available()}, and at least one.
   */
  public static int parallelUnits(int wanted, long perUnit) {
    if (wanted <= 1) {
      return Math.max(1, wanted);
    }
    long fit = available() / Math.max(1, perUnit);
    return (int) Math.max(1, Math.min(wanted, fit));
  }

  /**
   * A warning when the heap limit is more than the physical memory this JVM can still take, or
   * null: then a large board makes the machine page, and it can freeze, instead of stopping with an
   * out-of-memory error the run can report.
   */
  public static String startupWarning() {
    Runtime runtime = Runtime.getRuntime();
    long free = freePhysical();
    if (free < 0) {
      return null;
    }
    long canTake = runtime.totalMemory() + Math.max(0, free - SYSTEM_RESERVE);
    if (runtime.maxMemory() <= canTake) {
      return null;
    }
    return String.format(
        Locale.ROOT,
        "Memory: the heap limit is %.1f GB but only %.1f GB of physical memory is free (%.1f GB in"
            + " all). A large board can make the machine page or freeze: lower -Xmx, run fewer"
            + " jobs at once, or close other programs. Parallel work is sized to fit what is free.",
        gb(runtime.maxMemory()),
        gb(free),
        gb(totalPhysical()));
  }

  private static double gb(long bytes) {
    return bytes / (double) (1L << 30);
  }
}
