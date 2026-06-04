/*
 * cmd_stats.c --- `ownsona stats` --- an overview of the memory store.
 *
 * Combines the server's `memory_stats` aggregates (totals, soft-deleted /
 * expired counts, average importance, date range, top tags, per-source
 * breakdown) with a client-computed `keep` flag breakdown and text-length
 * stats over the active memories (the server's memory_stats tool does not
 * break down by keep).
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona stats\n"
"\n"
"Show an overview of the memory store: totals, keep-flag breakdown,\n"
"text-length stats, importance, date range, top tags, and per-source\n"
"counts.\n"
"\n"
"  -h, --help\n";

static double jnum(cJSON *o, const char *k, double dflt) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(o, k);
    return cJSON_IsNumber(v) ? v->valuedouble : dflt;
}

static const char *jstr(cJSON *o, const char *k) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(o, k);
    return (cJSON_IsString(v) && v->valuestring) ? v->valuestring : NULL;
}

int cmd_stats(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "help", no_argument, 0, 'h' },
        { 0, 0, 0, 0 }
    };
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind < argc) {
        fprintf(stderr, "ownsona stats: unexpected argument '%s'\n%s", argv[optind], USAGE);
        return 2;
    }

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0)
        return 1;

    /* 1. Server-side aggregates. */
    char *err = NULL;
    cJSON *stats = ownsona_mcp_call(&cfg, "memory_stats", cJSON_CreateObject(), &err);
    if (stats == NULL) {
        fprintf(stderr, "ownsona stats: %s\n", err ? err : "(unknown error)");
        free(err);
        ownsona_config_free(&cfg);
        return 1;
    }

    /* 2. Active memories: keep breakdown + text-length stats. */
    ownsona_mem_t *mems = NULL;
    size_t nmems = 0;
    if (ownsona_fetch_active(&cfg, &mems, &nmems, &err) != 0) {
        fprintf(stderr, "ownsona stats: %s\n", err ? err : "(unknown error)");
        free(err);
        cJSON_Delete(stats);
        ownsona_config_free(&cfg);
        return 1;
    }
    ownsona_config_free(&cfg);

    long ky = 0, kn = 0, ku = 0;
    size_t total_len = 0, min_len = 0, max_len = 0;
    int have_len = 0;
    for (size_t i = 0; i < nmems; i++) {
        switch (mems[i].keep) {
            case 'Y': ky++; break;
            case 'N': kn++; break;
            default:  ku++; break;
        }
        const size_t len = strlen(mems[i].text);
        total_len += len;
        if (!have_len || len < min_len)
            min_len = len;
        if (!have_len || len > max_len)
            max_len = len;
        have_len = 1;
    }
    const long kdenom = (ky + kn + ku) > 0 ? (ky + kn + ku) : 1;

    if (gopt->json_output) {
        cJSON *keep = cJSON_AddObjectToObject(stats, "keep");
        cJSON_AddNumberToObject(keep, "Y", (double) ky);
        cJSON_AddNumberToObject(keep, "N", (double) kn);
        cJSON_AddNumberToObject(keep, "U", (double) ku);
        cJSON *tl = cJSON_AddObjectToObject(stats, "text_length");
        cJSON_AddNumberToObject(tl, "count",    (double) nmems);
        cJSON_AddNumberToObject(tl, "total",    (double) total_len);
        cJSON_AddNumberToObject(tl, "average",  nmems ? (double) total_len / (double) nmems : 0);
        cJSON_AddNumberToObject(tl, "shortest", (double) (have_len ? min_len : 0));
        cJSON_AddNumberToObject(tl, "longest",  (double) (have_len ? max_len : 0));
        ownsona_print_json(stats);
        cJSON_Delete(stats);
        ownsona_mems_free(mems, nmems);
        return 0;
    }

    const long active = (long) jnum(stats, "active", (double) nmems);
    const long soft   = (long) jnum(stats, "soft_deleted", 0);
    const long expd   = (long) jnum(stats, "expired", 0);
    const long total  = (long) jnum(stats, "total", (double) (active + soft));

    printf("Memories\n");
    printf("  active         %7ld\n", active);
    printf("  soft-deleted   %7ld   (tombstones; hidden from recall)\n", soft);
    printf("  expired        %7ld\n", expd);
    printf("  total          %7ld\n", total);

    printf("\nKeep flag (active)\n");
    printf("  Y protected    %7ld   (%5.1f%%)\n", ky, 100.0 * (double) ky / (double) kdenom);
    printf("  N not kept     %7ld   (%5.1f%%)\n", kn, 100.0 * (double) kn / (double) kdenom);
    printf("  U unspecified  %7ld   (%5.1f%%)\n", ku, 100.0 * (double) ku / (double) kdenom);

    if (have_len) {
        printf("\nText length (active)\n");
        printf("  average        %7ld chars\n", nmems ? (long) (total_len / nmems) : 0L);
        printf("  shortest       %7ld\n", (long) min_len);
        printf("  longest        %7ld\n", (long) max_len);
        printf("  total          %7ld\n", (long) total_len);
    }

    {
        cJSON *ai = cJSON_GetObjectItemCaseSensitive(stats, "avg_importance");
        const char *oldest = jstr(stats, "oldest_created_at");
        const char *newest = jstr(stats, "newest_created_at");
        if (cJSON_IsNumber(ai) || oldest || newest) {
            printf("\nOther\n");
            if (cJSON_IsNumber(ai))
                printf("  avg importance   %.2f\n", ai->valuedouble);
            if (oldest)
                printf("  oldest           %s\n", oldest);
            if (newest)
                printf("  newest           %s\n", newest);
        }
    }

    {
        cJSON *tags = cJSON_GetObjectItemCaseSensitive(stats, "top_tags");
        if (cJSON_IsArray(tags) && cJSON_GetArraySize(tags) > 0) {
            printf("\nTop tags\n");
            cJSON *t;
            cJSON_ArrayForEach(t, tags) {
                const char *tag = jstr(t, "tag");
                printf("  %-18s %6ld\n", tag ? tag : "(none)", (long) jnum(t, "count", 0));
            }
        }
    }

    {
        cJSON *prov = cJSON_GetObjectItemCaseSensitive(stats, "by_provider");
        if (cJSON_IsArray(prov) && cJSON_GetArraySize(prov) > 0) {
            printf("\nBy source\n");
            cJSON *p;
            cJSON_ArrayForEach(p, prov) {
                const char *name = jstr(p, "provider");
                printf("  %-18s %6ld\n", name ? name : "(none)", (long) jnum(p, "count", 0));
            }
        }
    }

    cJSON_Delete(stats);
    ownsona_mems_free(mems, nmems);
    return 0;
}
