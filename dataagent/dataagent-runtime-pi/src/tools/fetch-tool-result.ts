/**
 * Built-in Agent tool: fetch_tool_result.
 *
 * Allows the agent to retrieve paginated slices or specific columns from
 * a folded large tool result stored in the ResultStore.
 */

import { Type } from "@earendil-works/pi-ai";
import { fetchToolResultSlice, searchToolResult } from "../context/result-store.js";

const FETCH_TOOL_RESULT_SCHEMA = Type.Object({
  result_ref: Type.String({
    description: "The unique result_ref handle returned in the folded summary (e.g. 'res_xxx').",
  }),
  offset: Type.Optional(
    Type.Integer({
      description: "Row offset to start reading from (0-indexed). Defaults to 0.",
      default: 0,
    })
  ),
  limit: Type.Optional(
    Type.Integer({
      description: "Number of rows to fetch (default 20, max 100).",
      default: 20,
    })
  ),
  columns: Type.Optional(
    Type.Array(Type.String(), {
      description: "Optional list of column names to project. If omitted, all columns are returned.",
    })
  ),
});

export function createFetchToolResultTool(workspaceRoot: string): unknown {
  return {
    name: "fetch_tool_result",
    label: "fetch_tool_result",
    description:
      "Retrieve a paginated slice or specific columns from a previously folded large tool result (identified by result_ref).",
    parameters: FETCH_TOOL_RESULT_SCHEMA,
    execute: async (
      _toolCallId: string,
      params: {
        result_ref: string;
        offset?: number;
        limit?: number;
        columns?: string[];
      }
    ) => {
      try {
        const slice = await fetchToolResultSlice(workspaceRoot, params.result_ref, {
          offset: params.offset,
          limit: params.limit,
          columns: params.columns,
        });

        return {
          content: [
            {
              type: "text" as const,
              text: JSON.stringify(slice, null, 2),
            },
          ],
          details: {
            result_ref: params.result_ref,
            is_tabular: slice.is_tabular,
            has_more: slice.has_more,
          },
        };
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          content: [
            {
              type: "text" as const,
              text: `Failed to fetch tool result '${params.result_ref}': ${message}`,
            },
          ],
          details: { error: message },
          isError: true,
        };
      }
    },
  };
}

const SEARCH_TOOL_RESULT_SCHEMA = Type.Object({
  result_ref: Type.String({
    description: "The unique result_ref handle returned in the folded summary (e.g. 'res_xxx').",
  }),
  query: Type.String({
    description: "Case-insensitive substring to look for. Matched against the whole row or line.",
  }),
  limit: Type.Optional(
    Type.Integer({
      description: "Maximum matches to return (default 20, max 100).",
      default: 20,
    })
  ),
});

/**
 * Built-in Agent tool: search_result.
 *
 * Paging helps only when the agent knows roughly where to look. When it does
 * not, re-running the original query has been the cheaper move — one probe turn
 * issued fifteen SQL calls rather than page through what it had already
 * fetched — which pays for a database round trip and reads the answer twice.
 */
export function createSearchToolResultTool(workspaceRoot: string): unknown {
  return {
    name: "search_result",
    label: "search_result",
    description:
      "Find rows or lines containing a substring in a previously folded large tool result " +
      "(identified by result_ref). Use this instead of re-running the query that produced it.",
    parameters: SEARCH_TOOL_RESULT_SCHEMA,
    execute: async (
      _toolCallId: string,
      params: { result_ref: string; query: string; limit?: number }
    ) => {
      try {
        const found = await searchToolResult(workspaceRoot, params.result_ref, params.query, {
          limit: params.limit,
        });

        return {
          content: [{ type: "text" as const, text: JSON.stringify(found, null, 2) }],
          details: {
            result_ref: params.result_ref,
            is_tabular: found.is_tabular,
            count: found.match_count,
            truncated: found.truncated,
          },
        };
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err);
        return {
          content: [
            {
              type: "text" as const,
              text: `Failed to search tool result '${params.result_ref}': ${message}`,
            },
          ],
          details: { error: message },
          isError: true,
        };
      }
    },
  };
}
