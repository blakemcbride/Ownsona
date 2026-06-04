/*
 * cmd_dump.c --- `ownsona dump [file]`
 *
 * Write all memories as JSON (the server's export_memories snapshot) to a
 * file or stdout.  Embedding vectors are not included --- they are
 * re-derived from each memory's text on restore.  Pairs with
 * `ownsona restore`.
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona dump [file]\n"
"\n"
"Write all memories as JSON (the export_memories snapshot).  Soft-deleted\n"
"(tombstone) rows are included by default.  Writes to <file> if given,\n"
"otherwise to stdout.  Embedding vectors are not included (they are\n"
"re-derived from text on restore).\n"
"\n"
"  --active-only   exclude soft-deleted rows from the dump\n"
"  -h, --help\n";

int cmd_dump(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "active-only", no_argument, 0, 'a' },
        { "help",        no_argument, 0, 'h' },
        { 0, 0, 0, 0 }
    };
    int include_deleted = 1;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'a': include_deleted = 0; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    const char *outfile = (optind < argc) ? argv[optind++] : NULL;
    if (optind < argc) {
        fprintf(stderr, "ownsona dump: unexpected extra argument '%s'\n%s", argv[optind], USAGE);
        return 2;
    }

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddBoolToObject(args, "include_deleted", include_deleted);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "export_memories", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona dump: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }

    const long count = (long) cJSON_GetNumberValue(
        cJSON_GetObjectItemCaseSensitive(result, "count"));

    char *json = cJSON_Print(result);
    cJSON_Delete(result);
    if (json == NULL) {
        fprintf(stderr, "ownsona dump: could not serialize result\n");
        return 1;
    }

    if (outfile != NULL) {
        FILE *f = fopen(outfile, "w");
        if (f == NULL) {
            fprintf(stderr, "ownsona dump: cannot open '%s' for writing\n", outfile);
            free(json);
            return 1;
        }
        fputs(json, f);
        fputc('\n', f);
        const int werr = ferror(f);
        if (fclose(f) != 0 || werr) {
            fprintf(stderr, "ownsona dump: error writing '%s'\n", outfile);
            free(json);
            return 1;
        }
        fprintf(stderr, "Dumped %ld memories to %s\n", count, outfile);
    } else {
        fputs(json, stdout);
        fputc('\n', stdout);
    }
    free(json);
    return 0;
}
