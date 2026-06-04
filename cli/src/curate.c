/*
 * curate.c --- shared helpers for the curation subcommands
 * (display, change, delete, enumerate).
 *
 *   - ownsona_fetch_active(): pull every active memory via export_memories.
 *   - ownsona_parse_ids():    expand an id-selector string ("5-9,12,$").
 *   - ownsona_ensure_trailing_period(): tidy text before storing.
 *
 * These commands are deliberately client-driven: the server has no
 * "give me ids 5-9" tool, so we fetch the whole (single-user) store once
 * and filter locally.  Cheap at OwnSona's scale and keeps the MCP surface
 * small.
 */
#include "ownsona.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ----- fetch ---------------------------------------------------------- */

static int cmp_mem_by_id(const void *a, const void *b) {
    const long ia = ((const ownsona_mem_t *) a)->id;
    const long ib = ((const ownsona_mem_t *) b)->id;
    if (ia < ib) return -1;
    if (ia > ib) return 1;
    return 0;
}

void ownsona_mems_free(ownsona_mem_t *mems, size_t count) {
    if (mems == NULL)
        return;
    for (size_t i = 0; i < count; i++)
        free(mems[i].text);
    free(mems);
}

int ownsona_fetch_active(const ownsona_config_t *cfg,
                         ownsona_mem_t **out, size_t *out_count, char **err) {
    *out = NULL;
    *out_count = 0;
    if (err != NULL)
        *err = NULL;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddBoolToObject(args, "include_deleted", 0);

    char *call_err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "export_memories", args, &call_err);
    if (result == NULL) {
        if (err != NULL)
            *err = call_err ? call_err : strdup("export_memories failed");
        else
            free(call_err);
        return 1;
    }

    cJSON *memories = cJSON_GetObjectItemCaseSensitive(result, "memories");
    if (!cJSON_IsArray(memories)) {
        cJSON_Delete(result);
        if (err != NULL)
            *err = strdup("export_memories returned no memories array");
        return 1;
    }

    const int n = cJSON_GetArraySize(memories);
    ownsona_mem_t *arr = (n > 0) ? calloc((size_t) n, sizeof *arr) : NULL;
    if (n > 0 && arr == NULL)
        ownsona_die(2, "out of memory");

    size_t count = 0;
    cJSON *m;
    cJSON_ArrayForEach(m, memories) {
        if (!cJSON_IsObject(m))
            continue;
        cJSON *id   = cJSON_GetObjectItemCaseSensitive(m, "id");
        cJSON *text = cJSON_GetObjectItemCaseSensitive(m, "text");
        cJSON *keep = cJSON_GetObjectItemCaseSensitive(m, "keep");
        if (!cJSON_IsNumber(id))
            continue;
        arr[count].id   = (long) id->valuedouble;
        arr[count].keep = (cJSON_IsString(keep) && keep->valuestring && keep->valuestring[0])
                          ? keep->valuestring[0] : 'U';
        arr[count].text = strdup((cJSON_IsString(text) && text->valuestring)
                                 ? text->valuestring : "");
        if (arr[count].text == NULL)
            ownsona_die(2, "out of memory");
        count++;
    }
    cJSON_Delete(result);

    if (count > 1)
        qsort(arr, count, sizeof *arr, cmp_mem_by_id);

    *out = arr;
    *out_count = count;
    return 0;
}

/* ----- id selectors --------------------------------------------------- */

static int known_contains(const long *known, size_t n, long id) {
    for (size_t i = 0; i < n; i++)
        if (known[i] == id)
            return 1;
    return 0;
}

static long known_max(const long *known, size_t n) {
    long mx = 0;
    for (size_t i = 0; i < n; i++)
        if (known[i] > mx)
            mx = known[i];
    return mx;
}

/* Parse a non-negative integer or '$' (-> max_id).  Returns 0 on success,
 * writing the value to *out; -1 on a malformed token. */
