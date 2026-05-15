from __future__ import annotations

import argparse

from _opendataworks_runtime import call_metadata_cli, error_payload, print_json

def main():
    parser = argparse.ArgumentParser(description="Inspect OpenDataWorks metadata for a database/table")
    parser.add_argument("--database", default="")
    parser.add_argument("--table", default="")
    parser.add_argument("--keyword", default="")
    parser.add_argument("--table-limit", type=int, default=12)
    args = parser.parse_args()

    database = str(args.database or "").strip()
    table_name = str(args.table or "").strip()
    keyword = str(args.keyword or "").strip()

    try:
        payload = call_metadata_cli(
            "inspect",
            database=database,
            table=table_name,
            keyword=keyword,
            table_limit=args.table_limit,
        )
        print_json(payload)
    except Exception as exc:
        print_json(
            error_payload(
                "metadata_snapshot",
                str(exc),
                database=database or None,
                table=table_name or None,
                keyword=keyword or None,
            )
        )
    return


if __name__ == "__main__":
    main()
