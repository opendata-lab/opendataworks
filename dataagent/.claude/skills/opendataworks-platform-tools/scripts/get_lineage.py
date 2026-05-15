from __future__ import annotations

import argparse

from _opendataworks_runtime import error_payload, get_lineage, print_json


def main():
    parser = argparse.ArgumentParser(description="Get lineage snapshot through the backend metadata path")
    parser.add_argument("--table", default="")
    parser.add_argument("--db-name", default="")
    parser.add_argument("--table-id", type=int, default=None)
    parser.add_argument("--depth", type=int, default=None)
    args = parser.parse_args()

    table = str(args.table or "").strip() or None
    db_name = str(args.db_name or "").strip() or None
    table_id = args.table_id
    depth = args.depth
    if table_id is None and not table:
        parser.error("--table 或 --table-id 至少提供一个")

    try:
        payload = get_lineage(table=table, db_name=db_name, table_id=table_id, depth=depth)
        print_json(payload)
    except Exception as exc:
        print_json(
            error_payload(
                "lineage_snapshot",
                str(exc),
                table=table,
                db_name=db_name,
                table_id=table_id,
                depth=depth,
                lineage=[],
            )
        )


if __name__ == "__main__":
    main()
