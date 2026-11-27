#!/usr/bin/env python3
"""Build app/src/main/assets/gamedb.bin from the two cores' own game lists.

Inputs (not stored in this repo):
  --mame   metadata/mame2003-plus.xml from github.com/libretro/mame2003-plus-libretro
  --fbneo  "dats/FinalBurn Neo (ClrMame Pro XML, Arcade only).dat" from github.com/libretro/FBNeo

Binary layout (big-endian), read by GameDb.kt:
  "RADB" | u32 version | u32 coreCount
  per core:  str coreId | u32 gameCount
  per game:  str name | str title | str parent | str bios
             u8 flags (1=vertical, 2=bios set, 4=preliminary driver) | u8 buttons (255=unknown)
             u8 stick (0=unknown, 1=8-way, 2=4-way, 3=analog) | u16 romCount
             romCount x (u32 crc, u32 size)
  str = u16 byteLength + UTF-8 bytes
ROM lists hold only the game's own and parent ROMs; BIOS ROMs are left out and
represented by the `bios` set name instead.
"""
import argparse
import struct
import sys
import xml.etree.ElementTree as ET

FLAG_VERTICAL, FLAG_BIOS, FLAG_PRELIM = 1, 2, 4

# MAME's "control" attribute -> the kind of stick the cabinet has.
STICK_UNKNOWN, STICK_8WAY, STICK_4WAY, STICK_ANALOG = 0, 1, 2, 3
STICK_TYPES = {
    "joy8way": STICK_8WAY, "doublejoy8way": STICK_8WAY,
    "joy4way": STICK_4WAY, "doublejoy4way": STICK_4WAY,
    "stick": STICK_ANALOG, "paddle": STICK_ANALOG, "dial": STICK_ANALOG,
    "trackball": STICK_ANALOG, "lightgun": STICK_ANALOG,
}


def parse(path, bios_attr):
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
        is_bios = el.get(bios_attr[0]) == bios_attr[1]
        games[name] = {
            "name": name,
            "title": (el.findtext("description") or name).strip(),
            "cloneof": el.get("cloneof") or "",
            "romof": el.get("romof") or "",
            "roms": roms,
            "vertical": video is not None and video.get("orientation") == "vertical",
            "prelim": driver is not None and driver.get("status") == "preliminary",
            "is_bios": is_bios,
            "buttons": int(inp.get("buttons") or 0) if inp is not None else 0,
            "stick": STICK_TYPES.get(inp.get("control") or "", STICK_UNKNOWN) if inp is not None else STICK_UNKNOWN,
        }
        el.clear()
    return games


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
        g["own"] = [(c, s) for c, s, merged in g["roms"] if not (merged and c in bios_crcs)]


def put_str(out, s):
    b = s.encode("utf-8")
    if len(b) > 65535:
        raise ValueError("string too long: " + s[:40])
    out += struct.pack(">H", len(b)) + b


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mame", required=True)
    ap.add_argument("--fbneo", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    mame = parse(a.mame, ("runnable", "no"))
    fbneo = parse(a.fbneo, ("isbios", "yes"))
    resolve(mame)
    resolve(fbneo)

    # FBNeo's list carries no control details: borrow MAME's for the same set (or its
    # parent). Neo Geo is always 4 buttons and an 8-way stick; 255 buttons = "unknown".
    for g in fbneo.values():
        if g["bios"] == "neogeo":
            g["buttons"] = 4
            g["stick"] = STICK_8WAY
            continue
        g["buttons"] = 255
        for key in (g["name"], g["parent"]):
            if key and key in mame:
                g["buttons"] = mame[key]["buttons"]
                g["stick"] = mame[key]["stick"]
                break

    out = bytearray(b"RADB")
    out += struct.pack(">II", 2, 2)
    for core_id, games in (("fbneo", fbneo), ("mame2003_plus", mame)):
        put_str(out, core_id)
        out += struct.pack(">I", len(games))
        for name in sorted(games):
            g = games[name]
            flags = (FLAG_VERTICAL if g["vertical"] else 0) | (FLAG_BIOS if g["is_bios"] else 0) \
                | (FLAG_PRELIM if g["prelim"] else 0)
            put_str(out, g["name"])
            put_str(out, g["title"])
            put_str(out, g["parent"])
            put_str(out, g["bios"])
            out += struct.pack(">BBBH", flags, min(g["buttons"], 255), g["stick"], len(g["own"]))
            for crc, size in g["own"]:
                out += struct.pack(">II", crc, size & 0xFFFFFFFF)
    with open(a.out, "wb") as f:
        f.write(out)
    print(f"fbneo={len(fbneo)} mame2003_plus={len(mame)} bytes={len(out)}", file=sys.stderr)


if __name__ == "__main__":
    main()
