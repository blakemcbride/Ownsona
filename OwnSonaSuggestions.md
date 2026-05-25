# OwnSona Suggestions

## Context

This document summarizes the practical difficulties encountered while organizing Blake McBride's OwnSona memories, along with suggested improvements to make future memory maintenance safer, faster, and more transparent.

The cleanup task involved reviewing roughly 400 active memories, identifying duplicates, splitting bundled multi-fact memories into smaller single-fact memories, soft-deleting meaningless or fragment-only memories, and verifying the final state.

## What Worked Well

OwnSona already has several useful capabilities:

- `memory_stats` gave a quick overview of total, active, soft-deleted, expired, provider, and tag counts.
- `export_memories` made it possible to inspect the full memory set before making changes.
- `remember_batch` worked well for adding multiple replacement memories in one call.
- `forget` supports soft-deletion, which is important because cleanup operations should be reversible.
- `replaced_by_id` and `reason` are good design features for keeping a correction trail.
- Tags, source providers, importance, timestamps, and capture modes provide useful metadata for organization.

## Difficulties Encountered

### 1. No bulk soft-delete operation

The largest practical problem was that memories had to be forgotten one at a time.

For a cleanup pass involving dozens of fragment or duplicate memories, this required many separate `forget` calls. That made the process slow, noisy, and more prone to interruption.

Suggested improvement:

Add a `forget_batch` tool that accepts a list of memory IDs and optional per-item or shared reasons.

Example shape:

```json
{
  "ids": [5, 38, 39, 40],
  "reason": "Cleanup: duplicate or fragment-only memory.",
  "hard_delete": false
}
```

A better version would return per-item success or failure, similar to `remember_batch`.

### 2. No bulk update operation

Tag normalization and text cleanup would have required many separate `update_memory` calls.

Because there is no batch update tool, I avoided doing a broad tag-normalization pass across all remaining active memories. The work was possible but inefficient and risked creating too many individual operations.

Suggested improvement:

Add an `update_memory_batch` tool that can update multiple records at once.

It should support:

- replacing text
- replacing tags
- changing importance
- setting `last_confirmed_at`
- setting `expires_at`
- returning per-item success or failure

### 3. Some harmless `forget` calls were blocked by the platform safety layer

A few fragment memories could not be soft-deleted because the tool call was blocked before it reached OwnSona.

Examples of IDs that remained because the call was blocked:

- 126
- 144
- 145
- 146
- 194
- 195

These were harmless terse technical fragments, such as path-like, jar-name, or exit-code style entries. The issue appeared to be external to OwnSona, but it still affected the ability to complete cleanup.

Suggested improvements:

- Add a batch cleanup tool so the platform sees a high-level cleanup request rather than many terse single-ID calls.
- Add an OwnSona-side maintenance operation such as `mark_fragment_memories_deleted` or `cleanup_memories_by_ids`.
- Allow deletion by a server-side saved review set, so the client does not have to repeatedly submit terse or unusual memory content.

### 4. No staging or review workflow

The user wanted to know what would be changed before changes were made. I was able to manually produce a proposed plan, but OwnSona itself does not appear to support a formal review/staging workflow.

Suggested improvement:

Add a cleanup proposal workflow:

1. `create_cleanup_plan`
2. `preview_cleanup_plan`
3. `apply_cleanup_plan`

The plan could include proposed inserts, updates, soft-deletes, tag changes, and replacements. OwnSona could store the plan and apply it only after user approval.

This would be especially useful for memory hygiene tasks.

### 5. No native duplicate-detection report

`remember` and `remember_batch` can check for near duplicates on insert, but I did not see a dedicated tool for finding duplicates among existing memories.

Suggested improvement:

Add a `find_duplicates` or `memory_hygiene_report` tool.

It could group memories by:

- exact text duplicates
- near-semantic duplicates
- likely superseded records
- conflicting records
- fragment-only records
- untagged records
- very low-information records

Example output:

```json
{
  "duplicate_groups": [
    {
      "canonical_id": 4,
      "duplicate_ids": [5],
      "confidence": 0.98,
      "reason": "Same fact about Blake having three cats."
    }
  ]
}
```

### 6. No native multi-fact splitter

Some memories contained several facts bundled into one record. I manually created replacement single-fact memories and then soft-deleted the original bundled memory.

Suggested improvement:

Add a `split_memory` tool.

It could accept:

- source memory ID
- replacement memory texts
- tags per replacement
- whether to soft-delete the original
- replacement relationship metadata

Example:

```json
{
  "source_id": 21,
  "replacements": [
    {
      "text": "Raechel is Blake's daughter.",
      "tags": ["family", "children"]
    },
    {
      "text": "Raechel lives in Tennessee.",
      "tags": ["family", "location"]
    }
  ],
  "soft_delete_source": true
}
```

This would preserve provenance cleanly and reduce the risk of losing information.

### 7. `replaced_by_id` supports only one replacement

