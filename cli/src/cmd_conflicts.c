/* cmd_conflicts.c --- `ownsona conflicts [options]` --- MCP tool 'find_conflicts'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static const char USAGE[] =
"usage: ownsona conflicts [options]    (alias: find-conflicts)\n"
"\n"
"Surface memories that may contradict each other: clusters of active\n"
"memories that are semantically close AND share at least one tag.  Only\n"
"flags candidates --- it does not decide which is correct.  Read-only.\n"
"\n"
"To resolve a conflict, confirm the right memory, or store the corrected\n"
"fact with 'add'/'remember' (on a client that passes supersedes/downweights).\n"
"\n"
"Options:\n"
"  --threshold N    cosine cutoff in [0.5, 1.0].  Default 0.80.\n"
"  --max-groups N   maximum groups to return.  Default 50, hard cap 500.\n"
"  -h, --help\n";

int cmd_conflicts(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "threshold",  required_argument, 0, 't' },
        { "max-groups", required_argument, 0, 'g' },
        { "help",       no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };

    const char *threshold  = NULL;
    const char *max_groups = NULL;

    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 't': threshold  = optarg; break;
            case 'g': max_groups = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    if (threshold != NULL)
        cJSON_AddNumberToObject(args, "threshold", strtod(threshold, NULL));
    if (max_groups != NULL)
        cJSON_AddNumberToObject(args, "max_groups", strtol(max_groups, NULL, 10));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "find_conflicts", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona conflicts: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
