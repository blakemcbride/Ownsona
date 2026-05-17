/* cmd_prompt.c --- `ownsona prompt "<user prompt>"` --- MCP tool 'build_context_prompt'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>

static const char USAGE[] =
"usage: ownsona prompt \"<user prompt>\" [options]\n"
"\n"
"Build a single LLM-ready prompt that includes relevant remembered\n"
"facts above the user's original prompt.  Output is the composed\n"
"prompt itself --- pipe it into your favorite LLM.\n"
"\n"
"Options:\n"
"  --limit N         max facts to include (default 8)\n"
"  --max-chars N     character budget on the included facts\n"
"                    (rough proxy for tokens; ~4 chars/token)\n"
"  -h, --help\n";

int cmd_prompt(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "limit",     required_argument, 0, 'l' },
        { "max-chars", required_argument, 0, 'm' },
        { "help",      no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *limit = NULL;
    const char *max_chars = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'l': limit     = optarg; break;
            case 'm': max_chars = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona prompt: missing required <user prompt>\n%s", USAGE);
        return 2;
    }
    const char *user_prompt = argv[optind];

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddStringToObject(args, "user_prompt", user_prompt);
    if (limit     != NULL) cJSON_AddNumberToObject(args, "limit",     strtol(limit,     NULL, 10));
    if (max_chars != NULL) cJSON_AddNumberToObject(args, "max_chars", strtol(max_chars, NULL, 10));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "build_context_prompt", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona prompt: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
