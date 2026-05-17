/* cmd_list.c --- `ownsona list` --- MCP tool 'list_memories'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona list [options]\n"
"\n"
"List memories most-recent first.  By default only active rows are\n"
"shown; --include-deleted also returns soft-deleted tombstones and\n"
"expired rows for diagnostics.\n"
"\n"
"Options:\n"
"  --limit N           max rows (default 20)\n"
"  --offset N          skip the first N (for paging, default 0)\n"
"  --include-deleted   also return soft-deleted / expired rows\n"
"  -h, --help\n";

int cmd_list(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "limit",           required_argument, 0, 'l' },
        { "offset",          required_argument, 0, 'o' },
        { "include-deleted", no_argument,       0, 'd' },
        { "help",            no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *limit = NULL;
    const char *offset = NULL;
    int include_deleted = 0;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'l': limit           = optarg; break;
            case 'o': offset          = optarg; break;
            case 'd': include_deleted = 1;      break;
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
    if (limit  != NULL) cJSON_AddNumberToObject(args, "limit",  strtol(limit,  NULL, 10));
    if (offset != NULL) cJSON_AddNumberToObject(args, "offset", strtol(offset, NULL, 10));
    if (include_deleted) cJSON_AddBoolToObject(args, "include_deleted", 1);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "list_memories", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona list: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