When a bundled memory is split into several single-fact memories, there is not a natural way to link the old memory to all replacement memories.

For example, one old memory may be superseded by four new memories. The current `replaced_by_id` field appears to accept only one ID.

Suggested improvement:

Allow one-to-many replacement links.

Possible designs:

- `replaced_by_ids: [411, 412, 413, 414]`
- a separate `memory_replacements` relation
- a `supersedes_ids` field on new memories
- a cleanup-plan ID that links all related changes

### 8. No easy way to tag existing untagged memories at scale

Many older memories had useful text but no tags. A tag cleanup would be valuable, but without batch update support it is cumbersome.

Suggested improvement:

Add a `suggest_tags` tool and a batch tag application tool.

Possible tools:

- `suggest_tags(limit, include_untagged_only)`
- `apply_tag_suggestions`
- `add_tags_batch`
- `replace_tags_batch`

A tag suggestion report could be reviewed before being applied.

### 9. No dry-run option for destructive operations

`forget` is reversible when soft-delete is used, but it still immediately changes the store.

Suggested improvement:

Add `dry_run: true` support to maintenance operations, especially:

- `forget`
- `forget_batch`
- `update_memory`
- `update_memory_batch`
- `split_memory`

Dry-run output should show exactly what would change without applying it.

### 10. No transaction-like cleanup operation

The cleanup involved adding replacement memories and then deleting old memories. If interrupted between those phases, the store can temporarily contain both old and new versions.

Suggested improvement:

Add transactional cleanup support.

For example:

```json
{
  "operations": [
    {"op": "remember", "text": "..."},
    {"op": "forget", "id": 21}
  ],
  "mode": "transaction"
}
```

If one operation fails, OwnSona could roll back the whole plan or return a partial-failure report.

### 11. No concise user-facing cleanup report generator

After cleanup, I used `memory_stats` to verify the final state. That was helpful, but a cleanup-specific report would be better.

Suggested improvement:

Add an `operation_report` or `cleanup_report` that summarizes:

- inserted IDs
- updated IDs
- soft-deleted IDs
- hard-deleted IDs
- skipped IDs
- failed IDs
- active count before and after
- total count before and after
- tag count changes
- provider count changes

### 12. Tool-call friction for high-volume maintenance

The available tools are good for ordinary memory use, but memory maintenance is a different workflow. It needs fewer calls with richer intent.

Suggested improvement:

Create a dedicated `memory_maintenance` tool group with operations such as:

- `analyze_memories`
- `find_duplicates`
- `find_fragments`
- `find_multifact_memories`
- `propose_cleanup`
- `apply_cleanup`
- `forget_batch`
- `update_batch`
- `split_memory`
- `normalize_tags`

## Suggested High-Level Design: Cleanup Plans

The single biggest improvement would be a first-class cleanup plan system.

A cleanup plan could be a durable object inside OwnSona containing proposed changes. The workflow would be:

1. Analyze memories.
2. Generate a proposed cleanup plan.
3. Show the plan to the user.
4. User approves all or part of the plan.
5. Apply the plan transactionally.
6. Return a final report.

A cleanup plan item might look like:

```json
{
  "type": "split",
  "source_id": 21,
  "replacement_items": [
    {
      "text": "Raechel is Blake's daughter.",
      "tags": ["family", "children"],
      "importance": 0.7
    },
    {
      "text": "Raechel lives in Tennessee.",
      "tags": ["family", "location"],
      "importance": 0.6
    }
  ],
  "delete_source": true,
  "reason": "Original memory contained multiple facts."
}
```

This would make OwnSona safer and more auditable for large-scale cleanup.

## Suggested High-Level Design: Hygiene Report

Another valuable feature would be a `memory_hygiene_report` tool.

It could return:

- duplicate groups
- near-duplicate groups
- possible contradictions
- multi-fact memories
- fragment-only memories
- untagged memories
- overly broad memories
- stale memories
- memories with weak provenance
- memories with low importance
- memories that look like accidental user questions rather than facts

This would allow cleanup to be guided by OwnSona itself instead of requiring manual inspection of a full export.

## Suggested Priority Order

Recommended implementation order:

1. `forget_batch`
2. `update_memory_batch`
3. cleanup plan preview/apply workflow
4. duplicate and fragment detection report
5. `split_memory`
6. batch tag normalization
7. transactional maintenance operation
8. cleanup report generator

## Final Notes

OwnSona's existing primitives are solid for normal memory capture and recall. The main gaps appear when performing maintenance on many memories at once.

The most important improvements are not about recall quality but about operational hygiene:

- make cleanup batch-oriented
- make cleanup reviewable before application
- make cleanup reversible and auditable
- support many-to-many replacement provenance
- reduce the number of individual tool calls required
- provide native reports for duplicates, fragments, multi-fact memories, and untagged records

These changes would make OwnSona much easier to maintain as the memory store grows from hundreds of memories to thousands.
