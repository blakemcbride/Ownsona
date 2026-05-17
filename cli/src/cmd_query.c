/* cmd_query.c --- `ownsona query "<question>"` --- MCP tool 'recall'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona query \"<question>\" [options]\n"
"\n"
"Find memories semantically related to the question.\n"
"\n"
"Options:\n"
"  --limit N          max matches (default 8)\n"
"  --min-score N      only return matches with similarity >= N\n"
"  --tags T1,T2       restrict to memories with any of these tags\n"
"  -h, --help\n";

static cJSON *split_csv_array(const char *csv) {
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

int cmd_query(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "limit",     required_argument, 0, 'l' },
        { "min-score", required_argument, 0, 's' },
        { "tags",      required_argument, 0, 't' },
        { "help",      no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };

    const char *limit = NULL;
    const char *min_score = NULL;
    const char *tags_csv = NULL;

    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'l': limit     = optarg; break;
            case 's': min_score = optarg; break;
            case 't': tags_csv  = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }

    if (optind >= argc) {
        fprintf(stderr, "ownsona query: missing required <question>\n%s", USAGE);
        return 2;
    }
    const char *q = argv[optind];

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddStringToObject(args, "query", q);
    if (limit != NULL)
        cJSON_AddNumberToObject(args, "limit", strtol(limit, NULL, 10));
    if (min_score != NULL)
        cJSON_AddNumberToObject(args, "min_score", strtod(min_score, NULL));
    if (tags_csv != NULL)
        cJSON_AddItemToObject(args, "tags", split_csv_array(tags_csv));

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "recall", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona query: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
