"""Add differential-pair rules to a KiCad Specctra DSN export, from the board's net classes.

Usage: python3 dsn_pairs.py <board.kicad_pcb> <in.dsn> <out.dsn> [--gap-um G] [--width-um W]

KiCad's Specctra exporter writes no pair information, although every net class in the .kicad_pro
has a differential pair width and gap. This script finds the pairs the way Freerouting and KiCad do
(net names that differ only in a final "+"/"-", "P"/"N" or "_P"/"_N") and writes, inside
(network ...), per pair whose two nets share a net class:

    (pair (nets <positive> <negative> (gap <um>)))

where the gap is the class's differential pair gap. When the class's differential pair width differs
from its track width, the pair's nets are moved to a copy of the class whose rule has the pair
width, because a DSN net class has one width and Freerouting routes a pair at its class width.

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


def class_blocks(network):
    """Yields (start, end) of each (class ...) block directly inside the network text."""
    depth = 0
    i = 0
    in_quote = False
    while i < len(network):
        c = network[i]
        if c == '"':
            in_quote = not in_quote
        elif not in_quote:
            if c == "(":
                if depth == 1 and re.match(r"\(\s*class\s", network[i:]):
                    end = find_block(network, i)
                    yield i, end
                    i = end
                    continue
                depth += 1
            elif c == ")":
                depth -= 1
        i += 1


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
        for pos, neg in (("+", "-"), ("_P", "_N"), ("P", "N")):
            if n.endswith(pos):
                other = n[: -len(pos)] + neg
                if other in names and (n, other) not in seen:
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
            tail_start = block.index("(", len(head)) if "(" in block[len(head):] else len(block) - 1
            body = block[tail_start:-1]
            new_name = f'"{cls}_pair_{width:g}um"'
            new_body = re.sub(r"\(width\s+[-\d.eE]+\)", f"(width {width:g})", body, count=1)
            original = f'(class {name} {" ".join(kept)} {body})'
            placeholder = '"" ' if '""' in nets else ""
            copy = f'(class {new_name} {placeholder}{" ".join(quote(m) for m in moved)} {new_body})'
            new_network = new_network[:s] + original + "\n    " + copy + new_network[e:]
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
