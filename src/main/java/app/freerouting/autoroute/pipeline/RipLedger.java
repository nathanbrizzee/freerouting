package app.freerouting.autoroute.pipeline;

import app.freerouting.board.model.items.Item;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rip-ups of one pass and which nets ripped which over the run. A net pair that rips each other
 * back and forth is the oscillation that keeps later passes from improving; the pass log line lets
 * a person or an agent see how much of a pass goes into it.
 */
final class RipLedger {

  /** Directed pairs (ripping net, ripped net) of the earlier passes and of this one. */
  private final Set<Long> pairs = new HashSet<>();

  /** Unordered pairs that ripped each other in both directions, over the run. */
  private final Set<Long> backAndForth = new HashSet<>();

  private int items;
  private int connections;
  private final Set<Integer> rippedNets = new TreeSet<>();
  private int newBackAndForth;

  /** Starts the counts of a new pass; the pairs of the earlier passes are kept. */
  void startPass() {
    this.items = 0;
    this.connections = 0;
    this.rippedNets.clear();
    this.newBackAndForth = 0;
  }

  /** Records the items ripped by one connection search of {@code net}. */
  void record(int net, Collection<Item> ripped) {
    boolean any = false;
    for (Item item : ripped) {
      boolean foreign = false;
      for (int i = 0; i < item.netCount(); i++) {
        int other = item.getNetNumber(i);
        if (other == net) {
          continue;
        }
        foreign = true;
        this.rippedNets.add(other);
        this.pairs.add(key(net, other));
        if (this.pairs.contains(key(other, net))
            && this.backAndForth.add(key(Math.min(net, other), Math.max(net, other)))) {
          this.newBackAndForth++;
        }
      }
      if (foreign) {
        this.items++;
        any = true;
      }
    }
    if (any) {
      this.connections++;
    }
  }

  int backAndForthPairs() {
    return this.backAndForth.size();
  }

  /** The pass line, or null when the pass ripped nothing. */
  String describe() {
    if (this.items == 0) {
      return null;
    }
    return String.format(
        Locale.US,
        "Rip-up: %d items of %d other nets ripped by %d connection searches; %d net pairs have"
            + " ripped each other back and forth so far, %d of them new in this pass.",
        this.items,
        this.rippedNets.size(),
        this.connections,
        this.backAndForth.size(),
        this.newBackAndForth);
  }

  private static long key(int from, int to) {
    return ((long) from << 32) | (to & 0xffffffffL);
  }
}
