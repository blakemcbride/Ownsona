/* cmd_update.c --- `ownsona update <id> "<new text>"` --- MCP tool 'update_memory'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona update <id> \"<new text>\" [options]\n"
"\n"
"Replace an existing memory's text (and optionally tags, importance,\n"
"or freshness fields).  The embedding is regenerated.\n"
"\n"
"Options:\n"
"  --tags T1,T2,...        replacement tags (omit to leave unchanged)\n"
"  --importance N          new importance 0..1\n"
"  --expires-at TS         ISO 8601 (omit to leave unchanged)\n"
"  --last-confirmed-at TS  ISO 8601 (omit to leave unchanged; use\n"
"                          'confirm' to just bump to now)\n"
"  -h, --help\n";

static cJSON *split_tags(const char *csv) {
    cJSON *arr = cJSON_CreateArray();
    if (csv == NULL) return arr;
    char *copy = strdup(csv);
    if (copy == NULL) ownsona_die(2, "out of memory");
    char *save = NULL;
    char *tok = strtok_r(copy, ",", &save);
    while (tok != NULL) {
        while (*tok == ' ' || *tok == '\t') tok++;
        size_t n = strlen(tok);
        while (n > 0 && (tok[n-1] == ' ' || tok[n-1] == '\t')) tok[--n] = '\0';
        if (n > 0) cJSON_AddItemToArray(arr, cJSON_CreateString(tok));
        tok = strtok_r(NULL, ",", &save);
    }
    free(copy);
    return arr;
}

int cmd_update(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "tags",              required_argument, 0, 't' },
        { "importance",        required_argument, 0, 'i' },
        { "expires-at",        required_argument, 0, 'E' },
        { "last-confirmed-at", required_argument, 0, 'L' },
        { "help",              no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *tags_csv = NULL, *imp = NULL, *expires_at = NULL, *confirmed = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 't': tags_csv   = optarg; break;
            case 'i': imp        = optarg; break;
            case 'E': expires_at = optarg; break;
            case 'L': confirmed  = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }

    if (optind + 1 >= argc) {
        fprintf(stderr, "ownsona update: need both <id> and \"<new text>\"\n%s", USAGE);
        return 2;
    }
    const long  id   = strtol(argv[optind], NULL, 10);
    const char *text = argv[optind + 1];
    if (id <= 0) {
        fprintf(stderr, "ownsona update: <id> must be a positive integer\n");
        return 2;
    }

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id",   (double) id);
    cJSON_AddStringToObject(args, "text", text);
    if (tags_csv  != NULL) cJSON_AddItemToObject  (args, "tags",              split_tags(tags_csv));
    if (imp       != NULL) cJSON_AddNumberToObject(args, "importance",        strtod(imp, NULL));
    if (expires_at!= NULL) cJSON_AddStringToObject(args, "expires_at",        expires_at);
    if (confirmed != NULL) cJSON_AddStringToObject(args, "last_confirmed_at", confirmed);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "update_memory", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona update: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
