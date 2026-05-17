/* cmd_confirm.c --- `ownsona confirm <id>` --- MCP tool 'confirm'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static const char USAGE[] =
"usage: ownsona confirm <id>\n"
"\n"
"Mark a memory as still-current by refreshing its last_confirmed_at\n"
"timestamp.  No other field changes; the embedding is not rebuilt.\n";

int cmd_confirm(int argc, char **argv, const ownsona_global_opts_t *gopt) {
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
        fprintf(stderr, "ownsona confirm: missing required <id>\n%s", USAGE);
        return 2;
    }
    const long id = strtol(argv[optind], NULL, 10);
    if (id <= 0) {
        fprintf(stderr, "ownsona confirm: <id> must be a positive integer\n");
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

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "confirm", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona confirm: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
