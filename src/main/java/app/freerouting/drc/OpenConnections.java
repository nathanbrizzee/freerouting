package app.freerouting.drc;

import app.freerouting.board.facade.BasicBoard;
import app.freerouting.board.model.items.Item;
import app.freerouting.board.model.items.Pin;
import app.freerouting.board.model.structure.Component;
import app.freerouting.board.model.structure.Unit;
import app.freerouting.geometry.planar.FloatPoint;
import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The connections a board still lacks, named so that a person or an agent can find them: pins by
 * component and pin name, other items by type and position in millimetres.
 */
public final class OpenConnections {

  private OpenConnections() {}

  /** One open connection of a net, between the two items its airline joins. */
  public static final class Entry {
    @SerializedName("net")
    public String net;

    @SerializedName("from")
    public String from;

    @SerializedName("to")
    public String to;

    @Override
    public String toString() {
      return net + ": " + from + " - " + to;
    }
  }

  /** The open connections of board, sorted by net name as the airlines are. */
  public static List<Entry> of(BasicBoard board) {
    AirLine[] airlines = new DesignRulesChecker(board, null).getAllAirlines();
    List<Entry> result = new ArrayList<>(airlines.length);
    for (AirLine airline : airlines) {
      Entry entry = new Entry();
      entry.net = airline.net.name;
      entry.from = label(board, airline.fromItem, airline.fromCorner);
      entry.to = label(board, airline.toItem, airline.toCorner);
      result.add(entry);
    }
    return result;
  }

  /** One log line naming at most limit of the open connections. */
  public static String summary(List<Entry> open, int limit) {
    StringBuilder line = new StringBuilder("Open connections (" + open.size() + "): ");
    for (int i = 0; i < Math.min(limit, open.size()); i++) {
      if (i > 0) {
        line.append("; ");
      }
      line.append(open.get(i));
    }
    if (open.size() > limit) {
      line.append("; and ").append(open.size() - limit).append(" more");
    }
    return line.toString();
  }

  static String label(BasicBoard board, Item item, FloatPoint corner) {
    if (item instanceof Pin pin) {
      Component component = board.components.get(pin.getComponentId());
      if (component != null && pin.name() != null) {
        return component.name + "-" + pin.name();
      }
    }
    String type = item == null ? "item" : item.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    if (corner == null) {
      return type;
    }
    double toMm =
        Unit.scale(1.0, board.communication.unit, Unit.MM)
            / (board.communication.resolution > 0 ? board.communication.resolution : 1);
    return String.format(Locale.ROOT, "%s (%.3f, %.3f)", type, corner.x * toMm, corner.y * toMm);
  }
}
