#!/usr/bin/env python3
"""Build app/src/main/assets/gamedb.bin from the three cores' own game lists.

Inputs (not stored in this repo):
  --mame      metadata/mame2003-plus.xml from github.com/libretro/mame2003-plus-libretro
  --fbneo     "dats/FinalBurn Neo (ClrMame Pro XML, Arcade only).dat" from github.com/libretro/FBNeo
  --mame-src  src/mame of github.com/libretro/mame (the current MAME core). Only the
              driver files named in src/mame/arcade.flt are needed; that file decides
              which drivers the "mamearcade" build of the core contains.

Binary layout (big-endian), read by GameDb.kt:
  "RADB" | u32 version | u32 coreCount
  per core:  str coreId | u32 gameCount
  per game:  str name | str title | str parent | str bios
             u8 flags (1=vertical, 2=bios set, 4=preliminary driver, 8=needs a CHD disk image)
             u8 buttons (255=unknown)
             u8 stick (0=unknown, 1=8-way, 2=4-way, 3=analog) | u16 romCount
             romCount x (u32 crc, u32 size)
  str = u16 byteLength + UTF-8 bytes
ROM lists hold only the game's own and parent ROMs; BIOS ROMs are left out and
represented by the `bios` set name instead.
"""
import argparse
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET

FLAG_VERTICAL, FLAG_BIOS, FLAG_PRELIM, FLAG_DISK = 1, 2, 4, 8

# MAME's "control" attribute -> the kind of stick the cabinet has.
STICK_UNKNOWN, STICK_8WAY, STICK_4WAY, STICK_ANALOG = 0, 1, 2, 3
STICK_TYPES = {
    "joy8way": STICK_8WAY, "doublejoy8way": STICK_8WAY,
    "joy4way": STICK_4WAY, "doublejoy4way": STICK_4WAY,
    "stick": STICK_ANALOG, "paddle": STICK_ANALOG, "dial": STICK_ANALOG,
    "trackball": STICK_ANALOG, "lightgun": STICK_ANALOG,
}
BUTTONS_UNKNOWN = 255


# ---- XML game lists (MAME 2003-Plus, FBNeo) ---------------------------------------------

def parse_xml(path, bios_attr):
    games = {}
    for _, el in ET.iterparse(path, events=("end",)):
        if el.tag != "game":
            continue
        name = el.get("name")
        roms = []
        for r in el.findall("rom"):
            crc = r.get("crc")
            if not crc or r.get("status") == "nodump":
                continue
            roms.append((int(crc, 16), int(r.get("size") or 0), r.get("merge") is not None))
        video = el.find("video")
        driver = el.find("driver")
        inp = el.find("input")
        games[name] = {
            "name": name,
            "title": (el.findtext("description") or name).strip(),
            "cloneof": el.get("cloneof") or "",
            "romof": el.get("romof") or "",
            "roms": roms,
            "vertical": video is not None and video.get("orientation") == "vertical",
            "prelim": driver is not None and driver.get("status") == "preliminary",
            "is_bios": el.get(bios_attr[0]) == bios_attr[1],
            "disk": el.find("disk") is not None,
            "buttons": int(inp.get("buttons") or 0) if inp is not None else 0,
            "stick": STICK_TYPES.get(inp.get("control") or "", STICK_UNKNOWN) if inp is not None else STICK_UNKNOWN,
        }
        el.clear()
    return games


# ---- current MAME: read the driver sources -----------------------------------------------
#
# The current MAME core has no ready-made list with checksums, but its driver sources
# declare every game (GAME / GAMEL macros) and every ROM (ROM_START ... ROM_END blocks),
# so the list is read from the same sources the core is built from.

def strip_comments(text):
    """Remove // and /* */ comments, leaving string literals alone."""
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            # keep line structure so that line-based parsing still works
            block = text[i:(n if j < 0 else j + 2)]
            out.append("\n" * block.count("\n"))
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_disabled_blocks(text):
    """Drop the contents of `#if 0 ... #endif` (or up to `#else`)."""
    out = []
    depth = 0          # nesting inside a disabled block
    skipping = False
    for line in text.split("\n"):
        s = line.strip()
        if skipping:
            if s.startswith("#if"):
                depth += 1
            elif s.startswith("#endif"):
                if depth == 0:
                    skipping = False
                else:
                    depth -= 1
            elif s.startswith("#else") and depth == 0:
                skipping = False
            out.append("")
            continue
        if re.match(r"#\s*if\s+0\b", s):
            skipping = True
            depth = 0
            out.append("")
            continue
        out.append(line)
    return "\n".join(out)


