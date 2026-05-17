/* cmd_add.c --- `ownsona add "<text>" [flags]` --- maps to MCP tool 'remember'. */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona add \"<text>\" [options]\n"
"\n"
"Store a new memory.\n"
"\n"
"Options:\n"
"  --tags T1,T2,...        comma-separated tags\n"
"  --provider NAME         optional source_provider label\n"
"  --importance N          importance score, 0..1 (default 0.5)\n"
"  --capture-mode MODE     'explicit' (user asked) or 'inferred' (you chose)\n"
"  --session-id ID         opaque conversation/session identifier\n"
"  --expires-at TS         ISO 8601, e.g. 2027-01-01T00:00:00Z\n"
"  --last-confirmed-at TS  ISO 8601\n"
"  --dedup POLICY          'insert' | 'skip_if_near' | 'ask' (default ask)\n"
"  -h, --help\n";

/* Split "a,b,c" into a cJSON array (caller owns).  Skips empty entries.
 * Trims whitespace around each entry. */
static cJSON *split_tags(const char *csv) {
    cJSON *arr = cJSON_CreateArray();
    if (csv == NULL)
        return arr;
    char *copy = strdup(csv);
    if (copy == NULL)
        ownsona_die(2, "out of memory");
    char *save = NULL;
    char *tok  = strtok_r(copy, ",", &save);
    while (tok != NULL) {
        while (*tok == ' ' || *tok == '\t')
            tok++;
        size_t n = strlen(tok);
        while (n > 0 && (tok[n-1] == ' ' || tok[n-1] == '\t'))
            tok[--n] = '\0';
        if (n > 0)
            cJSON_AddItemToArray(arr, cJSON_CreateString(tok));
        tok = strtok_r(NULL, ",", &save);
    }
    free(copy);
    return arr;
}

int cmd_add(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "tags",              required_argument, 0, 't' },
        { "provider",          required_argument, 0, 'p' },
        { "importance",        required_argument, 0, 'i' },
        { "capture-mode",      required_argument, 0, 'C' },
        { "session-id",        required_argument, 0, 'S' },
        { "expires-at",        required_argument, 0, 'E' },
        { "last-confirmed-at", required_argument, 0, 'L' },
        { "dedup",             required_argument, 0, 'D' },
        { "help",              no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };

    const char *tags_csv = NULL;
    const char *provider = NULL;
    const char *importance = NULL;
    const char *capture_mode = NULL;
    const char *session_id = NULL;
    const char *expires_at = NULL;
    const char *last_confirmed_at = NULL;
    const char *dedup = NULL;

    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 't': tags_csv          = optarg; break;
            case 'p': provider          = optarg; break;
            case 'i': importance        = optarg; break;
            case 'C': capture_mode      = optarg; break;
            case 'S': session_id        = optarg; break;
            case 'E': expires_at        = optarg; break;
            case 'L': last_confirmed_at = optarg; break;
            case 'D': dedup             = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }

    if (optind >= argc) {
        fprintf(stderr, "ownsona add: missing required <text> argument\n%s", USAGE);
        return 2;
    }
    const char *text = argv[optind];

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;

    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0)
        return 1;

    cJSON *args = cJSON_CreateObject();
    cJSON_AddStringToObject(args, "text", text);
    if (tags_csv != NULL)
        cJSON_AddItemToObject(args, "tags", split_tags(tags_csv));
    if (provider != NULL)
        cJSON_AddStringToObject(args, "source_provider", provider);
    if (importance != NULL)
        cJSON_AddNumberToObject(args, "importance", strtod(importance, NULL));
    if (capture_mode != NULL)
        cJSON_AddStringToObject(args, "capture_mode", capture_mode);
    if (session_id != NULL)
        cJSON_AddStringToObject(args, "session_id", session_id);
    if (expires_at != NULL)
        cJSON_AddStringToObject(args, "expires_at", expires_at);
    if (last_confirmed_at != NULL)
        cJSON_AddStringToObject(args, "last_confirmed_at", last_confirmed_at);
    if (dedup != NULL)
        cJSON_AddStringToObject(args, "dedup_policy", dedup);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "remember", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona add: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }

    if (gopt->json_output)
        ownsona_print_json(result);
    else
        ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
