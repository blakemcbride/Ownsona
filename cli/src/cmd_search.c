/* cmd_search.c --- `ownsona search "<substring>"` --- MCP tool 'text_search'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona search \"<substring>\" [options]\n"
"\n"
"Plain (case-insensitive) substring search over memory text.\n"
"\n"
"Options:\n"
"  --limit N         max matches (default 20)\n"
"  -h, --help\n";

int cmd_search(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "limit", required_argument, 0, 'l' },
        { "help",  no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *limit = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'l': limit = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona search: missing required <substring>\n%s", USAGE);
        return 2;
    }
    const char *needle = argv[optind];

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddStringToObject(args, "text", needle);
    if (limit != NULL)
        cJSON_AddNumberToObject(args, "limit", strtol(limit, NULL, 10));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "text_search", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona search: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
