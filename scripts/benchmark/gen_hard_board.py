#!/usr/bin/env python3
"""Generates a small synthetic Specctra DSN board that is hard to route for its size.

The board exercises the cases that make real dense boards slow, so router changes can be measured
in minutes instead of hours:

* two 0.5 mm pitch QFPs (100 and 64 pins) a few millimetres apart, like a microcontroller beside
  a companion chip: their buses leave the "wrong" side of the part (fanout pinch);
* a 0.8 mm pitch BGA-144 with only two outer rings of signals, the inner balls on power planes;
* a 0.5 mm pitch FPC connector and a through-hole header the buses must reach;
* buses that cross in opposite order, so connections have to rip each other up;
* large power pours on the outer layers across the channel between the big parts, so signals
  cannot cross there on the top or bottom layer and must dive to the inner signal layers;
* split inner power planes and a full ground plane (vias only);
* a wide power net and high-clearance nets that cross the fine-pitch channels (necking);
* decoupling capacitors and series resistors squeezed into the escape areas.

With "full" pours (the default) the top and bottom layers each carry a ground pour over the whole
board, as KiCad exports a flooded zone. By default Freerouting treats pours on signal layers as
conductors, not obstacles (router.plane_as_obstacle=false): routes may cross them, and every
connectivity query on the pour net covers the whole pour. With router.plane_as_obstacle=true the
pours block the outer layers. "channel" pours cover only the channel between the big parts.

Usage: python3 -I gen_hard_board.py [out.dsn] [layers: 6 or 8] [seed] [pours: full or channel]

The output depends only on the arguments. It uses no data from any real design.
"""

import random
import sys

BOARD_W, BOARD_H = 72000, 60000  # um; y runs downward as negative values, as in KiCad exports

LAYER_SETS = {
    # name, type; the plane net of each power layer is set in plane_for()
    6: [("F.Cu", "signal"), ("In1.Cu", "power"), ("In2.Cu", "signal"), ("In3.Cu", "signal"),
        ("In4.Cu", "power"), ("B.Cu", "signal")],
    8: [("F.Cu", "signal"), ("In1.Cu", "power"), ("In2.Cu", "signal"), ("In3.Cu", "signal"),
        ("In4.Cu", "power"), ("In5.Cu", "signal"), ("In6.Cu", "power"), ("B.Cu", "signal")],
}


def fmt(v):
    return ("%.3f" % v).rstrip("0").rstrip(".")


def rect_poly(layer, x1, y1, x2, y2):
    return "(polygon %s 0  %s %s  %s %s  %s %s  %s %s)" % (
        layer, fmt(x1), fmt(y1), fmt(x2), fmt(y1), fmt(x2), fmt(y2), fmt(x1), fmt(y2))


