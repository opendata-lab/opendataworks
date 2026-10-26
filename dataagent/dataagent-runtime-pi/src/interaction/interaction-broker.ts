/**
 * Asks the user through the control plane and waits for the reply.
 *
 * A tool that needs a person (a question, a plan to approve) sends one
 * `interaction.request` frame and parks on a promise; the control plane records
 * the card, waits on its durable store and answers with `interaction.resolved`.
 * Nothing about the wait lives here beyond the pending promise: deadlines and
 * timeout policy belong to the control plane, which owns the record.
 */

export type InteractionKind = "question" | "plan";
export type InteractionResult = Record<string, unknown>;
export type InteractionSend = (payload: Record<string, unknown>) => void;

export class InteractionBroker {
  private readonly pending = new Map<string, (result: InteractionResult) => void>();

  constructor(private readonly send: InteractionSend) {}

  public request(
    kind: InteractionKind,
    requestId: string,
    payload: Record<string, unknown>,
    signal?: AbortSignal
  ): Promise<InteractionResult> {
    return new Promise((resolve, reject) => {
      if (signal?.aborted) {
        reject(new Error("aborted"));
        return;
      }
      const onAbort = () => {
        this.pending.delete(requestId);
        reject(new Error("aborted"));
      };
      signal?.addEventListener("abort", onAbort, { once: true });
      this.pending.set(requestId, (result) => {
        signal?.removeEventListener("abort", onAbort);
        resolve(result);
      });
      this.send({ request_id: requestId, kind, payload });
    });
  }

  /** Deliver a reply; a reply nobody waits for (cancelled, duplicated) is dropped. */
  public resolve(requestId: string, result: InteractionResult): boolean {
    const done = this.pending.get(requestId);
    if (!done) {
      return false;
    }
    this.pending.delete(requestId);
    done(result);
    return true;
  }
}
