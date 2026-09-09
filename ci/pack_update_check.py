#!/usr/bin/env python3
"""Is a newer assertion pack published than the one the deployment has loaded?

A NOTIFICATION, never an action. The moment the server follows a channel -
"fetch whatever is newest" - `latest` has been reinvented, and a 4am validation
starts running assertions nobody chose. So this lives in a pipeline, compares,
and says so. Applying the update stays a values-file change plus a POST.

Two kinds of drift, and only one of them needs this tool:

* CONFIGURED but not loaded - the values file was updated and nobody POSTed a
  refresh. The server answers that itself, on `GET /assertions/packs` as
  `pendingRefresh`, because it knows both sides. Reported here for one place to
  look, not re-derived.
* PUBLISHED but not pinned - a newer pack exists in the registry than the
  deployment pins. The server CANNOT know this: a pinned digest is all it has.
  That is what this adds.

The comparison is on DIGEST, not version. A version is a label someone types; a
digest is what would actually execute, and "same version, different bytes" is
precisely the case a pin exists to catch.

Registry: GitHub releases. Each release asset carries `digest: "sha256:..."`
in the API response, so nothing has to be invented or fetched to compare - the
pack itself is never downloaded here.
"""

import argparse
import json
import os
import pathlib
import re
import sys
import urllib.request

# The RVF API's own header scheme, matching ci/engine_ab.py.
RVF_AUTH = {
    "X-AUTH-username": "pack-update-check",
    "X-AUTH-roles": "ROLE_ihtsdo-ops-admin",
    "X-AUTH-token": "local",
}


def get_json(url, headers=None, timeout=60):
    request = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8-sig"))


def loaded_packs(packs_url, headers):
    """{name: {version, digest}} plus the server's own pending list."""
    body = get_json(packs_url, headers)
    loaded = {}
    for pack in body.get("packs") or []:
        loaded[pack.get("name")] = {
            "version": pack.get("version"),
            "digest": (pack.get("digest") or "").lower(),
            "assertions": pack.get("assertions"),
        }
    return loaded, body.get("pending") or []