def split_args(text, start):
    """Split a macro call's arguments. `start` is the index just after its '('.
    Returns (args, index after the closing ')'), or (None, start) if unbalanced."""
    args, cur = [], []
    depth = 0
    i, n = start, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            cur.append(text[i:j + 1])
            i = j + 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            if depth == 0:
                args.append("".join(cur).strip())
                return args, i + 1
            depth -= 1
        elif c == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(c)
        i += 1
    return None, start


def string_literal(arg):
    """Value of one or more adjacent C string literals."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', arg)
    value = "".join(parts)
    value = value.replace('\\"', '"').replace("\\\\", "\\")
    return value


def int_literal(arg):
    arg = arg.strip()
    try:
        return int(arg, 0)
    except ValueError:
        return 0


ROM_START_RE = re.compile(r"\bROM_START\s*\(\s*(\w+)\s*\)")
ROM_END_RE = re.compile(r"\bROM_END\b")
# Any macro call inside a ROM block: besides ROM_LOAD and its relatives, drivers have
# their own loaders (NEO_SFIX_128K, ROM_LOAD_VROM, ...). Whatever carries a CRC is a ROM.
CALL_RE = re.compile(r"\b([A-Z][A-Z0-9_]*)\s*\(")
CRC_RE = re.compile(r"\bCRC\s*\(\s*([0-9a-fA-F]{1,8})\s*\)")
# GAME / GAMEL declare arcade machines. A few BIOS roots (Neo Geo) are declared as CONS.
GAME_RE = re.compile(r"(?m)^[ \t]*(GAMEL?|CONS|COMP|SYST)\s*\(")


def parse_source_file(text, games):
    text = strip_disabled_blocks(strip_comments(text))

    # ROM blocks.
    rom_sets = {}
    disk_sets = set()
    pos = 0
    while True:
        m = ROM_START_RE.search(text, pos)
        if not m:
            break
        end = ROM_END_RE.search(text, m.end())
        if not end:
            break
        body = text[m.end():end.start()]
        roms = []
        at = 0
        while True:
            call = CALL_RE.search(body, at)
            if not call:
                break
            args, after = split_args(body, call.end())
            if args is None:
                at = call.end()
                continue
            at = after                        # also skips the CRC(...) SHA1(...) calls inside
            if call.group(1).startswith("DISK_IMAGE"):
                disk_sets.add(m.group(1))
                continue
            crc_index = next((k for k, a in enumerate(args) if CRC_RE.search(a)), -1)
            if crc_index < 0:
                continue                      # ROM_REGION, ROM_CONTINUE, NO_DUMP ...
            crc = int(CRC_RE.search(args[crc_index]).group(1), 16)
            size = int_literal(args[crc_index - 1]) if crc_index >= 1 else 0
            if size < 0 or size > 0x40000000:
                size = 0                      # that argument was not a length
            roms.append((crc, size, True))
        rom_sets[m.group(1)] = roms
        pos = end.end()

    # Game declarations:
    # GAME( year, name, parent, machine, input, class, init, monitor, company, fullname, flags )
    # CONS( year, name, parent, compat, machine, input, class, init, company, fullname, flags )
    for m in GAME_RE.finditer(text):
        args, _ = split_args(text, m.end())
        if not args or len(args) < 11:
            continue
        name = args[1]
        if not re.fullmatch(r"\w+", name) or name not in rom_sets:
            continue
        flags = args[10]
        arcade = m.group(1).startswith("GAME")
        if not arcade and "MACHINE_IS_BIOS_ROOT" not in flags:
            continue
        parent = args[2] if re.fullmatch(r"\w+", args[2]) and args[2] != "0" else ""
        games[name] = {
            "name": name,
            "title": string_literal(args[9]) or name,
            "cloneof": parent,                 # fixed up below once BIOS sets are known
            "romof": parent,
            "roms": rom_sets[name],
            "vertical": arcade and bool(re.search(r"\bROT(90|270)\b", args[7])),
            "prelim": "MACHINE_NOT_WORKING" in flags,
            "is_bios": "MACHINE_IS_BIOS_ROOT" in flags,
            "disk": name in disk_sets,
            "buttons": BUTTONS_UNKNOWN,
            "stick": STICK_UNKNOWN,
        }


def parse_mame_sources(src_dir):
    """Games of the 'arcade' build: the driver files listed in arcade.flt, minus the
    individual drivers it switches off with a leading '-'."""
    files, excluded = [], set()
    with open(os.path.join(src_dir, "arcade.flt"), encoding="utf-8", errors="replace") as f:
        for raw in f:
            if raw.startswith("//"):
                continue
            line = raw.split("//")[0].strip()
            if re.fullmatch(r"[a-z0-9_]+/[A-Za-z0-9_./-]+\.cpp", line):
                files.append(line)
            elif re.fullmatch(r"-\w+", line):
                excluded.add(line[1:])
    games = {}
    missing = 0
    for rel in files:
        path = os.path.join(src_dir, rel)
        if not os.path.exists(path):
            missing += 1
            continue
        with open(path, encoding="utf-8", errors="replace") as f:
            parse_source_file(f.read(), games)
    for name in excluded:
        games.pop(name, None)
    # A clone's parent must be a real game; a BIOS root is not a "clone of" relation.
    for g in games.values():
        p = g["cloneof"]
        if p not in games:
            g["cloneof"] = g["romof"] = ""
        elif games[p]["is_bios"]:
            g["cloneof"] = ""
    print(f"mame sources: {len(files)} driver files ({missing} not found), {len(games)} games", file=sys.stderr)
    return games


# ---- shared ------------------------------------------------------------------------------

def resolve(games):
    """Split each game's romof chain into parent (a real game) and bios (a BIOS set)."""
    for g in games.values():
        parent = g["cloneof"] if g["cloneof"] in games else ""
        bios = ""
        seen = set()
        cur = g
        while cur["romof"] and cur["romof"] in games and cur["romof"] not in seen:
            seen.add(cur["romof"])
            cur = games[cur["romof"]]
            if cur["is_bios"]:
                bios = cur["name"]
                break
        if not parent and g["romof"] in games and not games[g["romof"]]["is_bios"]:
            parent = g["romof"]
        g["parent"], g["bios"] = parent, bios
    for g in games.values():
        if g["is_bios"]:
            g["own"] = [(c, s) for c, s, _ in g["roms"]]
            continue
        bios_crcs = {c for c, _, _ in games[g["bios"]]["roms"]} if g["bios"] else set()
        own, seen = [], set()
        for c, s, merged in g["roms"]:
            if merged and c in bios_crcs:
                continue
            if c in seen:
                continue                      # the same file loaded into two regions
            seen.add(c)
            own.append((c, s))
        g["own"] = own


