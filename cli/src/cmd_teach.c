/*
 * cmd_teach.c --- `ownsona teach FILE [options]`
 *
 * Reads a long-form text (e.g. an autobiography), uses an LLM to
 * extract durable third-person facts about the subject, then submits
 * them as a batch of memories via remember_batch (which the server
 * embeds and indexes as usual).
 *
 * Default behavior is dry-run: print the extracted JSON to stdout (or
 * --output FILE) so the user can eyeball before committing.  Pass
 * --commit to actually insert.  Pass --yes to skip the confirmation
 * prompt that --commit otherwise shows.
 */
#include "ownsona.h"

#include <ctype.h>
#include <errno.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static const char USAGE[] =
"usage: ownsona teach FILE [options]\n"
"\n"
"Extract durable facts from FILE using an LLM and submit them as\n"
"memories.  Default is dry-run --- pass --commit to actually insert.\n"
"\n"
"Options:\n"
"  --subject NAME       how to refer to the subject in extracted facts\n"
"                       (default from config; falls back to 'the user')\n"
"  --model NAME         override config's llm_model for this run\n"
"  --chunk-size N       characters per LLM call (default 4000)\n"
"  --tags T1,T2         apply these tags to every extracted fact\n"
"  --dedup POLICY       dedup_policy for remember_batch\n"
"                       ('insert'|'skip_if_near'|'ask'; default 'skip_if_near')\n"
"  --max-facts N        sanity cap on total facts (default 10000)\n"
"  --commit             actually insert the extracted facts\n"
"                       (default is dry-run)\n"
"  --yes                with --commit, skip the confirmation prompt\n"
"  --output FILE        in dry-run, write JSON to FILE instead of stdout\n"
"  -h, --help\n";

/* ----- file slurp + chunking ----------------------------------------- */

static char *slurp(const char *path, size_t *out_len) {
    FILE *fp = fopen(path, "rb");
    if (fp == NULL) {
        fprintf(stderr, "ownsona teach: cannot open '%s': %s\n", path, strerror(errno));
        return NULL;
    }
    struct stat st;
    if (fstat(fileno(fp), &st) != 0) {
        fclose(fp);
        return NULL;
    }
    char *buf = malloc((size_t) st.st_size + 1);
    if (buf == NULL) {
        fclose(fp);
        ownsona_die(2, "out of memory");
    }
    const size_t got = fread(buf, 1, (size_t) st.st_size, fp);
    fclose(fp);
    buf[got] = '\0';
    if (out_len) *out_len = got;
    return buf;
}

/*
 * Split src into chunks of up to chunk_size chars, breaking at the
 * nearest paragraph boundary (\n\n) preceding the limit when possible,
 * else at the nearest sentence end, else at a hard cut.  Returns a
 * NULL-terminated array of malloc'd strings; caller frees each + the
 * array.
 */
static char **chunk_text(const char *src, size_t chunk_size, size_t *out_n) {
    *out_n = 0;
    if (src == NULL)
        return NULL;
    size_t total = strlen(src);
    if (total == 0) {
        char **arr = calloc(1, sizeof(char *));
        return arr;
    }
    /* Generous upper bound: total/chunk_size + 2 */
    size_t cap = (total / chunk_size) + 4;
    char **arr = calloc(cap, sizeof(char *));
    if (arr == NULL) ownsona_die(2, "out of memory");

    size_t i = 0;
    while (i < total) {
        size_t want_end = i + chunk_size;
        if (want_end >= total) want_end = total;
        size_t cut = want_end;
        if (cut < total) {
            /* Prefer the latest "\n\n" at or before want_end. */
            for (size_t j = want_end; j > i + chunk_size / 2; j--) {
                if (j + 1 < total && src[j] == '\n' && src[j-1] == '\n') {
                    cut = j + 1;
                    goto have_cut;
                }
            }
            /* Else try sentence end: '. ' or '! ' or '? ' */
            for (size_t j = want_end; j > i + chunk_size / 2; j--) {
                const char c = src[j];
                if ((c == '.' || c == '!' || c == '?')
                    && j + 1 < total
                    && (src[j+1] == ' ' || src[j+1] == '\n')) {
                    cut = j + 2;
                    goto have_cut;
                }
            }
            /* Hard cut at want_end. */
        }
    have_cut: {}
        if (cut < i) cut = i + 1;
        size_t len = cut - i;
        char *s = malloc(len + 1);
        if (s == NULL) ownsona_die(2, "out of memory");
        memcpy(s, src + i, len);
        s[len] = '\0';
        /* skip leading whitespace within the chunk for tidiness */
        char *p = s;
        while (*p == '\n' || *p == ' ' || *p == '\t' || *p == '\r')
            p++;
        if (p != s)
            memmove(s, p, strlen(p) + 1);
        if (*s != '\0') {
            if (*out_n + 1 >= cap) {
                cap *= 2;
                char **n = realloc(arr, cap * sizeof(char *));
                if (n == NULL) ownsona_die(2, "out of memory");
                arr = n;
            }
            arr[(*out_n)++] = s;
        } else {
            free(s);
        }
        i = cut;
    }
    arr[*out_n] = NULL;
    return arr;
}

