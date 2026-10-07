#!/usr/bin/env python3
"""Find the release a package was built on, make sure RVF holds it, name it.

The package says what it was built on. The Full module dependency file carries
one row per release of each module, so for the edition module the newest
effectiveTime strictly before the package's own is its predecessor:

    20260831  20260930  20261031   <- a 20261031 build: predecessor 20260930

The old step instead asked RVF for "the newest release it happens to hold". When
September had never been kept, an October build was compared against August,
and every September change was reported as a Full-file defect - 11 failures,
up to 12,508 findings each, none of them real.

Published releases sit at a fixed place on the share,
<directory>/<yyyymmdd>/<one zip>, so a missing predecessor is fetched and kept
here. If it is not on the share either, the run FAILS: that is the short window
between versioning a release and publishing it, and comparing against an older
month in the meantime would produce exactly the false failures above.

Writes the chosen filename to stdout's last line as `previousRelease=<name>`
(empty for a genuine first release). Exit 1 with an explanation otherwise.

Usage (all I/O injected, so it is testable without Azure or RVF):
    ensure_previous_release.py --package <zip> --module <sctid>
        --kept-cmd '<cmd printing kept names, one per line>'
        --list-cmd '<cmd listing zips in a share dir; {dir} substituted>'
        --fetch-and-keep-cmd '<cmd; {dir} {name} {version} substituted>'
        --share-root <prefix, e.g. prod/AU_32506021000036107>
"""
import argparse
import io
import re
import shlex
import subprocess
import sys
import zipfile

EFFECTIVE_TIME = re.compile(r"(?<!\d)(\d{8})(?:T\d{6}Z)?(?!\d)")


def module_release_dates(package, module):
    """Every effectiveTime at which `module` declared its dependencies."""
    with zipfile.ZipFile(package) as zf:
        names = [n for n in zf.namelist()
                 if "ModuleDependencyFull" in n and n.endswith(".txt")]
        if not names:
            raise SystemExit(f"FAIL: {package} has no Full module dependency file, "
                             "so the release it was built on cannot be read from it")
        dates = set()
        for name in names:
            with zf.open(name) as raw:
                rows = io.TextIOWrapper(raw, encoding="utf-8")
                header = next(rows).rstrip("\r\n").split("\t")
                et, mod = header.index("effectiveTime"), header.index("moduleId")
                for line in rows:
                    cols = line.rstrip("\r\n").split("\t")
                    if len(cols) > mod and cols[mod] == module:
                        dates.add(cols[et])
    return dates


def predecessor(dates, own):
    earlier = sorted(d for d in dates if d < own)
    return earlier[-1] if earlier else None


def run(cmd):
    out = subprocess.run(cmd, shell=True, check=True, capture_output=True, text=True).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def kept_for(kept, version):
    return [n for n in kept if version in EFFECTIVE_TIME.findall(n)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--package", required=True)
    ap.add_argument("--module", required=True)
    ap.add_argument("--kept-cmd", required=True)
    ap.add_argument("--list-cmd", required=True)
    ap.add_argument("--fetch-and-keep-cmd", required=True)
    ap.add_argument("--share-root", required=True)
    a = ap.parse_args()

    dates = module_release_dates(a.package, a.module)
    if not dates:
        raise SystemExit(f"FAIL: module {a.module} has no MDRS rows in {a.package} - wrong module?")
    own = max(dates)
    prev = predecessor(dates, own)
    print(f"package module {a.module}: releases {', '.join(sorted(dates)[-4:])}  (own {own})")
    if prev is None:
        print("no earlier release of this module: a first release, nothing to compare against")
        print("previousRelease=")
        return
    print(f"built on: {prev}")

    names = kept_for(run(a.kept_cmd), prev)
    if not names:
        directory = f"{a.share_root}/{prev}"
        zips = [z for z in run(a.list_cmd.format(dir=shlex.quote(directory))) if z.endswith(".zip")]
        if not zips:
            raise SystemExit(
                f"FAIL: this package was built on {prev}, which RVF does not hold and which is "
                f"not published at {directory} yet. Release-type checks would compare against "
                "an older month and report its changes as defects, so the run stops instead. "
                "It will pass once the release is on the share.")
        if len(zips) > 1:
            raise SystemExit(f"FAIL: {len(zips)} zips in {directory}: {zips} - keep the right one "
                             "with the rvf-keep-release pipeline")
        print(f"not kept: fetching {directory}/{zips[0]} and keeping it")
        subprocess.run(a.fetch_and_keep_cmd.format(dir=shlex.quote(directory),
                                                   name=shlex.quote(zips[0]), version=prev),
                       shell=True, check=True)
        names = kept_for(run(a.kept_cmd), prev)
        if not names:
            raise SystemExit(f"FAIL: kept {zips[0]} but RVF still lists no release dated {prev}")
    if len(names) > 1:
        production = [n for n in names if "PRODUCTION" in n.upper()]
        names = production or names
    print(f"previousRelease={sorted(names)[-1]}")


if __name__ == "__main__":
    main()