class Design:
    def __init__(self, layers):
        self.layers = layers
        self.padstacks = {}
        self.images = {}
        self.places = []  # (image, ref, x, y)
        self.nets = {}  # net -> [pins]
        self.net_class = {}  # net -> class name
        self.planes = []

    def padstack_rect(self, name, w, h, layers=("F.Cu",)):
        shapes = "".join(
            "      (shape (rect %s %s %s %s %s))\n"
            % (l, fmt(-w / 2), fmt(-h / 2), fmt(w / 2), fmt(h / 2)) for l in layers)
        self.padstacks[name] = "    (padstack %s\n%s      (attach off)\n    )\n" % (name, shapes)

    def padstack_circle(self, name, d, layers=("F.Cu",)):
        shapes = "".join("      (shape (circle %s %s))\n" % (l, fmt(d)) for l in layers)
        self.padstacks[name] = "    (padstack %s\n%s      (attach off)\n    )\n" % (name, shapes)

    def image(self, name, pins):
        self.images[name] = pins

    def place(self, image, ref, x, y):
        self.places.append((image, ref, x, y))

    def connect(self, net, *pins, cls="default"):
        self.nets.setdefault(net, []).extend(pins)
        self.net_class.setdefault(net, cls)

    def plane(self, net, polygon):
        self.planes.append("    (plane %s %s)\n" % (net, polygon))

    def dsn(self):
        o = ["(pcb synthetic_hard_mix.dsn\n"]
        o.append("  (parser\n    (string_quote \")\n    (space_in_quoted_tokens on)\n")
        o.append("    (host_cad \"gen_hard_board\")\n    (host_version \"1\")\n  )\n")
        o.append("  (resolution um 10)\n  (unit um)\n  (structure\n")
        for i, (l, kind) in enumerate(self.layers):
            o.append("    (layer %s\n      (type %s)\n      (property\n        (index %d)\n      )\n    )\n"
                     % (l, kind, i))
        o.append("    (boundary\n      (path pcb 0  0 0  %d 0  %d %d  0 %d  0 0)\n    )\n"
                 % (BOARD_W, BOARD_W, -BOARD_H, -BOARD_H))
        o.extend(self.planes)
        o.append("    (via Via500 Via800)\n")
        o.append("    (rule\n      (width 100)\n      (clearance 100)\n    )\n  )\n")
        o.append("  (placement\n")
        by_image = {}
        for image, ref, x, y in self.places:
            by_image.setdefault(image, []).append((ref, x, y))
        for image, items in by_image.items():
            o.append("    (component %s\n" % image)
            for ref, x, y in items:
                o.append("      (place %s %s %s front 0)\n" % (ref, fmt(x), fmt(y)))
            o.append("    )\n")
        o.append("  )\n  (library\n")
        for name, pins in self.images.items():
            o.append("    (image %s\n" % name)
            for padstack, pid, x, y in pins:
                o.append("      (pin %s %s %s %s)\n" % (padstack, pid, fmt(x), fmt(y)))
            o.append("    )\n")
        for ps in self.padstacks.values():
            o.append(ps)
        o.append("  )\n  (network\n")
        for net, pins in self.nets.items():
            o.append("    (net %s\n      (pins %s)\n    )\n" % (net, " ".join(pins)))
        classes = {}
        for net, cls in self.net_class.items():
            classes.setdefault(cls, []).append(net)
        rules = {"default": ("Via500", 100, 100), "Power": ("Via800", 400, 250),
                 "HV": ("Via800", 200, 500)}
        for cls, nets in classes.items():
            via, width, clear = rules[cls]
            name = "kicad_default" if cls == "default" else cls
            o.append("    (class %s %s\n      (circuit\n        (use_via %s)\n      )\n"
                     "      (rule\n        (width %d)\n        (clearance %d)\n      )\n    )\n"
                     % (name, " ".join(nets), via, width, clear))
        o.append("  )\n  (wiring\n  )\n)\n")
        return "".join(o)


def qfp(d, name, per_side, tip, ref, cx, cy, power):
    """A QFP with 0.5 mm pitch; returns {(side, index): 'REF-n'}. Sides run L, B, R, T."""
    half = (per_side - 1) * 250
    pins = []
    pin_of = {}
    number = 1
    for s in "LBRT":
        for i in range(per_side):
            pos = -half + 500 * i
            x, y = {"L": (-tip, -pos), "B": (pos, -tip), "R": (tip, pos), "T": (-pos, tip)}[s]
            pins.append(("PadH" if s in "LR" else "PadV", str(number), x, y))
            pin_of[(s, i)] = "%s-%d" % (ref, number)
            number += 1
    d.image(name, pins)
    d.place(name, ref, cx, cy)
    for key, net in power.items():
        d.connect(net, pin_of[key])
    return pin_of


