/* cmd_relations.c --- `ownsona relations <entity> [options]` --- MCP tool 'query_relations'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static const char USAGE[] =
"usage: ownsona relations \"<entity>\" [options]\n"
"\n"
"Traverse the relationship graph built from your memories (multi-hop):\n"
"follow relations outward from an entity to answer connected questions.\n"
"The graph is populated by the background extraction job; if this returns\n"
"nothing, relation extraction may not be enabled yet.\n"
"\n"
"Options:\n"
"  --max-hops N   hops to follow outward.  Default 2, hard cap 5.\n"
"  -h, --help\n";

int cmd_relations(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "max-hops", required_argument, 0, 'm' },
        { "help",     no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };

    const char *max_hops = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'm': max_hops = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona relations: missing required <entity>\n%s", USAGE);
        return 2;
    }
    const char *entity = argv[optind];

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddStringToObject(args, "entity", entity);
    if (max_hops != NULL)
        cJSON_AddNumberToObject(args, "max_hops", strtol(max_hops, NULL, 10));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "query_relations", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona relations: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
