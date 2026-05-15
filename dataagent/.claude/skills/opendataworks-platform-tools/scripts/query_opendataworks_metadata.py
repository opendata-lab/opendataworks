from __future__ import annotations

import argparse
import json

from _opendataworks_runtime import call_metadata_cli


def main():
    parser = argparse.ArgumentParser(description="Query OpenDataWorks metadata tables")
    parser.add_argument("--kind", choices=["tables", "lineage", "datasource"], required=True)
    parser.add_argument("--database", default="")
    args = parser.parse_args()

    payload = call_metadata_cli(
        "export",
        kind=args.kind,
        database=str(args.database or "").strip(),
    )
    print(json.dumps(payload, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
