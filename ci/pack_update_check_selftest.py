#!/usr/bin/env python3
"""Pins what pack_update_check.py reports, including against a REAL payload.

The comparison logic is trivial; what is not is agreeing with GitHub about where
a digest lives. That is checked against a recorded `releases/latest` response
from a real repository rather than a hand-written fixture, because a fixture
written from memory proves only that the memory is self-consistent.

Run: python3 ci/pack_update_check_selftest.py
"""

import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import pack_update_check as C  # noqa: E402

FAILURES = []


def check(what, got, want):
    if got != want:
        FAILURES.append(f"{what}\n    got  {got!r}\n    want {want!r}")


def release(tag, assets):
    return {"tag_name": tag, "published_at": "2026-09-09T00:00:00Z",
            "assets": [{"name": name, "digest": digest} for name, digest in assets]}


LOADED_AMTV4 = {"amtv4": {"version": "2026.09.1", "digest": "sha256:aaaa",
                          "assertions": 200}}

# --- in sync, which must be quiet ------------------------------------------
lines, drift = C.compare(
    LOADED_AMTV4,
    C.published_packs(release("2026.09.1", [("amtv4-pack.json", "sha256:AAAA")]),
                      {"amtv4": "amtv4-pack.json"}))
check("a matching digest is in sync", drift, False)
check("and says so once", [l.strip().split()[0] for l in lines], ["in"])
# Note the fixture above: loaded `sha256:aaaa` against published `sha256:AAAA`.
# Case is not a difference - GitHub lowercases its digests and a values file may
# not - so that in-sync result IS the case-insensitivity check.

# --- a newer pack, which is the whole point --------------------------------
lines, drift = C.compare(
    LOADED_AMTV4,
    C.published_packs(release("2026.09.2", [("amtv4-pack.json", "sha256:bbbb")]),
                      {"amtv4": "amtv4-pack.json"}))
check("a different digest is drift", drift, True)
check("naming both sides", "loaded 2026.09.1" in lines[0]
      and "published 2026.09.2" in lines[0], True)

# --- same version, different bytes: exactly what a pin exists to catch -----
lines, drift = C.compare(
    LOADED_AMTV4,
    C.published_packs(release("2026.09.1", [("amtv4-pack.json", "sha256:cccc")]),
                      {"amtv4": "amtv4-pack.json"}))
check("same version with different bytes is drift", drift, True)

# --- a pack published and not deployed at all ------------------------------
lines, drift = C.compare(
    {},
    C.published_packs(release("2026.09.1", [("amtv4-pack.json", "sha256:aaaa")]),
                      {"amtv4": "amtv4-pack.json"}))
check("a pack the deployment does not have is drift", drift, True)
check("and is described as not loaded", "NOT LOADED" in lines[0], True)

# --- a registry that states no digest cannot be compared -------------------
lines, drift = C.compare(
    LOADED_AMTV4,
    C.published_packs(release("2026.09.1", [("amtv4-pack.json", "")]),
                      {"amtv4": "amtv4-pack.json"}))
check("no digest is drift, not silence", drift, True)
check("and says why", "NO DIGEST" in lines[0], True)

# --- a renamed asset must not read as in sync ------------------------------
lines, drift = C.compare(
    LOADED_AMTV4,
    C.published_packs(release("2026.09.1", [("amt-pack.json", "sha256:aaaa")]),
                      {"amtv4": "amtv4-pack.json"}))
check("a missing asset is drift", drift, True)
check("naming what the release does have", "amt-pack.json" in lines[0], True)

# --- mappings are explicit -------------------------------------------------
check("a mapping parses", C.parse_mappings(["amtv4=amtv4-pack.json"]),
      {"amtv4": "amtv4-pack.json"})
try:
    C.parse_mappings(["amtv4"])
    FAILURES.append("a mapping without an asset should be refused")
except SystemExit:
    pass

# --- the shape GitHub actually returns -------------------------------------
recorded = pathlib.Path(__file__).with_name("testdata-github-release.json")
if recorded.exists():
    real = json.loads(recorded.read_text())
    asset = (real.get("assets") or [{}])[0]
    check("a real release names its assets", isinstance(asset.get("name"), str), True)
    check("a real asset carries a sha256 digest",
          str(asset.get("digest", "")).startswith("sha256:"), True)
    published = C.published_packs(real, {"probe": asset["name"]})
    check("which this reads without special-casing",
          published["probe"]["digest"], asset["digest"].lower())
    check("and takes the tag as the version",
          published["probe"]["version"], real["tag_name"])
else:
    FAILURES.append(f"{recorded.name} is missing - the GitHub shape is unverified")

if FAILURES:
    print(f"FAIL: {len(FAILURES)} of the pinned rules broke\n")
    for f in FAILURES:
        print(f"  {f}\n")
    sys.exit(1)
print("OK: drift is reported, and the GitHub payload shape is the real one")
