/*
 * config.c --- minimal INI-style config loader for the OwnSona CLI.
 *
 * Format (one key = value per line, sections ignored for now):
 *
 *     # ~/.ownsona/config.ini
 *     server_url = https://your.host/mcp
 *     token      = <bearer-token>
 *
 * Resolution order (highest priority first):
 *   1. CLI flag overrides (--server, --token), via *cli.
 *   2. Environment variables OWNSONA_SERVER, OWNSONA_TOKEN.
 *   3. Config file values.
 *
 * The config file path is taken from $OWNSONA_CONFIG if set, else
 * ~/.ownsona/config.ini.  An explicit path passed in always wins.
 */
#include "ownsona.h"

#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ----- small helpers -------------------------------------------------- */

static char *xstrdup(const char *s) {
    if (s == NULL)
        return NULL;
    char *out = strdup(s);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    return out;
}

static char *trim(char *s) {
    if (s == NULL)
        return NULL;
    while (*s != '\0' && isspace((unsigned char)*s))
        s++;
    if (*s == '\0')
        return s;
    char *end = s + strlen(s) - 1;
    while (end > s && isspace((unsigned char)*end))
        *end-- = '\0';
    return s;
}

/* Build "$HOME/.ownsona/config.ini" into a malloc'd string. */
static char *default_config_path(void) {
    const char *home = getenv("HOME");
    if (home == NULL || *home == '\0') {
        /* MSYS2 sets HOME for the user; this path is rarely hit. */
        return xstrdup(".ownsona/config.ini");
    }
    const size_t need = strlen(home) + strlen("/.ownsona/config.ini") + 1;
    char *out = malloc(need);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    snprintf(out, need, "%s/.ownsona/config.ini", home);
    return out;
}

/* ----- file parser ---------------------------------------------------- */

/* Read the file at `path` and set `cfg`'s fields from any keys it
 * recognizes (server_url, token).  Returns 0 on success.  Missing file is
 * NOT an error if `required` is false --- the caller may be relying on
 * env vars / CLI flags. */
static int parse_file(const char *path, bool required, ownsona_config_t *cfg) {
    FILE *fp = fopen(path, "r");
    if (fp == NULL) {
        if (errno == ENOENT && !required)
            return 0;
        fprintf(stderr, "ownsona: cannot open config '%s': %s\n",
                path, strerror(errno));
        return 1;
    }

    cfg->source_path = xstrdup(path);

    char line[2048];
    int  lineno = 0;
    while (fgets(line, sizeof line, fp) != NULL) {
        lineno++;
        char *s = trim(line);
        if (*s == '\0' || *s == '#' || *s == ';')
            continue;
        if (*s == '[')         /* [section] --- accepted but ignored */
            continue;
        char *eq = strchr(s, '=');
        if (eq == NULL) {
            fprintf(stderr, "ownsona: %s:%d: ignoring line without '=': %s\n",
                    path, lineno, s);
            continue;
        }
        *eq = '\0';
        char *key   = trim(s);
        char *value = trim(eq + 1);

        /* Strip optional surrounding quotes on the value. */
        size_t vlen = strlen(value);
        if (vlen >= 2 &&
            ((value[0] == '"'  && value[vlen-1] == '"')  ||
             (value[0] == '\'' && value[vlen-1] == '\''))) {
            value[vlen-1] = '\0';
            value++;
        }

        if (strcmp(key, "server_url") == 0 || strcmp(key, "server") == 0) {
            free(cfg->server_url);
            cfg->server_url = xstrdup(value);
        } else if (strcmp(key, "token") == 0 || strcmp(key, "api_token") == 0) {
            free(cfg->token);
            cfg->token = xstrdup(value);
        }
        /* Unknown keys are silently ignored --- forward-compat. */
    }

    fclose(fp);
    return 0;
}

/* ----- public API ----------------------------------------------------- */

int ownsona_config_load(const char *explicit_path,
                        const ownsona_config_t *cli,
                        ownsona_config_t *cfg) {
    memset(cfg, 0, sizeof *cfg);

    /* 1) Config file --- only required if the caller supplied an explicit
     *    --config path.  Otherwise fall through to env + CLI overrides. */
    const char *path_to_use = NULL;
    char       *default_path = NULL;
    if (explicit_path != NULL && *explicit_path != '\0') {
        path_to_use = explicit_path;
    } else {
        const char *env_path = getenv("OWNSONA_CONFIG");
        if (env_path != NULL && *env_path != '\0') {
            path_to_use = env_path;
        } else {
            default_path = default_config_path();
            path_to_use = default_path;
        }
    }
    const bool required = (explicit_path != NULL);
    if (parse_file(path_to_use, required, cfg) != 0) {
        free(default_path);
        return 1;
    }
    free(default_path);

    /* 2) Environment overrides */
    const char *env_server = getenv("OWNSONA_SERVER");
    if (env_server != NULL && *env_server != '\0') {
        free(cfg->server_url);
        cfg->server_url = xstrdup(env_server);
    }
    const char *env_token = getenv("OWNSONA_TOKEN");
    if (env_token != NULL && *env_token != '\0') {
        free(cfg->token);
        cfg->token = xstrdup(env_token);
    }

    /* 3) CLI overrides (highest priority) */
    if (cli != NULL) {
        if (cli->server_url != NULL && *cli->server_url != '\0') {
            free(cfg->server_url);
            cfg->server_url = xstrdup(cli->server_url);
        }
        if (cli->token != NULL && *cli->token != '\0') {
            free(cfg->token);
            cfg->token = xstrdup(cli->token);
        }
    }

    /* Required-ness check */
    if (cfg->server_url == NULL || *cfg->server_url == '\0') {
        fprintf(stderr, "ownsona: server URL not set "
                "(use --server, $OWNSONA_SERVER, or 'server_url = ...' in %s)\n",
                cfg->source_path ? cfg->source_path : "config file");
        return 1;
    }
    if (cfg->token == NULL || *cfg->token == '\0') {
        fprintf(stderr, "ownsona: bearer token not set "
                "(use --token, $OWNSONA_TOKEN, or 'token = ...' in %s)\n",
                cfg->source_path ? cfg->source_path : "config file");
        return 1;
    }

    return 0;
}

void ownsona_config_free(ownsona_config_t *cfg) {
    if (cfg == NULL)
        return;
    free(cfg->server_url);
    free(cfg->token);
    free(cfg->source_path);
    memset(cfg, 0, sizeof *cfg);
}
