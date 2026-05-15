from __future__ import annotations

import argparse

from _opendataworks_runtime import error_payload, print_json, resolve_datasource


def main():
    parser = argparse.ArgumentParser(description="Resolve datasource metadata for a target database")
    parser.add_argument("--database", required=True)
    parser.add_argument("--engine", default="")
    args = parser.parse_args()

    database = str(args.database or "").strip()
    preferred_engine = str(args.engine or "").strip().lower() or None

    try:
        resolved = resolve_datasource(database, preferred_engine=preferred_engine)
        print_json(
            {
                "kind": "datasource_resolution",
                "database": resolved["database"],
                "engine": resolved["engine"],
                "cluster_id": resolved["cluster_id"],
                "cluster_name": resolved["cluster_name"],
                "source_type": resolved["source_type"],
                "resolved_by": resolved["resolved_by"],
                "summary": f"已定位 `{resolved['database']}` 的 {resolved['engine']} 数据源",
                "error": None,
            }
        )
    except Exception as exc:
        print_json(
            error_payload(
                "datasource_resolution",
                str(exc),
                database=database,
                engine=preferred_engine,
            )
        )


if __name__ == "__main__":
    main()
