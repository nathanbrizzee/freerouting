#!/usr/bin/env python3
"""Add blind via padstacks to a KiCad-exported DSN and allow them in every class.

Usage: dsn_blind.py in.dsn out.dsn FIRST-LAST:DIAM[:DRILL] ...
Example: dsn_blind.py logicbone.dsn logicbone_blind.dsn 0-2:450 0-5:500

Each span names the layer indices (0 = first layer in the structure) the via spans. The new padstack
copies the existing through via's shape per layer with the given diameter, is listed in the
structure's (via ...) list and appended to every class's (use_via ...).
"""
import re
import sys

src, dst, *spans = sys.argv[1:]
text = open(src, encoding="utf-8", errors="replace").read()

layers = re.findall(r"\(layer\s+(\S+)\s*\n?\s*\(type", text)
if not layers:
    layers = re.findall(r"\(layer\s+(\S+)", text)
through = re.search(r'\(via\s+("?Via\[[^"\s)]+"?)', text).group(1)
through_name = through.strip('"')
drill = re.search(r":(\d+)_um", through_name)
drill = drill.group(1) if drill else "200"

new_names = []
new_padstacks = []
for span in spans:
    rng, diam, *rest = span.split(":")
    first, last = (int(v) for v in rng.split("-"))
    d = rest[0] if rest else drill
    name = '"Via[%d-%d]_%s:%s_um"' % (first, last, diam, d)
    new_names.append(name)
    shapes = "".join(
        "\n      (shape (circle %s %s))" % (layers[i], diam) for i in range(first, last + 1)
    )
    new_padstacks.append("    (padstack %s%s\n      (attach off)\n    )\n" % (name, shapes))

# structure via list
text = text.replace("(via %s)" % through, "(via %s %s)" % (through, " ".join(new_names)), 1)
# class use_via lists
text = re.sub(
    r"\(use_via\s+%s\)" % re.escape(through),
    "(use_via %s %s)" % (through, " ".join(new_names)),
    text,
)
# padstack definitions: before the through via's padstack
marker = "    (padstack %s" % through
pos = text.index(marker)
text = text[:pos] + "".join(new_padstacks) + text[pos:]
open(dst, "w", encoding="utf-8").write(text)
print("layers", layers)
print("added", new_names, "to", dst)