static int parse_token(const char *tok, long max_id, long *out) {
    while (*tok == ' ' || *tok == '\t')
        tok++;
    if (tok[0] == '$' && (tok[1] == '\0')) {
        *out = max_id;
        return 0;
    }
    if (!isdigit((unsigned char) tok[0]))
        return -1;
    char *end = NULL;
    long v = strtol(tok, &end, 10);
    while (end != NULL && (*end == ' ' || *end == '\t'))
        end++;
    if (end == NULL || *end != '\0' || v < 0)
        return -1;
    *out = v;
    return 0;
}

long *ownsona_parse_ids(const char *spec,
                        const long *known_ids, size_t known_count,
                        size_t *out_count, char **err) {
    *out_count = 0;
    if (err != NULL)
        *err = NULL;

    /* Empty/NULL spec -> all known ids (already sorted by the caller). */
    if (spec == NULL || *spec == '\0') {
        if (known_count == 0)
            return NULL;
        long *all = malloc(known_count * sizeof *all);
        if (all == NULL)
            ownsona_die(2, "out of memory");
        memcpy(all, known_ids, known_count * sizeof *all);
        *out_count = known_count;
        return all;
    }

    const long max_id = known_max(known_ids, known_count);

    /* Worst case every known id is selected; size to that. */
    long *sel = malloc((known_count ? known_count : 1) * sizeof *sel);
    if (sel == NULL)
        ownsona_die(2, "out of memory");
    size_t nsel = 0;

    char *copy = strdup(spec);
    if (copy == NULL)
        ownsona_die(2, "out of memory");

    int failed = 0;
    char *save = NULL;
    for (char *part = strtok_r(copy, ",", &save);
         part != NULL && !failed;
         part = strtok_r(NULL, ",", &save)) {
        /* Trim. */
        while (*part == ' ' || *part == '\t')
            part++;
        if (*part == '\0')
            continue;

        char *dash = strchr(part, '-');
        long lo, hi;
        if (dash != NULL) {
            *dash = '\0';
            if (parse_token(part, max_id, &lo) != 0
                || parse_token(dash + 1, max_id, &hi) != 0) {
                failed = 1;
                break;
            }
            if (lo > hi) {
                const long t = lo; lo = hi; hi = t;
            }
        } else {
            if (parse_token(part, max_id, &lo) != 0) {
                failed = 1;
                break;
            }
            hi = lo;
        }
        /* Keep only existing ids; skip dups. */
        for (long id = lo; id <= hi; id++) {
            if (!known_contains(known_ids, known_count, id))
                continue;
            if (!known_contains(sel, nsel, id))
                sel[nsel++] = id;
        }
    }
    free(copy);

    if (failed) {
        free(sel);
        if (err != NULL) {
            char buf[256];
            snprintf(buf, sizeof buf,
                     "invalid id selector: \"%s\" (use ids like 5, 5-9, 5,7,9, or 20-$)",
                     spec);
            *err = strdup(buf);
        }
        return NULL;
    }

    /* Sort ascending for stable, id-ordered output. */
    if (nsel > 1) {
        /* simple insertion sort over the small selection */
        for (size_t i = 1; i < nsel; i++) {
            const long key = sel[i];
            size_t j = i;
            while (j > 0 && sel[j - 1] > key) {
                sel[j] = sel[j - 1];
                j--;
            }
            sel[j] = key;
        }
    }
    *out_count = nsel;
    return sel;
}

/* ----- text utility --------------------------------------------------- */

char *ownsona_ensure_trailing_period(const char *in) {
    if (in == NULL)
        in = "";
    size_t len = strlen(in);
    /* Find last non-space char. */
    size_t end = len;
    while (end > 0 && isspace((unsigned char) in[end - 1]))
        end--;
    const int has_period = (end > 0 && in[end - 1] == '.');
    char *out = malloc(len + 2);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    memcpy(out, in, len);
    if (has_period || end == 0) {
        out[len] = '\0';
    } else {
        /* Append the period right after the last non-space char, dropping
         * any trailing whitespace so "foo  " becomes "foo." not "foo  .". */
        out[end] = '.';
        out[end + 1] = '\0';
    }
    return out;
}
