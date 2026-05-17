/* cmd_forget.c --- `ownsona forget <id> [--hard] [--reason ...] [--replaced-by ...]` */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static const char USAGE[] =
"usage: ownsona forget <id> [options]\n"
"\n"
"Delete a memory.  Soft delete by default (the row stays as a\n"
"tombstone; future dedup checks will flag attempts to re-add the\n"
"same fact).\n"
"\n"
"Options:\n"
"  --hard                 hard delete (drops the row entirely)\n"
"  --reason \"text\"        why you're forgetting this (stored on the\n"
"                         tombstone; rejected with --hard)\n"
"  --replaced-by <id>     the new memory that supersedes this one\n"
"                         (stored on the tombstone; rejected with --hard)\n"
"  -h, --help\n";

int cmd_forget(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "hard",        no_argument,       0, 'H' },
        { "reason",      required_argument, 0, 'r' },
        { "replaced-by", required_argument, 0, 'R' },
        { "help",        no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    int hard = 0;
    const char *reason = NULL;
    const char *replaced_by = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'H': hard        = 1;      break;
            case 'r': reason      = optarg; break;
            case 'R': replaced_by = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona forget: missing required <id>\n%s", USAGE);
        return 2;
    }
    const long id = strtol(argv[optind], NULL, 10);
    if (id <= 0) {
        fprintf(stderr, "ownsona forget: <id> must be a positive integer\n");
        return 2;
    }

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    if (hard)                    cJSON_AddBoolToObject  (args, "hard_delete",   1);
    if (reason      != NULL)     cJSON_AddStringToObject(args, "reason",        reason);
    if (replaced_by != NULL)     cJSON_AddNumberToObject(args, "replaced_by_id",
                                                         (double) strtol(replaced_by, NULL, 10));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "forget", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona forget: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
