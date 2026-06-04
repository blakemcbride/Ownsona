/*
 * cmd_change.c --- `ownsona change <id> <new-text>`
 *
 * Replace a memory's text.  Adds a trailing period if missing, stamps
 * source_provider=ownsona / source_client=cli, and lets the server
 * recompute normalized_text.  Rejected by the server when the memory is
 * protected (keep=Y).
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona change <id> <new-text>\n"
"\n"
"Replace the text of memory <id>.  A trailing period is added if absent.\n"
"Fails if the memory is protected (keep=Y).\n"
"\n"
"  -h, --help\n";

int cmd_change(int argc, char **argv, const ownsona_global_opts_t *gopt) {
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

    if (optind + 2 != argc) {
        fprintf(stderr, "ownsona change: expected <id> and <new-text>\n%s", USAGE);
        return 2;
    }

    char *end = NULL;
    const long id = strtol(argv[optind], &end, 10);
    if (end == NULL || *end != '\0' || id < 0) {
        fprintf(stderr, "ownsona change: invalid id '%s'\n", argv[optind]);
        return 2;
    }
    const char *new_text = argv[optind + 1];
    char *text = ownsona_ensure_trailing_period(new_text);

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0) {
        free(text);
        return 1;
    }

    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    cJSON_AddStringToObject(args, "text", text);
    cJSON_AddStringToObject(args, "source_provider", "ownsona");
    cJSON_AddStringToObject(args, "source_client", "cli");
    free(text);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "update_memory", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona change: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }

    if (gopt->json_output)
        ownsona_print_json(result);
    else
        printf("Changed memory %ld\n", id);
    cJSON_Delete(result);
    return 0;
}
