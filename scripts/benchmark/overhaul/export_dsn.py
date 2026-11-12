"""Export a .kicad_pcb to Specctra DSN, removing unlocked tracks and vias first.

Usage: <KiCad python> -I export_dsn.py board.kicad_pcb out.dsn
Locked tracks and vias are kept (pre-routed copper). Zones are exported as KiCad does.
It needs KiCad's own Python (pcbnew), e.g. on Windows
%LOCALAPPDATA%/Programs/KiCad/10.0/bin/python.exe; pcbench_prep.py runs it as "python3", so put
that interpreter first on PATH or run this script directly for each board.
"""
import sys

src, dst = sys.argv[1:3]
try:
    import wx
    app = wx.App(False)
    wx.Log.EnableLogging(False)
except Exception:
    pass
import pcbnew

board = pcbnew.LoadBoard(src)
removed = kept = 0
for t in list(board.GetTracks()):
    if t.IsLocked():
        kept += 1
    else:
        board.Remove(t)
        removed += 1
ok = pcbnew.ExportSpecctraDSN(board, dst)
print("removed %d tracks/vias, kept %d locked, export %s" % (removed, kept, "ok" if ok else "FAILED"))
sys.exit(0 if ok else 1)
