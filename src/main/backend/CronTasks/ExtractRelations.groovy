package CronTasks

import ai.ownsona.llm.GraphExtractionJob

/**
 * Cron shim for the Tier 4 phase 2 relation-extraction job.  All logic
 * lives in the precompiled Java {@link ai.ownsona.llm.GraphExtractionJob};
 * this file exists only because Kiss Cron runs Groovy files.
 *
 * OFF by default three ways: the crontab line that invokes this file ships
 * commented out, GRAPH_EXTRACTION_ENABLED defaults false, and it no-ops
 * without a generative provider (LLM_API_KEY). When it runs it processes at
 * most GRAPH_EXTRACTION_MAX_MEMORIES memories (one LLM call each) and is a
 * no-op once every memory has been extracted.
 */
class ExtractRelations {

    /** Entry point invoked by Kiss Cron.  obj is a Connection (unused: the
     *  job opens its own connections). */
    static void start(Object obj) {
        GraphExtractionJob.runScheduled()
    }
}
