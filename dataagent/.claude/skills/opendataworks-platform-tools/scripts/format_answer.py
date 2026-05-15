from __future__ import annotations

import argparse

from _opendataworks_runtime import error_payload, load_json_input, print_json


def main():
    parser = argparse.ArgumentParser(description="Summarize SQL execution JSON for the final answer")
    parser.add_argument("--input", default="")
    parser.add_argument("--input-file", default="")
    args = parser.parse_args()

    try:
        payload = load_json_input(raw=str(args.input or "").strip(), file_path=str(args.input_file or "").strip() or None)
        rows = payload.get("rows") if isinstance(payload, dict) else []
        summary = payload.get("summary") if isinstance(payload, dict) else ""
        observations = []
        if isinstance(rows, list) and rows:
            first_row = rows[0]
            if isinstance(first_row, dict):
                preview = "，".join([f"{key}={value}" for key, value in list(first_row.items())[:3]])
                observations.append(f"首行样例：{preview}")
        if payload.get("has_more"):
            observations.append("结果已截断，前端仅展示预览数据。")

        print_json(
            {
                "kind": "python_execution",
                "tool_label": "结果摘要",
                "script": "format_answer.py",
                "summary": summary or "已提取查询结果摘要",
                "stdout": "",
                "result": {"observations": observations},
                "error": None,
            }
        )
    except Exception as exc:
        print_json(error_payload("python_execution", str(exc), tool_label="结果摘要", script="format_answer.py"))


if __name__ == "__main__":
    main()
