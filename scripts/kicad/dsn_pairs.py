"""Add differential-pair rules to a KiCad Specctra DSN export, from the board's net classes.

Usage: python3 dsn_pairs.py <board.kicad_pcb> <in.dsn> <out.dsn> [--gap-um G] [--width-um W]

KiCad's Specctra exporter writes no pair information, although every net class in the .kicad_pro
has a differential pair width and gap. This script finds the pairs the way Freerouting and KiCad do
(net names that differ only in a final "+"/"-", "P"/"N", "_P"/"_N" or "DP"/"DM") and writes, inside
(network ...), per pair whose two nets share a net class:

    (pair (nets <positive> <negative> (gap <um>)))

where the gap is the class's differential pair gap. When the class's differential pair width differs
from its track width, the pair's nets are moved to a copy of the class whose rule has the pair
width, because a DSN net class has one width and Freerouting routes a pair at its class width.
Every (class_class ...) rule that names the original class is repeated for the copy, so a clearance
to another class (a high-voltage class, say) still holds for the moved nets.

Power and ground nets (V+/V-, VS+/VS-, GND..., VCC...) are never paired, with the same exclusions
as Freerouting's own name detection. A pair whose two nets are in different net classes gets no
rule and a warning on stderr.

--gap-um and --width-um give the values for pairs whose class has none (or when the .kicad_pro is
missing). Values are in micrometers. Freerouting routes the pairs it finds in the DSN with
--router.autorouter.differential_pairs=auto, the default.
"""

import json
import os
import re
import sys


def tokens(text):
    """Splits an s-expression into "(", ")" and atoms (quoted atoms keep their quotes)."""
    return re.findall(r'\(|\)|"[^"]*"|[^\s()"]+', text)


def find_block(text, start):
    """Returns the end index (exclusive) of the parenthesised block that opens at text[start]."""
    depth = 0
    i = start
    in_quote = False
    while i < len(text):
        c = text[i]
        if c == '"':
            in_quote = not in_quote
        elif not in_quote:
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return i + 1
        i += 1
    raise ValueError("unbalanced parentheses in the DSN")


def class_blocks(network, keyword="class"):
    """Yields (start, end) of each (keyword ...) block directly inside the network text."""
    pattern = re.compile(r"\(\s*" + keyword + r"\s")
    depth = 0
    i = 0
    in_quote = False
    while i < len(network):
        c = network[i]
        if c == '"':
            in_quote = not in_quote
        elif not in_quote:
            if c == "(":
                if depth == 1 and pattern.match(network, i):
                    end = find_block(network, i)
                    yield i, end
                    i = end
                    continue
                depth += 1
            elif c == ")":
                depth -= 1
        i += 1


def first_open(block, start):
    """Index of the first "(" at or after start that is not inside a quoted atom, or -1.

    KiCad's automatic net names contain parentheses, e.g. "Net-(D200-A)", so a plain text search
    for "(" can land inside a net name.
    """
    in_quote = False
    for i in range(start, len(block)):
        c = block[i]
        if c == '"':
            in_quote = not in_quote
        elif c == "(" and not in_quote:
            return i
    return -1


def members_of(block):
    """The net names of a (class NAME net net ... (circuit...) (rule...)) block, and its name."""
    atoms = tokens(block)
    # atoms[0] == "(", atoms[1] == "class", atoms[2] == name
    name = atoms[2]
    nets = []
    for atom in atoms[3:]:
        if atom in ("(", ")"):
            break
        nets.append(atom)
    return name, nets


# Power and ground nets that end in "+"/"-" or "P"/"N" but are not pairs (V+/V-, VS+/VS-): the
# same exclusions as Freerouting's DifferentialPairDetector.isExcludedNet.
POWER_RAIL = re.compile(r"^[+-]?\d+([.V]\d*)?V?$", re.IGNORECASE)


def is_excluded(name):
    local = name.strip().upper().rsplit("/", 1)[-1]
    if not local or POWER_RAIL.match(local):
        return True
    if local in ("GND", "GROUND", "VCC", "VDD", "VSS", "VBUS", "VBAT", "V+", "V-", "VS+", "VS-",
                 "PWR", "POWER"):
        return True
    starts = ("GND_", "GND-", "VCC_", "VDD_", "VSS_", "VBUS_", "VBAT_", "BAT", "VIN", "VOUT")
    ends = ("GND", "VCC", "VDD", "VSS")
    return local.startswith(starts) or local.endswith(ends)


