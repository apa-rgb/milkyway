#!/usr/bin/env python3
"""Create the ignored iOS REST configuration without logging Firebase keys."""
import argparse
import pathlib
import plistlib
import re
import shutil
from urllib.parse import urlparse

root = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--plist", type=pathlib.Path, help="GoogleService-Info.plist from the Firebase Apple app")
parser.add_argument("--database-url")
parser.add_argument("--plant")
parser.add_argument("--example", action="store_true", help="Build the unconfigured login screen")
args = parser.parse_args()
output = root / "Resources/MilkywayCloud.plist"
if args.example:
    if output.exists():
        raise SystemExit("Configuration already exists; --example will not overwrite it.")
    shutil.copyfile(root / "Resources/MilkywayCloud.example.plist", output)
else:
    if not args.plist or not args.plant:
        parser.error("Provide --plist and --plant (same plant as Android).")
    with args.plist.open("rb") as handle:
        source = plistlib.load(handle)
    if source.get("PROJECT_ID") != "milyway-41e9e" or source.get("BUNDLE_ID") != "pl.apargb.milkyway.ios":
        raise SystemExit("Wrong project or Apple bundle ID in the plist.")
    database = args.database_url or source.get("DATABASE_URL", "")
    parsed = urlparse(database)
    if parsed.scheme != "https" or not parsed.hostname or not parsed.hostname.endswith((".firebaseio.com", ".firebasedatabase.app")) or parsed.query or parsed.fragment or parsed.username:
        raise SystemExit("Provide the existing HTTPS Realtime Database URL with --database-url.")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.plant) or not source.get("API_KEY"):
        raise SystemExit("Invalid plant identifier or missing Firebase key.")
    config = {"PROJECT_ID": source["PROJECT_ID"], "API_KEY": source["API_KEY"], "DATABASE_URL": database.rstrip("/"), "PLANT_ID": args.plant}
    with output.open("wb") as handle:
        plistlib.dump(config, handle)
    output.chmod(0o600)
print("iOS configuration prepared. Credentials were not printed.")
