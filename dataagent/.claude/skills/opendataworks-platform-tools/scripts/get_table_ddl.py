from __future__ import annotations

import argparse

from _opendataworks_runtime import error_payload, get_table_ddl, print_json


def main():
    parser = argparse.ArgumentParser(description="Get live table DDL through the backend metadata path")
    parser.add_argument("--database", default="")
    parser.add_argument("--table", default="")
    parser.add_argument("--table-id", type=int, default=None)
    args = parser.parse_args()

    database = str(args.database or "").strip() or None
    table = str(args.table or "").strip() or None
    table_id = args.table_id
    if table_id is None and (not database or not table):
        parser.error("--table-id 或 --database + --table 至少提供一组")

    try:
        payload = get_table_ddl(database=database, table=table, table_id=table_id)
        print_json(payload)
    except Exception as exc:
        print_json(
            error_payload(
                "table_ddl",
                str(exc),
                database=database,
                table_name=table,
                table_id=table_id,
                ddl=None,
                fields=[],
            )
        )


if __name__ == "__main__":
    main()
