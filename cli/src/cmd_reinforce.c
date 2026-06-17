/* cmd_reinforce.c --- `ownsona reinforce <id>... [--delta N]` --- MCP tool 'reinforce'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static const char USAGE[] =
"usage: ownsona reinforce <id> [<id>...] [--delta N]\n"
"\n"
"Record feedback on memories so the store learns to rank genuinely\n"
"useful facts higher.  Raises (or, with a negative delta, lowers) each\n"
"memory's learned salience.  Never edits text and never deletes anything;\n"
"protected (keep=Y) memories can still be reinforced.\n"
"\n"
"Options:\n"
"  --delta N    feedback strength in [-1, 1].  Default +1 (helpful);\n"
"               use a negative value (e.g. --delta=-1) for 'unhelpful'.\n"
"  -h, --help\n";

int cmd_reinforce(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "delta", required_argument, 0, 'd' },
        { "help",  no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };

    const char *delta = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'd': delta = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }

    if (optind >= argc) {
        fprintf(stderr, "ownsona reinforce: missing required <id>\n%s", USAGE);
        return 2;
    }

    cJSON *ids = cJSON_CreateArray();
    for (int j = optind; j < argc; j++) {
        char *end = NULL;
        const long id = strtol(argv[j], &end, 10);
        if (end == argv[j] || *end != '\0' || id <= 0) {
            cJSON_Delete(ids);
            fprintf(stderr, "ownsona reinforce: invalid id '%s' "
                            "(ids must be positive integers)\n", argv[j]);
            return 2;
        }
        cJSON_AddItemToArray(ids, cJSON_CreateNumber((double) id));
    }

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0) {
        cJSON_Delete(ids);
        return 1;
    }

    cJSON *args = cJSON_CreateObject();
    cJSON_AddItemToObject(args, "memory_ids", ids);
    if (delta != NULL)
        cJSON_AddNumberToObject(args, "delta", strtod(delta, NULL));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "reinforce", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona reinforce: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