def latest_release(repo, token=None, api="https://api.github.com"):
    headers = {"Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return get_json(f"{api}/repos/{repo}/releases/latest", headers)


def published_packs(release, mappings):
    """{pack name: {version, digest, asset}} for each mapped asset.

    The release tag is the version, and the asset's own `digest` is the
    comparison. An asset with no digest is reported rather than skipped: the
    registry not stating one is exactly the case where "is this the same pack"
    cannot be answered, and silence there would read as "in sync".
    """
    by_asset = {asset.get("name"): asset for asset in release.get("assets") or []}
    out = {}
    for pack, asset_name in mappings.items():
        asset = by_asset.get(asset_name)
        if asset is None:
            out[pack] = {"missing": f"the latest release has no asset {asset_name!r} "
                                    f"(it has {sorted(by_asset)})"}
            continue
        out[pack] = {
            "version": release.get("tag_name"),
            "digest": (asset.get("digest") or "").lower(),
            "asset": asset_name,
            "published": release.get("published_at"),
        }
    return out


def compare(loaded, published):
    """One line per pack, and whether any of it is drift."""
    lines, drift = [], False
    for pack, available in sorted(published.items()):
        if "missing" in available:
            drift = True
            lines.append(f"  UNKNOWN     {pack}: {available['missing']}")
            continue
        have = loaded.get(pack)
        if have is None:
            drift = True
            lines.append(f"  NOT LOADED  {pack} {available['version']} is published "
                         f"({available['digest'][:23]}...) and the deployment has no "
                         f"such pack")
            continue
        if not available["digest"]:
            drift = True
            lines.append(f"  NO DIGEST   {pack}: the registry states no digest for "
                         f"{available['asset']}, so nothing can be compared")
            continue
        if have["digest"] == available["digest"]:
            lines.append(f"  in sync     {pack} {have['version']} "
                         f"({have['digest'][:23]}...)")
            continue
        drift = True
        lines.append(f"  DRIFT       {pack}: loaded {have['version']} "
                     f"{have['digest'][:23]}..., published {available['version']} "
                     f"{available['digest'][:23]}...")
    return lines, drift


def parse_mappings(values):
    """`amtv4=amtv4-pack.json` pairs, explicit rather than guessed.

    Deriving a pack name from an asset filename would work until an asset is
    renamed, at which point the check would silently compare nothing.
    """
    mappings = {}
    for value in values:
        pack, _, asset = value.partition("=")
        if not pack or not asset:
            raise SystemExit(f"FATAL: --pack {value!r} is not NAME=ASSET")
        mappings[pack] = asset
    return mappings


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--packs-url", required=True,
                    help="the deployment's GET /assertions/packs")
    ap.add_argument("--header", action="append", default=[], metavar="NAME: VALUE",
                    help="header for the RVF request; repeatable. Given any, the "
                         "local X-AUTH scheme is NOT sent - the deployed API is "
                         "behind a gateway that authorises on a Bearer token, and "
                         "sending both would hide which one was accepted.")
    ap.add_argument("--github-repo", help="owner/repo publishing the packs")
    ap.add_argument("--pack", action="append", default=[], metavar="NAME=ASSET",
                    help="which release asset is which pack; repeatable")
    ap.add_argument("--token-env", default="PACK_REGISTRY_TOKEN",
                    help="env var holding a GitHub token, for a private repo")
    ap.add_argument("--release-json",
                    help="a recorded releases/latest payload, instead of the API")
    ap.add_argument("--gate", action="store_true",
                    help="exit 1 on drift, so a scheduled build's failure is the "
                         "notification")
    a = ap.parse_args()

    headers = dict(RVF_AUTH)
    if a.header:
        # Replaced, not merged: the deployed API is behind a gateway that
        # authorises on a Bearer token, and sending the local scheme alongside
        # it would leave which one was accepted unknowable.
        headers = {}
        for header in a.header:
            name, _, value = header.partition(":")
            if not value:
                raise SystemExit(f"FATAL: --header {header!r} is not 'Name: value'")
            headers[name.strip()] = value.strip()

    # Both fetches are reported as "cannot compare", never as a traceback and
    # never as "in sync". A private registry with no token answers 404, and a
    # check that treated an unreachable side as agreement would go green for
    # exactly as long as the token stayed missing.
    try:
        loaded, pending = loaded_packs(a.packs_url, headers)
    except Exception as e:                                        # noqa: BLE001
        print(f"  UNREACHABLE the deployment's {a.packs_url}: "
              f"{type(e).__name__}: {e}")
        print("\n  Nothing was compared. This is not 'in sync'.")
        return 1
    summary = ", ".join(f"{name} {pack['version']}" for name, pack in loaded.items())
    print(f"  loaded: {len(loaded)} pack(s)" + (f" - {summary}" if summary else ""))

    # The server's own answer, not re-derived: it knows what it was configured
    # with and what it loaded, and this tool knows neither.
    for entry in pending:
        print(f"  PENDING     {entry}")

    mappings = parse_mappings(a.pack)
    if not mappings:
        print("  no --pack mappings given, so nothing was compared against the registry")
        return 1 if (a.gate and pending) else 0

    try:
        if a.release_json:
            release = json.loads(pathlib.Path(a.release_json).read_text())
        elif a.github_repo:
            release = latest_release(a.github_repo, os.environ.get(a.token_env))
        else:
            raise SystemExit("FATAL: one of --github-repo or --release-json is required")
    except SystemExit:
        raise
    except Exception as e:                                        # noqa: BLE001
        print(f"  UNREACHABLE the registry {a.github_repo or a.release_json}: "
              f"{type(e).__name__}: {e}")
        print("\n  Nothing was compared. If the repository is private, "
              f"{a.token_env} must hold a token that can read its releases.")
        return 1

    lines, drift = compare(loaded, published_packs(release, mappings))
    for line in lines:
        print(line)

    if a.gate and (drift or pending):
        print("\n  A newer pack is published than the deployment pins, or a pin is "
              "not loaded.\n  Update the values file and POST /assertions/packs/refresh."
              "\n  This job never applies it: a server that follows a channel is "
              "`latest` by another name.")
        return 1
    if not drift and not pending:
        print("  everything the deployment pins is the newest published, and loaded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
