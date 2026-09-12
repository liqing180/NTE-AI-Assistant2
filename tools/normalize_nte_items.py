#!/usr/bin/env python3
"""Normalize NTE exported BidKing collection DataTable to the app's compact JSON format.

Usage:
  python tools/normalize_nte_items.py DT_BidKingCollectionItemConfig.json data/collectibles.json

The source file is intentionally not vendored here. Keep the game-data snapshot versioned
separately so app logic remains independent from game updates.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

TYPE_MAP = {
    "antique": "ANTIQUE",
    "jewel": "JEWEL",
    "technology": "TECHNOLOGY",
    "food": "FOOD",
    "commodity": "COMMODITY",
    "superpower": "SUPERPOWER",
}

QUALITY_PREFIX = "EItemQuality::ITEM_QUALITY_"


def normalize(source: Path) -> list[dict]:
    raw = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(raw, list) or not raw:
        raise ValueError("Unexpected DataTable root")
    rows = raw[0].get("Rows") or {}
    items: list[dict] = []

    for item_id, row in rows.items():
        if row.get("bHidden") is True:
            continue
        quality_raw = str(row.get("ItemQuality", ""))
        quality = quality_raw.removeprefix(QUALITY_PREFIX)
        type_id = str(row.get("TypeID", ""))
        type_name = TYPE_MAP.get(type_id)
        if not quality or not type_name:
            continue

        name_obj = row.get("CollectionName") or {}
        silhouette = (row.get("SilhouetteIcon") or {}).get("AssetPathName") or ""
        items.append(
            {
                "id": item_id,
                "name": name_obj.get("SourceString") or item_id,
                "quality": quality,
                "type": type_name,
                "width": int(row.get("Cols") or 0),
                "height": int(row.get("Rows") or 0),
                "price": int(row.get("Price") or 0),
                "silhouetteId": silhouette.rsplit("/", 1)[-1].split(".", 1)[0] or item_id,
                "canRotate": bool(row.get("bCanRotate", False)),
                "tier": int(row.get("Tier") or 0),
                "effectTypeWeight": float(row.get("EffectTypeWeight") or 1.0),
            }
        )

    items.sort(key=lambda x: (x["quality"], -x["price"], x["id"]))
    return items


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: normalize_nte_items.py <source.json> <output.json>")
    source = Path(sys.argv[1])
    output = Path(sys.argv[2])
    output.parent.mkdir(parents=True, exist_ok=True)
    items = normalize(source)
    payload = {
        "schemaVersion": 1,
        "source": source.name,
        "count": len(items),
        "items": items,
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {len(items)} collectibles -> {output}")


if __name__ == "__main__":
    main()
