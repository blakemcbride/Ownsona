# OwnSona Enhancement Suggestions

Suggestions are ranked by expected leverage. Each entry states **what**, **why**, and a sketch of **how**.

## 1. Server-side auto-tagging on write

**What.** When `remember` / `remember_batch` is called, OwnSona classifies the text and applies tags itself rather than trusting the client.

**Why.** Tag consistency currently depends on whichever LLM and prompt happen to be calling. Different Claude Code sessions, the claude.ai web client, and any future client will tag differently — or not at all (memory ID 11 has no tags). Untagged memories still surface in semantic `recall`, but tag-filtered queries silently miss them.

**How.** Either an LLM classification call against a controlled vocabulary, or an embedding-nearest-tag lookup over existing memories. Keep client-supplied tags as hints, union them with auto-tags, then normalize.

## 2. Dedup / merge suggestions on write

**What.** When a new memory is semantically near an existing one (above some threshold), return the candidates to the client and let it choose: insert, update existing, or merge.

**Why.** There are already two name memories (`Blake McBride was born on June 8, 1959.` and `blake's name is Blake McBride.`) — overlap that could have been one record. Over time, drift like this fragments recall.

**How.** Run the embedding for the incoming text, fetch top-K with `min_score` ~0.9, return them in the `remember` response. Client decides. Or add a `dedup_policy: "ask" | "merge" | "insert"` parameter.

## 3. Controlled tag vocabulary + normalization

**What.** Maintain a canonical tag list (`family`, `software`, `preferences`, `health`, `publishing`, …) and normalize incoming tags to it (`tech` → `software`, `bio` → `personal`).

**Why.** Without governance, tag sprawl is inevitable across clients and time. Filtering by tag becomes unreliable, which compounds problem #1.

**How.** Synonym map + reject-or-rewrite on write. Periodic audit endpoint that lists low-frequency tags so you can fold them.

## 4. Conflict detection on recall

**What.** When `recall` returns multiple memories that contradict each other (e.g., two different "preferred editor" facts), flag the conflict in the response.

**Why.** Right now both stale and current facts come back with equal weight; the client has to notice the contradiction. Many won't, and will pick whichever ranks higher.

**How.** Group near-duplicate hits, compare timestamps and importance, and return a `conflicts: [...]` field. Cheap version: just include `created_at` / `updated_at` prominently and let the client reason. Better version: detect contradictions during indexing.

## 5. Freshness / staleness signals

**What.** Add optional `expires_at` or `last_confirmed_at` to memories, and let `recall` decay scores for old, unconfirmed entries.

**Why.** Some facts are durable ("Blake's birthday"), others rot fast ("currently working on Rsnap-backup"). Today both are treated the same. A "current project" memory from six months ago is misleading.

**How.** Optional fields on `remember`. `recall` applies a half-life decay to non-durable memories. A new `confirm` op refreshes `last_confirmed_at` without a full update.

## 6. Correction / tombstone records

**What.** When the user says "no, that's wrong" and you call `forget`, leave a small negative record: "previously stored X, corrected to Y on date Z."

**Why.** Without this, a different client tomorrow can re-infer the same wrong fact from the same evidence. The correction history is the most valuable signal.

**How.** `forget` gains a `reason` and optional `replaced_by` field. Tombstones are excluded from normal `recall` but consulted before `remember` writes a near-match.

## 7. Provenance metadata

**What.** Record alongside each memory: which client/session/conversation produced it, and whether the user *explicitly asked* to save vs. the model inferring it was save-worthy.

**Why.** "User-stated" facts deserve more trust than "model-inferred" facts. Today they're indistinguishable, so a confidently-wrong inference outranks a quiet user correction.

**How.** `source_provider` already exists. Add `capture_mode: "explicit" | "inferred"` and optional `session_id`. Surface these in `recall` so the client can weight.

## 8. Structured fields for common fact types

**What.** Beyond free-text, allow optional structured payloads for well-known fact types (name, birthday, email, address, preferred X).

**Why.** Free-text + semantic search works, but it's lossy for facts that have a clear shape. Asking "when is Blake's birthday" should hit a `birthdate: 1959-06-08` field, not depend on the phrasing of a sentence.

**How.** Add a `kind` and `payload` to the memory record. Keep the text representation for backwards compatibility and human readability. `recall` can short-circuit to structured lookups when the query matches a known kind.

## 9. `build_context_prompt` token budgets

**What.** If `build_context_prompt` produces a preamble for the next conversation, give it a hard `max_tokens` and a relevance threshold.

**Why.** Context windows are finite. A growing memory store will, without a budget, eventually dominate the prompt with low-value facts.

**How.** Parameter on the call. Server picks the top-N by recency × importance × similarity until the budget is hit.

## 10. Periodic compaction / review

**What.** A scheduled or on-demand pass that:
- Folds near-duplicates (uses #2 logic)
- Promotes frequently-recalled memories' `importance`
- Demotes never-recalled memories
- Surfaces a "review queue" of low-confidence or stale entries

**Why.** Memory quality compounds. Without periodic cleanup, the store grows monotonically and signal-to-noise drops.

**How.** A `compact` admin op. Read-recall counters already imply infrastructure for the importance adjustments.

---

## What I'd build first

If picking one: **#2 (dedup-on-write)**, because it prevents the fragmentation that makes every other feature harder. **#1 (auto-tagging)** is a close second and pairs naturally with it — both run inside the same write path.