def copy_class_class(network, cls, new_name):
    """Adds, after each (class_class ...) rule that names cls, the same rule for new_name, so a
    clearance between cls and another class (a high-voltage class, say) also holds for the nets
    moved out of cls."""
    out = network
    for s, e in reversed(list(class_blocks(network, "class_class"))):
        block = network[s:e]
        m = re.search(r"\(\s*classes\s", block)
        if not m:
            continue
        c_end = find_block(block, m.start())
        names = tokens(block[m.start():c_end])[2:-1]
        positions = [i for i, n in enumerate(names) if bare(n) == cls]
        copies = []
        # Every combination with at least one cls replaced: (classes A cls) gives (A new);
        # (classes cls cls) gives (new cls), (cls new) and (new new).
        for mask in range(1, 1 << len(positions)):
            renamed = list(names)
            for bit, i in enumerate(positions):
                if mask >> bit & 1:
                    renamed[i] = new_name
            classes = "(classes " + " ".join(renamed) + ")"
            copies.append(block[:m.start()] + classes + block[c_end:])
        if copies:
            out = out[:e] + "".join("\n    " + c for c in copies) + out[e:]
    return out


def quote(name):
    return name if name.startswith('"') else (f'"{name}"' if re.search(r'[\s()"]', name) else name)


def bare(name):
    return name.strip('"')


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    options = {}
    rest = argv[1:]
    for i, a in enumerate(rest):
        if a in ("--gap-um", "--width-um") and i + 1 < len(rest):
            options[a] = float(rest[i + 1])
            args = [x for x in args if x != rest[i + 1]]
    if len(args) != 3:
        print(__doc__)
        return 2
    src_pcb, src_dsn, out = args
    pro = os.path.splitext(src_pcb)[0] + ".kicad_pro"
    classes = {}
    if os.path.exists(pro):
        settings = json.load(open(pro)).get("net_settings", {}) or {}
        classes = {c["name"]: c for c in settings.get("classes", [])}
    default = classes.get("Default", {})

    text = open(src_dsn).read()
    n_start = text.index("(network")
    n_end = find_block(text, n_start)
    network = text[n_start:n_end]

    # The DSN class of each net. KiCad's own class "Default" is exported as "kicad_default".
    net_class = {}
    for s, e in class_blocks(network):
        name, nets = members_of(network[s:e])
        for net in nets:
            if bare(net):
                net_class[bare(net)] = bare(name)
    names = set(net_class)

    pairs = []
    seen = set()
    for n in sorted(names):
        if is_excluded(n):
            continue
        for pos, neg in (("+", "-"), ("_P", "_N"), ("DP", "DM"), ("P", "N")):
            if n.endswith(pos):
                other = n[: -len(pos)] + neg
                if other in names and (n, other) not in seen and not is_excluded(other):
                    pairs.append((n, other))
                    seen.add((n, other))
                    break

    def setting(cls, key, fallback):
        kicad_name = "Default" if cls == "kicad_default" else cls
        value = classes.get(kicad_name, {}).get(key) or default.get(key)
        return value * 1000 if value else fallback

    rules = []
    retarget = {}  # (class, width) -> nets to move into a copy of the class with that width
    for pos, neg in pairs:
        cls = net_class[pos]
        if net_class[neg] != cls:
            print(
                f"warning: {pos} and {neg} are in different net classes ({cls}, {net_class[neg]});"
                " no pair rule written. Put both nets in one class.",
                file=sys.stderr,
            )
            continue
        gap = setting(cls, "diff_pair_gap", options.get("--gap-um"))
        width = setting(cls, "diff_pair_width", options.get("--width-um"))
        track = setting(cls, "track_width", None)
        if gap:
            rules.append(f"\n    (pair (nets {quote(pos)} {quote(neg)} (gap {gap:g})))")
        else:
            rules.append(f"\n    (pair (nets {quote(pos)} {quote(neg)}))")
        if width and track and abs(width - track) > 0.5:
            retarget.setdefault((cls, width), []).extend([pos, neg])

    # Move the nets of pairs with their own width into copies of their class.
    new_network = network
    for (cls, width), moved in retarget.items():
        for s, e in class_blocks(new_network):
            block = new_network[s:e]
            name, nets = members_of(block)
            if bare(name) != cls:
                continue
            kept = [n for n in nets if bare(n) not in moved]
            head = re.match(r"\(\s*class\s+\S+", block).group(0)
            tail_start = first_open(block, len(head))
            if tail_start < 0:
                tail_start = len(block) - 1
            body = block[tail_start:-1]
            new_name = f'"{cls}_pair_{width:g}um"'
            new_body = re.sub(r"\(width\s+[-\d.eE]+\)", f"(width {width:g})", body, count=1)
            original = f'(class {name} {" ".join(kept)} {body})'
            placeholder = '"" ' if '""' in nets else ""
            copy = f'(class {new_name} {placeholder}{" ".join(quote(m) for m in moved)} {new_body})'
            new_network = new_network[:s] + original + "\n    " + copy + new_network[e:]
            new_network = copy_class_class(new_network, cls, new_name)
            break

    insert_at = new_network.rindex(")")
    new_network = new_network[:insert_at] + "".join(rules) + "\n  " + new_network[insert_at:]
    with open(out, "w") as f:
        f.write(text[:n_start] + new_network + text[n_end:])
    print(
        f"{out}: {len(rules)} pair rule(s), {len(retarget)} class(es) copied for a pair width "
        f"({len(pairs)} name pair(s) found)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
