/**
 * The UI's copy of a tool result, kept apart from the model's.
 *
 * Folding exists to keep the model's context small. Until now it also decided
 * what the transcript stored, because both read the same object: a folded
 * result reached tool_execution_end as a digest, so a chart or table larger than
 * the fold threshold was gone from history for good. Exempting structured
 * outputs from folding bought that back only by paying their full token cost on
 * every turn.
 *
 * Separating the two lets a result be small for the model and whole for replay.
 * The registry holds the UI copy between afterToolCall — where the full result
 * is still available — and the normalizer, which persists it.
 *
 * Scoped to one run. Entries are consumed on read and the rest are dropped when
 * the run settles, so a long conversation cannot accumulate tool results in
 * memory.
 */

export interface UiToolResult {
  content: unknown;
  meta: Record<string, unknown> | null;
}

export class UiToolResultRegistry {
  private readonly entries = new Map<string, UiToolResult>();
  private missCount = 0;

  /** Number of lookups that found nothing; a rising count means a wiring bug. */
  public get misses(): number {
    return this.missCount;
  }

  public get size(): number {
    return this.entries.size;
  }

  public set(toolCallId: string, result: UiToolResult): void {
    const key = String(toolCallId || "").trim();
    if (!key) {
      // Without an id the normalizer could never find it again; holding it
      // would only leak.
      return;
    }
    this.entries.set(key, result);
  }

  /**
   * Take the UI copy for a tool call, if one was registered.
   *
   * Consuming on read keeps the map bounded and makes a double-persist visible
   * as a miss rather than silently succeeding twice.
   */
  public take(toolCallId: string): UiToolResult | null {
    const key = String(toolCallId || "").trim();
    if (!key || !this.entries.has(key)) {
      this.missCount += 1;
      return null;
    }
    const value = this.entries.get(key) ?? null;
    this.entries.delete(key);
    return value;
  }

  /** Drop everything. Called when a run settles, however it settles. */
  public clear(): void {
    this.entries.clear();
  }
}