def borrow_controls(games, mame2003, neogeo_bios="neogeo"):
    """Lists without control details borrow MAME 2003-Plus's for the same set (or its
    parent). Neo Geo is always 4 buttons and an 8-way stick."""
    for g in games.values():
        if g["bios"] == neogeo_bios:
            g["buttons"], g["stick"] = 4, STICK_8WAY
            continue
        g["buttons"], g["stick"] = BUTTONS_UNKNOWN, STICK_UNKNOWN
        for key in (g["name"], g["parent"]):
            if key and key in mame2003:
                g["buttons"] = mame2003[key]["buttons"]
                g["stick"] = mame2003[key]["stick"]
                break


def put_str(out, s):
    b = s.encode("utf-8")
    if len(b) > 65535:
        raise ValueError("string too long: " + s[:40])
    out += struct.pack(">H", len(b)) + b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mame", required=True)
    ap.add_argument("--fbneo", required=True)
    ap.add_argument("--mame-src", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    mame2003 = parse_xml(a.mame, ("runnable", "no"))
    fbneo = parse_xml(a.fbneo, ("isbios", "yes"))
    current = parse_mame_sources(a.mame_src)
    for games in (mame2003, fbneo, current):
        resolve(games)
    borrow_controls(fbneo, mame2003)
    borrow_controls(current, mame2003)

    cores = (("fbneo", fbneo), ("mame2003_plus", mame2003), ("mame_current", current))
    out = bytearray(b"RADB")
    out += struct.pack(">II", 2, len(cores))
    for core_id, games in cores:
        put_str(out, core_id)
        out += struct.pack(">I", len(games))
        for name in sorted(games):
            g = games[name]
            flags = (FLAG_VERTICAL if g["vertical"] else 0) | (FLAG_BIOS if g["is_bios"] else 0) \
                | (FLAG_PRELIM if g["prelim"] else 0) | (FLAG_DISK if g["disk"] else 0)
            put_str(out, g["name"])
            put_str(out, g["title"])
            put_str(out, g["parent"])
            put_str(out, g["bios"])
            own = g["own"][:65535]
            out += struct.pack(">BBBH", flags, min(g["buttons"], 255), g["stick"], len(own))
            for crc, size in own:
                out += struct.pack(">II", crc, size & 0xFFFFFFFF)
    with open(a.out, "wb") as f:
        f.write(out)
    print(" ".join(f"{cid}={len(g)}" for cid, g in cores) + f" bytes={len(out)}", file=sys.stderr)


if __name__ == "__main__":
    main()