/* ----- prompt assembly ----------------------------------------------- */

static char *build_system_prompt(const char *subject) {
    static const char tmpl[] =
        "You extract durable personal facts from a chunk of %s's autobiography.\n"
        "\n"
        "Return a JSON OBJECT with exactly one key, \"facts\", whose value is\n"
        "an array of objects.  Each object has:\n"
        "  \"text\": a single self-contained statement of fact, written in\n"
        "           the third person.  Refer to the subject as \"%s\".\n"
        "           Do not use pronouns whose antecedent is outside this\n"
        "           statement (replace 'he', 'she', 'they', 'we', etc.\n"
        "           with concrete names where possible).\n"
        "  \"tags\": 1-3 short canonical tags chosen from: family, work,\n"
        "           education, personal, places, software, publishing,\n"
        "           health, philosophy, preferences, military, religion,\n"
        "           pets, hobbies, history.  Add other tags only if none\n"
        "           of these fit.\n"
        "\n"
        "Rules:\n"
        "- One discrete fact per object.  Do NOT combine unrelated facts.\n"
        "- Skip scenery, mood, transitions, dialogue, and anecdotes that\n"
        "  do not reduce to a durable fact (events, relationships, places\n"
        "  lived/worked, education, experiences, beliefs, preferences,\n"
        "  family members, jobs, achievements, dates).\n"
        "- Skip speculation, dreams, hypotheticals, and uncertain claims.\n"
        "- Do not invent or extrapolate.  If the chunk contains no usable\n"
        "  facts, return {\"facts\": []}.\n"
        "- Phrase past events in past tense; phrase ongoing states in\n"
        "  present tense.\n"
        "- Output only the JSON object, with no markdown or commentary.\n";
    size_t need = sizeof(tmpl) + strlen(subject) * 2;
    char *out = malloc(need);
    if (out == NULL) ownsona_die(2, "out of memory");
    snprintf(out, need, tmpl, subject, subject);
    return out;
}

/* ----- main subcommand ----------------------------------------------- */

static cJSON *split_tags(const char *csv) {
    cJSON *arr = cJSON_CreateArray();
    if (csv == NULL) return arr;
    char *copy = strdup(csv);
    if (copy == NULL) ownsona_die(2, "out of memory");
    char *save = NULL;
    char *tok = strtok_r(copy, ",", &save);
    while (tok != NULL) {
        while (*tok == ' ' || *tok == '\t') tok++;
        size_t n = strlen(tok);
        while (n > 0 && (tok[n-1] == ' ' || tok[n-1] == '\t')) tok[--n] = '\0';
        if (n > 0) cJSON_AddItemToArray(arr, cJSON_CreateString(tok));
        tok = strtok_r(NULL, ",", &save);
    }
    free(copy);
    return arr;
}

/* Apply extra tags from --tags to every fact (merging, dedup). */
static void merge_tags(cJSON *fact, cJSON *extra) {
    if (extra == NULL || cJSON_GetArraySize(extra) == 0)
        return;
    cJSON *existing = cJSON_GetObjectItemCaseSensitive(fact, "tags");
    if (!cJSON_IsArray(existing)) {
        cJSON_DeleteItemFromObjectCaseSensitive(fact, "tags");
        cJSON_AddItemToObject(fact, "tags", cJSON_Duplicate(extra, 1));
        return;
    }
    cJSON *t;
    cJSON_ArrayForEach(t, extra) {
        if (!cJSON_IsString(t)) continue;
        bool present = false;
        cJSON *e;
        cJSON_ArrayForEach(e, existing) {
            if (cJSON_IsString(e) && e->valuestring != NULL
                && strcmp(e->valuestring, t->valuestring) == 0) {
                present = true;
                break;
            }
        }
        if (!present)
            cJSON_AddItemToArray(existing, cJSON_CreateString(t->valuestring));
    }
}

