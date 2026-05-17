/* cmd_import.c --- `ownsona import FILE [options]` --- MCP tool 'remember_batch'. */
#include "ownsona.h"

#include <ctype.h>
#include <errno.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static const char USAGE[] =
"usage: ownsona import FILE [options]\n"
"\n"
"Bulk-load memories from FILE using remember_batch (one HTTP round-trip,\n"
"one embedding call covering the whole batch).  Maximum 200 items per\n"
"call; if FILE has more, split it.\n"
"\n"
"Format is auto-detected from the first non-whitespace character:\n"
"  '['  --- JSON: a top-level array of objects, each with a 'text' key\n"
"           and optional 'tags' / 'importance' / 'source_provider' /\n"
"           'capture_mode' / 'session_id' / 'expires_at' /\n"
"           'last_confirmed_at' / 'dedup_policy'.\n"
"  else --- lines: one fact per line; '#' starts a comment; blank lines\n"
"           are skipped.  --tags applies to every line.\n"
"\n"
"Options:\n"
"  --provider NAME      default source_provider for items that don't\n"
"                       set their own (JSON) or for every line\n"
"  --dedup POLICY       default dedup_policy ('insert'/'skip_if_near'/'ask')\n"
"  --tags T1,T2,...     tags for lines-format input (ignored for JSON)\n"
"  --format lines|json  force a specific format (skips auto-detect)\n"
"  -h, --help\n";

/* Read whole file into a malloc'd buffer.  Caller frees. */
static char *slurp(const char *path, size_t *out_len) {
    FILE *fp = fopen(path, "rb");
    if (fp == NULL) {
        fprintf(stderr, "ownsona import: cannot open '%s': %s\n", path, strerror(errno));
        return NULL;
    }
    struct stat st;
    if (fstat(fileno(fp), &st) != 0) {
        fclose(fp);
        fprintf(stderr, "ownsona import: stat failed: %s\n", strerror(errno));
        return NULL;
    }
    char *buf = malloc((size_t) st.st_size + 1);
    if (buf == NULL) {
        fclose(fp);
        ownsona_die(2, "out of memory");
    }
    const size_t got = fread(buf, 1, (size_t) st.st_size, fp);
    fclose(fp);
    buf[got] = '\0';
    if (out_len) *out_len = got;
    return buf;
}

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

/* Build the items array from lines-format input.  Each non-comment,
 * non-blank line becomes one item with the given tags. */
static cJSON *parse_lines(const char *src, const char *tags_csv) {
    cJSON *items = cJSON_CreateArray();
    cJSON *base_tags = (tags_csv != NULL) ? split_tags(tags_csv) : NULL;

    const char *p = src;
    while (*p != '\0') {
        /* find end of line */
        const char *eol = p;
        while (*eol != '\0' && *eol != '\n')
            eol++;
        /* extract & trim */
        size_t len = (size_t)(eol - p);
        while (len > 0 && (p[len-1] == '\r' || p[len-1] == ' ' || p[len-1] == '\t'))
            len--;
        const char *start = p;
        while (len > 0 && (*start == ' ' || *start == '\t')) { start++; len--; }
        if (len > 0 && *start != '#') {
            char *line = malloc(len + 1);
            if (line == NULL) ownsona_die(2, "out of memory");
            memcpy(line, start, len);
            line[len] = '\0';
            cJSON *item = cJSON_CreateObject();
            cJSON_AddStringToObject(item, "text", line);
            free(line);
            if (base_tags != NULL)
                cJSON_AddItemToObject(item, "tags", cJSON_Duplicate(base_tags, 1));
            cJSON_AddItemToArray(items, item);
        }
        if (*eol == '\0')
            break;
        p = eol + 1;
    }
    cJSON_Delete(base_tags);
    return items;
}

int cmd_import(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "provider", required_argument, 0, 'p' },
        { "dedup",    required_argument, 0, 'D' },
        { "tags",     required_argument, 0, 't' },
        { "format",   required_argument, 0, 'F' },
        { "help",     no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *provider = NULL, *dedup = NULL, *tags_csv = NULL, *force_fmt = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'p': provider  = optarg; break;
            case 'D': dedup     = optarg; break;
            case 't': tags_csv  = optarg; break;
            case 'F': force_fmt = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona import: missing required FILE\n%s", USAGE);
        return 2;
    }
    const char *path = argv[optind];

    size_t src_len = 0;
    char *src = slurp(path, &src_len);
    if (src == NULL)
        return 1;

    /* Decide format */
    bool is_json = false;
    if (force_fmt != NULL) {
        if (strcmp(force_fmt, "json") == 0)
            is_json = true;
        else if (strcmp(force_fmt, "lines") != 0) {
            fprintf(stderr, "ownsona import: --format must be 'lines' or 'json'\n");
            free(src);
            return 2;
        }
    } else {
        const char *q = src;
        while (*q != '\0' && isspace((unsigned char)*q)) q++;
        if (*q == '[')
            is_json = true;
    }

    cJSON *items = NULL;
    if (is_json) {
        cJSON *parsed = cJSON_Parse(src);
        if (!cJSON_IsArray(parsed)) {
            fprintf(stderr, "ownsona import: JSON input must be a top-level array\n");
            cJSON_Delete(parsed);
            free(src);
            return 1;
        }
        items = parsed;   /* take ownership */
    } else {
        items = parse_lines(src, tags_csv);
    }
    free(src);

    const int count = cJSON_GetArraySize(items);
    if (count == 0) {
        fprintf(stderr, "ownsona import: no items found in '%s'\n", path);
        cJSON_Delete(items);
        return 1;
    }
    if (count > 200) {
        fprintf(stderr, "ownsona import: %d items > server limit of 200; "
                "split the file\n", count);
        cJSON_Delete(items);
        return 1;
    }

    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli, &cfg) != 0) {
        cJSON_Delete(items);
        return 1;
    }

    cJSON *args = cJSON_CreateObject();
    cJSON_AddItemToObject(args, "items", items);   /* takes ownership */
    if (provider != NULL) cJSON_AddStringToObject(args, "source_provider", provider);
    if (dedup    != NULL) cJSON_AddStringToObject(args, "dedup_policy",    dedup);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(&cfg, "remember_batch", args, &err);
    ownsona_config_free(&cfg);
    if (result == NULL) {
        fprintf(stderr, "ownsona import: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    if (gopt->json_output) ownsona_print_json(result);
    else                   ownsona_print_human(result);
    cJSON_Delete(result);
    return 0;
}