def build(layer_count, seed, pours="full"):
    rnd = random.Random(seed)
    layers = LAYER_SETS[layer_count]
    names = [l for l, _ in layers]
    d = Design(layers)
    d.padstack_rect("PadH", 1500, 300)
    d.padstack_rect("PadV", 300, 1500)
    d.padstack_circle("Ball400", 400)
    d.padstack_rect("Pad0402", 600, 500)
    d.padstack_rect("PadFpc", 1200, 300)
    d.padstack_rect("PadSoic", 1500, 600)
    d.padstack_circle("Thru1700", 1700, tuple(names))
    d.padstack_circle("Via500", 500, tuple(names))
    d.padstack_circle("Via800", 800, tuple(names))

    # Planes. Ground whole-board on the first inner layer (and the sixth of an 8 layer stack);
    # the supply plane layer is split between 3V3 (upper half) and 1V2 (lower half).
    # The ground plane stops above the through-hole header, whose other-net pins it would touch.
    upper = (0, 0, BOARD_W, -43000)
    d.plane("GND", rect_poly("In1.Cu", *upper))
    if layer_count == 8:
        d.plane("GND", rect_poly("In6.Cu", *upper))
    d.plane("V3V3", rect_poly("In4.Cu", 0, 0, 34000, -36000))
    d.plane("V1V2", rect_poly("In4.Cu", 34000, 0, BOARD_W, -36000))
    # Large pours on the outer layers: the channel between the big parts on both sides, a strip
    # along the top edge and a block below the header. Signals cannot cross a pour of another
    # net on that layer, so the channel is closed on top and bottom.
    if pours == "full":
        d.plane("GND", rect_poly("F.Cu", 500, -500, BOARD_W - 500, -BOARD_H + 500))
        d.plane("GND", rect_poly("B.Cu", 500, -500, BOARD_W - 500, -BOARD_H + 500))
    else:
        d.plane("V12", rect_poly("F.Cu", 31000, -6000, 40500, -40000))
        d.plane("GND", rect_poly("B.Cu", 31000, -6000, 40500, -40000))
        d.plane("GND", rect_poly("F.Cu", 1000, -1000, 71000, -4000))
        d.plane("GND", rect_poly("B.Cu", 48500, -52000, 71000, -59000))
        d.plane("V12", rect_poly("F.Cu", 48500, -52000, 71000, -59000))

    # --- U1: QFP-100 (microcontroller), U4: QFP-64, 4 mm below it.
    power1 = {("L", 0): "V3V3", ("L", 24): "GND", ("B", 0): "GND", ("B", 12): "V3V3",
              ("B", 24): "V1V2", ("R", 0): "V1V2", ("R", 12): "GND", ("R", 24): "V3V3",
              ("T", 0): "GND", ("T", 12): "V1V2", ("T", 24): "V3V3"}
    u1 = qfp(d, "QFP100", 25, 7750, "U1", 18000, -22000, power1)
    power4 = {("L", 0): "V3V3", ("L", 15): "GND", ("B", 0): "GND", ("B", 8): "V3V3",
              ("R", 0): "V1V2", ("R", 15): "GND", ("T", 0): "GND", ("T", 8): "V3V3"}
    u4 = qfp(d, "QFP64", 16, 5750, "U4", 14000, -48000, power4)

    # --- U2: BGA-144, 0.8 mm pitch. Two outer rings are signals, the inner 8 x 8 power.
    letters = "ABCDEFGHJKLM"
    bga = []
    ring_balls = []
    inner_balls = []
    for r in range(12):
        for c in range(12):
            name = "%s%d" % (letters[r], c + 1)
            bga.append(("Ball400", name, (c - 5.5) * 800, (5.5 - r) * 800))
            (ring_balls if min(r, c, 11 - r, 11 - c) < 2 else inner_balls).append("U2-" + name)
    d.image("BGA144", bga)
    d.place("BGA144", "U2", 48500, -14000)

    d.image("FPC40", [("PadFpc", str(i + 1), 0, -9750 + 500 * i) for i in range(40)])
    d.place("FPC40", "J1", 68500, -30000)
    d.image("HDR2X10", [("Thru1700", str(i + 1), (i // 2) * 2540, -(i % 2) * 2540)
                        for i in range(20)])
    d.place("HDR2X10", "J2", 33000, -45000)
    soic = []
    for i in range(4):
        soic.append(("PadSoic", str(i + 1), -2700, 1905 - 1270 * i))
        soic.append(("PadSoic", str(8 - i), 2700, 1905 - 1270 * i))
    d.image("SOIC8", soic)
    d.place("SOIC8", "U3", 27000, -50000)
    d.place("SOIC8", "U5", 63000, -46000)
    d.image("R0402V", [("Pad0402", "1", 0, 500), ("Pad0402", "2", 0, -500)])
    d.image("R0402", [("Pad0402", "1", -500, 0), ("Pad0402", "2", 500, 0)])
    d.image("C0402", [("Pad0402", "1", -500, 0), ("Pad0402", "2", 500, 0)])

    # --- Signals.
    ring = list(ring_balls)
    rnd.shuffle(ring)
    bus_a, bus_b, bus_c, bus_d, rest = ring[:23], ring[23:46], ring[46:54], ring[54:68], ring[68:]
    for k in range(23):
        d.connect("A%02d" % k, u1[("L", k + 1)], bus_a[k])
        d.connect("B%02d" % k, u1[("R", k + 1)], bus_b[k])
    # U1 bottom (22) through series resistors to J1, in reverse order so the nets cross.
    rows = [(3500 + 1400 * i, -36000) for i in range(11)] + [(3500 + 1400 * i, -38500) for i in range(11)]
    for k in range(22):
        ref = "R%d" % (k + 1)
        d.place("R0402V", ref, rows[k][0], rows[k][1])
        d.connect("S%02d" % k, u1[("B", k + 1)], ref + "-1")
        d.connect("F%02d" % k, ref + "-2", "J1-%d" % (23 - k))
    for k in range(8):
        d.connect("C%02d" % k, bus_c[k], "J1-%d" % (24 + k))
    for pin in (1, 39):
        d.connect("GND", "J1-%d" % pin)
    for pin in (38, 40):
        d.connect("V3V3", "J1-%d" % pin)
    # U1 top: 12 to the header, 10 to U4's left side.
    for k in range(12):
        d.connect("H%02d" % k, u1[("T", k + 1)], "J2-%d" % (k + 1))
    for k in range(10):
        d.connect("U%02d" % k, u1[("T", k + 13)], u4[("L", k + 1)])
    # U4: top side to the BGA, right side to J1 and pull resistors.
    for k in range(14):
        d.connect("D%02d" % k, u4[("T", k + 1)], bus_d[k])
    for k in range(6):
        d.connect("G%02d" % k, u4[("R", k + 1)], "J1-%d" % (32 + k))
    pull = [u4[("R", k + 7)] for k in range(8)] + [u4[("L", k + 11)] for k in range(4)]
    for k, pin in enumerate(pull):
        ref = "R%d" % (23 + k)
        d.place("R0402", ref, 22000 + 2200 * k, -54500)
        d.connect("P%02d" % k, pin, ref + "-1")
        d.connect("V3V3", ref + "-2")
    # Header: 4 high-clearance nets to U5, a wide +12 V net to U3, ground and 3V3.
    for k in range(4):
        d.connect("HV%d" % k, "J2-%d" % (13 + k), "U5-%d" % (k + 1), cls="HV")
    d.connect("V12", "J2-17", "U3-1", cls="Power")
    d.connect("V12", "J2-18", cls="Power")
    d.connect("GND", "J2-19")
    d.connect("V3V3", "J2-20")
    d.connect("V3V3", "U3-8", cls="Power")
    d.connect("GND", "U3-4", "U5-5", "U5-8")
    for i, ball in enumerate(rest):
        d.connect(["GND", "V3V3", "V1V2"][i % 3], ball)
    for i, ball in enumerate(inner_balls):
        d.connect(["GND", "GND", "V3V3", "V1V2"][i % 4], ball)

    # --- Decoupling capacitors squeezed against the parts' escape areas.
    sites = (
        [(18000 - 10000, -22000 + dy) for dy in (-6000, -2000, 2000, 6000)]
        + [(18000 + 10000, -22000 + dy) for dy in (-6000, -2000, 2000, 6000)]
        + [(18000 + dx, -22000 + 10000) for dx in (-3000, 3000)]
        + [(14000 - 8200, -48000 + dy) for dy in (-3000, 3000)]
        + [(14000 + 8200, -48000 + dy) for dy in (-3000, 3000)]
        + [(48500 + dx, -14000 + 6300) for dx in (-3000, 0, 3000)]
        + [(48500 + dx, -14000 - 6300) for dx in (-3000, 0, 3000)]
        + [(48500 + 6600, -14000 + dy) for dy in (-2000, 2000)]
    )
    for i, (x, y) in enumerate(sites):
        ref = "C%d" % (i + 1)
        d.place("C0402", ref, x, y)
        d.connect(["V3V3", "V1V2"][(i // 3) % 2], ref + "-1")
        d.connect("GND", ref + "-2")
    return d


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "Synthetic-HardMix.dsn"
    layer_count = int(sys.argv[2]) if len(sys.argv) > 2 else 6
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    pours = sys.argv[4] if len(sys.argv) > 4 else "full"
    design = build(layer_count, seed, pours)
    with open(out, "w") as f:
        f.write(design.dsn())
    pins = sum(len(p) for p in design.nets.values())
    print("%s: %d layers, %s pours, %d nets, %d pins" % (out, layer_count, pours, len(design.nets), pins))


if __name__ == "__main__":
    main()