static int submit_batch(const ownsona_config_t *cfg,
                        cJSON *facts_slice,   /* takes ownership */
                        const char *dedup_policy) {
    cJSON *args = cJSON_CreateObject();
    cJSON_AddItemToObject(args, "items", facts_slice);
    cJSON_AddStringToObject(args, "source_provider", "ownsona-cli/teach");
    if (dedup_policy != NULL && *dedup_policy != '\0')
        cJSON_AddStringToObject(args, "dedup_policy", dedup_policy);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "remember_batch", args, &err);
    if (result == NULL) {
        fprintf(stderr, "ownsona teach: remember_batch failed: %s\n",
                err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    /* Reuse the existing human formatter --- it knows the batch shape. */
    ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}

int cmd_teach(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "subject",    required_argument, 0, 's' },
        { "model",      required_argument, 0, 'm' },
        { "chunk-size", required_argument, 0, 'C' },
        { "tags",       required_argument, 0, 't' },
        { "dedup",      required_argument, 0, 'D' },
        { "max-facts",  required_argument, 0, 'M' },
        { "commit",     no_argument,       0, 'k' },
        { "yes",        no_argument,       0, 'y' },
        { "output",     required_argument, 0, 'o' },
        { "help",       no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *subject = NULL;
    const char *model_override = NULL;
    size_t      chunk_size = 4000;
    const char *tags_csv = NULL;
    const char *dedup_policy = "skip_if_near";
    long        max_facts = 10000;
    bool        commit = false;
    bool        confirmed = false;
    const char *output_path = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 's': subject        = optarg; break;
            case 'm': model_override = optarg; break;
            case 'C': chunk_size     = (size_t) strtoul(optarg, NULL, 10); break;
            case 't': tags_csv       = optarg; break;
            case 'D': dedup_policy   = optarg; break;
            case 'M': max_facts      = strtol(optarg, NULL, 10); break;
            case 'k': commit         = true;   break;
            case 'y': confirmed      = true;   break;
            case 'o': output_path    = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona teach: missing required FILE\n%s", USAGE);
        return 2;
    }
    if (chunk_size < 500 || chunk_size > 32000) {
        fprintf(stderr, "ownsona teach: --chunk-size must be 500..32000\n");
        return 2;
    }
    const char *path = argv[optind];

    /* Load config */
    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;
    /* Subject precedence: --subject flag > config > "the user" default. */
    if (subject != NULL && *subject != '\0') {
        free(cfg.subject_name);
        cfg.subject_name = strdup(subject);
    }
    /* LLM key is required by this subcommand specifically. */
    if (cfg.llm_api_key == NULL || *cfg.llm_api_key == '\0') {
        fprintf(stderr, "ownsona teach: llm_api_key not set "
                "(use llm_api_key in config or $OWNSONA_LLM_API_KEY)\n");
        ownsona_config_free(&cfg);
        return 1;
    }

    /* Read + chunk the input */
    size_t src_len = 0;
    char *src = slurp(path, &src_len);
    if (src == NULL) { ownsona_config_free(&cfg); return 1; }
    size_t n_chunks = 0;
    char **chunks = chunk_text(src, chunk_size, &n_chunks);
    free(src);
    if (chunks == NULL || n_chunks == 0) {
        fprintf(stderr, "ownsona teach: nothing usable in '%s'\n", path);
        ownsona_config_free(&cfg);
        return 1;
    }

    fprintf(stderr,
            "ownsona teach: %zu chunk%s of up to %zu chars; model=%s subject=%s\n",
            n_chunks, n_chunks == 1 ? "" : "s",
            chunk_size,
            (model_override && *model_override) ? model_override : cfg.llm_model,
            cfg.subject_name);

    /* Build the system prompt once. */
    char *system_prompt = build_system_prompt(cfg.subject_name);
    cJSON *extra_tags   = (tags_csv != NULL) ? split_tags(tags_csv) : NULL;

    /* Collect facts across all chunks. */
    cJSON *all_facts = cJSON_CreateArray();
    long total = 0;
    long chunk_failures = 0;
    bool hit_cap = false;
    for (size_t ci = 0; ci < n_chunks; ci++) {
        fprintf(stderr, "  chunk %zu/%zu (%zu chars) ... ",
                ci + 1, n_chunks, strlen(chunks[ci]));
        fflush(stderr);
        char *err = NULL;
        cJSON *resp = ownsona_llm_chat(&cfg, model_override,
                                       system_prompt, chunks[ci], &err);
        if (resp == NULL) {
            fprintf(stderr, "FAILED: %s\n", err ? err : "(unknown)");
            free(err);
            chunk_failures++;
            continue;
        }
        /* Expected shape: {"facts": [...]} */
        cJSON *facts = cJSON_GetObjectItemCaseSensitive(resp, "facts");
        if (!cJSON_IsArray(facts)) {
            /* Some models return the bare array despite the prompt;
             * accept either shape. */
            if (cJSON_IsArray(resp)) facts = resp;
        }
        if (!cJSON_IsArray(facts)) {
            fprintf(stderr, "no 'facts' array in response; skipped\n");
            cJSON_Delete(resp);
            chunk_failures++;
            continue;
        }
        int added = 0;
        cJSON *f;
        cJSON_ArrayForEach(f, facts) {
            if (!cJSON_IsObject(f)) continue;
            cJSON *text = cJSON_GetObjectItemCaseSensitive(f, "text");
            if (!cJSON_IsString(text) || text->valuestring == NULL
                || *text->valuestring == '\0')
                continue;
            cJSON *dup = cJSON_Duplicate(f, 1);
            merge_tags(dup, extra_tags);
            cJSON_AddItemToArray(all_facts, dup);
            added++;
            total++;
            if (total >= max_facts) { hit_cap = true; break; }
        }
        fprintf(stderr, "+%d (total %ld)\n", added, total);
        cJSON_Delete(resp);
        if (hit_cap) {
            fprintf(stderr, "ownsona teach: reached --max-facts=%ld; stopping\n",
                    max_facts);
            break;
        }
    }
    for (size_t i = 0; i < n_chunks; i++) free(chunks[i]);
    free(chunks);
    free(system_prompt);
    cJSON_Delete(extra_tags);

    fprintf(stderr,
            "ownsona teach: extracted %ld fact%s (%ld chunk%s failed)\n",
            total, total == 1 ? "" : "s",
            chunk_failures, chunk_failures == 1 ? "" : "s");

    if (total == 0) {
        fprintf(stderr, "ownsona teach: nothing to do.\n");
        cJSON_Delete(all_facts);
        ownsona_config_free(&cfg);
        return chunk_failures > 0 ? 1 : 0;
    }

    /* Dry-run path: emit JSON to stdout or --output. */
    if (!commit) {
        FILE *fp = stdout;
        if (output_path != NULL) {
            fp = fopen(output_path, "w");
            if (fp == NULL) {
                fprintf(stderr, "ownsona teach: cannot write '%s': %s\n",
                        output_path, strerror(errno));
                cJSON_Delete(all_facts);
                ownsona_config_free(&cfg);
                return 1;
            }
        }
        char *s = cJSON_Print(all_facts);
        if (s != NULL) {
            fputs(s, fp);
            fputc('\n', fp);
            free(s);
        }
        if (fp != stdout) {
            fclose(fp);
            fprintf(stderr, "ownsona teach: dry-run; wrote %ld fact%s to %s\n",
                    total, total == 1 ? "" : "s", output_path);
        } else {
            fprintf(stderr, "ownsona teach: dry-run; pass --commit to insert\n");
        }
        cJSON_Delete(all_facts);
        ownsona_config_free(&cfg);
        return 0;
    }

    /* Commit path: optionally confirm, then submit in 200-at-a-time
     * batches via remember_batch. */
    if (!confirmed) {
        fprintf(stderr, "About to insert %ld memories with dedup_policy=%s. "
                        "Proceed? [y/N] ", total, dedup_policy);
        int ch = getchar();
        if (ch != 'y' && ch != 'Y') {
            fprintf(stderr, "ownsona teach: aborted.\n");
            cJSON_Delete(all_facts);
            ownsona_config_free(&cfg);
            return 1;
        }
    }

    int rc = 0;
    const int batch_size = 200;
    const int n = cJSON_GetArraySize(all_facts);
    for (int off = 0; off < n; off += batch_size) {
        cJSON *slice = cJSON_CreateArray();
        const int end = (off + batch_size > n) ? n : off + batch_size;
        for (int j = off; j < end; j++) {
            cJSON *item = cJSON_GetArrayItem(all_facts, j);
            cJSON_AddItemToArray(slice, cJSON_Duplicate(item, 1));
        }
        fprintf(stderr, "ownsona teach: submitting batch %d-%d / %d\n",
                off + 1, end, n);
        if (submit_batch(&cfg, slice, dedup_policy) != 0) {
            rc = 1;
            break;
        }
    }

    cJSON_Delete(all_facts);
    ownsona_config_free(&cfg);
    return rc;
}
