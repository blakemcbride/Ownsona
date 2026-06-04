/*
 * cmd_delete.c --- `ownsona delete <ids>`
 *
 * Hard-delete the selected memories.  ids accepts the same selector
 * syntax as display (5, 5-9, 5,7,9, 20-$).  Protected (keep=Y) memories
 * are reported as skipped, not deleted.
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona delete <ids>\n"
"\n"
"Hard-delete the selected memories (irreversible).\n"
"\n"
"  ids          id (5), range (5-9), list (5,7,9), or combination\n"
"               (5-9,12,20-$).  '$' means the last id.\n"
"  -h, --help\n";

/* Hard-delete one id.  Returns 0 on success; on failure prints a reason
 * and returns non-zero. */
static int delete_one(const ownsona_config_t *cfg, long id) {
    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    cJSON_AddBoolToObject(args, "hard_delete", 1);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "forget", args, &err);
    if (result == NULL) {
        printf("  [%ld] skipped: %s\n", id, err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    cJSON_Delete(result);
    printf("  [%ld] deleted\n", id);
    return 0;
}

int cmd_delete(int argc, char **argv, const ownsona_global_opts_t *gopt) {
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

    if (optind >= argc) {
        fprintf(stderr, "ownsona delete: missing required <ids> argument\n%s", USAGE);
        return 2;
    }
    const char *ids_spec = argv[optind++];
    if (optind < argc) {
        fprintf(stderr, "ownsona delete: unexpected extra argument '%s'\n%s", argv[optind], USAGE);
        return 2;
    }

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0)
        return 1;

    ownsona_mem_t *mems = NULL;
    size_t nmems = 0;
    char *err = NULL;
    if (ownsona_fetch_active(&cfg, &mems, &nmems, &err) != 0) {
        fprintf(stderr, "ownsona delete: %s\n", err ? err : "(unknown error)");
        free(err);
        ownsona_config_free(&cfg);
        return 1;
    }

    long *known = malloc((nmems ? nmems : 1) * sizeof *known);
    if (known == NULL)
        ownsona_die(2, "out of memory");
    for (size_t i = 0; i < nmems; i++)
        known[i] = mems[i].id;

    size_t nsel = 0;
    long *sel = ownsona_parse_ids(ids_spec, known, nmems, &nsel, &err);
    free(known);
    ownsona_mems_free(mems, nmems);
    if (sel == NULL && err != NULL) {
        fprintf(stderr, "ownsona delete: %s\n", err);
        free(err);
        ownsona_config_free(&cfg);
        return 2;
    }
    if (nsel == 0) {
        printf("(no matching memories)\n");
        free(sel);
        ownsona_config_free(&cfg);
        return 0;
    }

    int failures = 0;
    for (size_t i = 0; i < nsel; i++)
        if (delete_one(&cfg, sel[i]) != 0)
            failures++;

    free(sel);
    ownsona_config_free(&cfg);
    printf("deleted %zu, skipped %d\n", nsel - (size_t) failures, failures);
    return failures == 0 ? 0 : 1;
}
