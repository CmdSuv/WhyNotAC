#!/usr/bin/env python3
"""
WhyNotAC PROD-14 Protocol Table Diff Tool.

Compares two WhyNotAC world-table directories (containing block-states.txt.gz,
entity-sizes.tsv, item-prototypes.tsv, menu-layouts.tsv) and reports:
  - Added / removed / changed entries per table
  - Impacted whynotac-world, whynotac-physics, and whynotac-analysis classes
    that must be re-verified against the target Minecraft release before
    registering the protocol in SupportedProtocols.

Usage:
  python3 tools/upgrade/diff_protocol_tables.py <old-dir> <new-dir> [--json]
  python3 tools/upgrade/diff_protocol_tables.py --self-test
"""
import gzip
import json
import os
import sys
import tempfile
from pathlib import Path

TABLES = {
    "block-states.txt.gz": {
        "gzip": True,
        "modules": [
            "whynotac-world: BlockStates, BlockShapes, ClientWorld",
            "whynotac-physics: Colliders, OnFoot, Boat, Horse, Minecart",
            "whynotac-analysis: MovementCheck, UseAimCheck, BreakTimeCheck",
            "whynotac-conformance: ClientWorldPlacementConformanceTest, CollisionConformanceTest",
        ],
    },
    "entity-sizes.tsv": {
        "gzip": False,
        "modules": [
            "whynotac-world: EntitySizes, ClientEntities",
            "whynotac-analysis: AttackReachCheck, AttackAimCheck, InteractAtBoxCheck",
        ],
    },
    "item-prototypes.tsv": {
        "gzip": False,
        "modules": [
            "whynotac-world: ItemPrototypes, ClientInventory",
            "whynotac-analysis: AttackReachCheck, BreakTimeCheck, UseSlowdownCheck, UseItemStateCheck",
        ],
    },
    "menu-layouts.tsv": {
        "gzip": False,
        "modules": [
            "whynotac-world: MenuLayouts, ClientInventory",
            "whynotac-analysis: InventoryProtocolCheck",
        ],
    },
}


def load_table(path: Path, is_gzip: bool) -> dict[str, str]:
    if not path.is_file():
        return {}
    opener = gzip.open if is_gzip else open
    entries: dict[str, str] = {}
    with opener(path, "rt", encoding="utf-8") as fh:
        for idx, raw in enumerate(fh):
            line = raw.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t", 1)
            key = parts[0] if len(parts) > 1 else f"line:{idx}"
            val = parts[1] if len(parts) > 1 else line
            entries[key] = val
    return entries


def diff_tables(old_dir: Path, new_dir: Path) -> dict:
    report = {
        "oldDir": str(old_dir),
        "newDir": str(new_dir),
        "tables": {},
        "impactedModules": [],
        "hasChanges": False,
    }
    impacted_set: list[str] = []
    for filename, meta in TABLES.items():
        old_entries = load_table(old_dir / filename, meta["gzip"])
        new_entries = load_table(new_dir / filename, meta["gzip"])
        old_keys = set(old_entries.keys())
        new_keys = set(new_entries.keys())
        added = sorted(new_keys - old_keys)
        removed = sorted(old_keys - new_keys)
        modified = sorted(k for k in (old_keys & new_keys) if old_entries[k] != new_entries[k])
        changed = bool(added or removed or modified)
        if changed:
            report["hasChanges"] = True
            for mod in meta["modules"]:
                if mod not in impacted_set:
                    impacted_set.append(mod)
        report["tables"][filename] = {
            "oldCount": len(old_entries),
            "newCount": len(new_entries),
            "addedCount": len(added),
            "removedCount": len(removed),
            "modifiedCount": len(modified),
            "sampleAdded": added[:10],
            "sampleRemoved": removed[:10],
            "sampleModified": modified[:10],
        }
    report["impactedModules"] = impacted_set
    return report


def run_self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="wnac-diff-test-") as td:
        old_d = Path(td) / "old"
        new_d = Path(td) / "new"
        old_d.mkdir()
        new_d.mkdir()
        with gzip.open(old_d / "block-states.txt.gz", "wt", encoding="utf-8") as fh:
            fh.write("0\tminecraft:air\n1\tminecraft:stone\n")
        with gzip.open(new_d / "block-states.txt.gz", "wt", encoding="utf-8") as fh:
            fh.write("0\tminecraft:air\n1\tminecraft:stone\n2\tminecraft:copper_lantern[hanging=false]\n")
        (old_d / "entity-sizes.tsv").write_text("minecraft:camel\t1.7\t2.375\n", encoding="utf-8")
        (new_d / "entity-sizes.tsv").write_text("minecraft:camel\t1.7\t2.400\n", encoding="utf-8")
        (old_d / "item-prototypes.tsv").write_text("minecraft:mace\t1\t0\n", encoding="utf-8")
        (new_d / "item-prototypes.tsv").write_text("minecraft:mace\t1\t0\n", encoding="utf-8")
        (old_d / "menu-layouts.tsv").write_text("minecraft:generic_9x3\t27\n", encoding="utf-8")
        (new_d / "menu-layouts.tsv").write_text("minecraft:generic_9x3\t27\n", encoding="utf-8")

        res = diff_tables(old_d, new_d)
        assert res["hasChanges"] is True
        assert res["tables"]["block-states.txt.gz"]["addedCount"] == 1
        assert res["tables"]["entity-sizes.tsv"]["modifiedCount"] == 1
        assert res["tables"]["item-prototypes.tsv"]["modifiedCount"] == 0
        assert any("MovementCheck" in m for m in res["impactedModules"])
        assert any("AttackReachCheck" in m for m in res["impactedModules"])
        assert not any("InventoryProtocolCheck" in m for m in res["impactedModules"])
    print("DIFF_PROTOCOL_TABLES_SELF_TEST: OK")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "--self-test":
        return run_self_test()
    as_json = "--json" in argv
    positional = [a for a in argv[1:] if a != "--json"]
    if len(positional) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    old_dir, new_dir = Path(positional[0]), Path(positional[1])
    res = diff_tables(old_dir, new_dir)
    if as_json:
        print(json.dumps(res, indent=2))
    else:
        print(f"WhyNotAC Protocol Table Diff: {old_dir} -> {new_dir}")
        for name, info in res["tables"].items():
            print(
                f"  {name:22s}: old={info['oldCount']} new={info['newCount']} "
                f"+{info['addedCount']} -{info['removedCount']} ~{info['modifiedCount']}"
            )
        if res["impactedModules"]:
            print("Impacted modules requiring conformance re-verification:")
            for mod in res["impactedModules"]:
                print(f"  - {mod}")
        else:
            print("No table differences detected.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
