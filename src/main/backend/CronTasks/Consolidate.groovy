package CronTasks

import ai.ownsona.llm.ConsolidationJob

/**
 * Cron shim for the Tier 3 memory consolidation ("sleep") job.  All the
 * logic lives in the precompiled Java {@link ai.ownsona.llm.ConsolidationJob};
 * this file only exists because Kiss Cron runs Groovy files.
 *
 * This job is OFF by default in three independent ways, any one of which
 * keeps it (and any LLM spend) from running:
 *   1. The crontab line that invokes this file ships commented out.
 *   2. CONSOLIDATION_ENABLED defaults to false in application.ini.
 *   3. It no-ops unless a generative provider (LLM_API_KEY) is configured.
 *
 * When it does run it makes at most CONSOLIDATION_MAX_GROUPS (default 25)
 * small LLM calls, and zero calls when there are no near-duplicate
 * clusters to merge.
 */
class Consolidate {

    /** Entry point invoked by Kiss Cron.  obj is a Connection (unused: the
     *  job opens its own connections through the service layer). */
    static void start(Object obj) {
        ConsolidationJob.runScheduled()
    }
}
