#!/usr/bin/env python3
"""
Downloads the official, unmodified Minecraft 1.21.11 client and the libraries it needs on Linux into
a cache directory. Every file is checked against the SHA-1 that Mojang's version manifest publishes
for it; a mismatch aborts. Prints the classpath (one line) and writes launch.json next to it.

usage: fetch_client.py <cache-dir>

Asset objects are fetched except sounds and translations (about 435 MB of 452): the client needs the
rest (icons, fonts, built-in resource packs), en_us is inside the jar, and the container has no audio
device. Each object is named by its own SHA-1, which is verified.
"""
import hashlib
import json
import os
import sys
import urllib.request

VERSION = "1.21.11"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"


def fetch(url, path, sha1=None):
    if os.path.exists(path) and (sha1 is None or digest(path) == sha1):
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with urllib.request.urlopen(url, timeout=120) as response, open(tmp, "wb") as out:
        while chunk := response.read(1 << 16):
            out.write(chunk)
    if sha1 is not None and digest(tmp) != sha1:
        os.remove(tmp)
        raise SystemExit(f"SHA-1 mismatch for {url}")
    os.replace(tmp, path)
    return path


def digest(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def allowed(rules):
    """Mojang library rules, evaluated for Linux x86_64."""
    if not rules:
        return True
    result = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if os_rule and os_rule.get("name") not in (None, "linux"):
            continue
        if "arch" in os_rule and os_rule["arch"] not in ("x86_64", "amd64"):
            continue
        result = rule["action"] == "allow"
    return result


def main():
    cache = os.path.abspath(sys.argv[1])
    os.makedirs(cache, exist_ok=True)
    manifest = json.load(urllib.request.urlopen(MANIFEST, timeout=60))
    entry = next(v for v in manifest["versions"] if v["id"] == VERSION)
    version_path = fetch(entry["url"], os.path.join(cache, f"{VERSION}.json"), entry["sha1"])
    version = json.load(open(version_path))

    client = version["downloads"]["client"]
    jars = [fetch(client["url"], os.path.join(cache, "client", f"{VERSION}.jar"), client["sha1"])]
    # Mojang's official mappings for this exact jar: the ground-truth agent resolves obfuscated names with them.
    mappings = version["downloads"]["client_mappings"]
    mappings_path = fetch(mappings["url"], os.path.join(cache, "client", f"{VERSION}-client.txt"), mappings["sha1"])
    for lib in version["libraries"]:
        if not allowed(lib.get("rules")):
            continue
        artifact = lib.get("downloads", {}).get("artifact")
        if artifact:
            jars.append(fetch(artifact["url"], os.path.join(cache, "libraries", artifact["path"]), artifact["sha1"]))

    index = version["assetIndex"]
    index_path = fetch(index["url"], os.path.join(cache, "assets", "indexes", f"{index['id']}.json"), index["sha1"])
    for name, obj in json.load(open(index_path))["objects"].items():
        if name.startswith("minecraft/sounds/") or name.startswith("minecraft/lang/"):
            continue
        h = obj["hash"]
        fetch(f"https://resources.download.minecraft.net/{h[:2]}/{h}", os.path.join(cache, "assets", "objects", h[:2], h), h)
    launch = {
        "mainClass": version["mainClass"],
        "classpath": jars,
        "assetsDir": os.path.join(cache, "assets"),
        "assetIndex": index["id"],
        "javaMajor": version.get("javaVersion", {}).get("majorVersion"),
        "mappings": mappings_path,
    }
    json.dump(launch, open(os.path.join(cache, "launch.json"), "w"), indent=1)
    print(os.pathsep.join(jars))


if __name__ == "__main__":
    main()
