/*
 * config.c --- minimal INI-style config loader for the OwnSona CLI.
 *
 * Format (one key = value per line, sections ignored for now):
 *
 *     server_url = https://your.host/mcp
 *     token      = <bearer-token>
 *
 * Resolution order (highest priority first):
 *   1. CLI flag overrides (--server, --token), via *cli.
 *   2. Environment variables OWNSONA_SERVER, OWNSONA_TOKEN.
 *   3. Config file values.
 *
 * The config file path is taken from $OWNSONA_CONFIG if set, else the
 * OS-specific default returned by default_config_path():
 *
 *     Linux / BSD     ~/.config/ownsona/config.ini
 *     macOS           ~/Library/Application Support/ownsona/config.ini
 *     Windows         %LOCALAPPDATA%\ownsona\config.ini
 *
 * An explicit path passed in always wins.
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

/* Join `base` + `tail` into a malloc'd string. */
static char *join_path(const char *base, const char *tail) {
    const size_t need = strlen(base) + strlen(tail) + 1;
    char *out = malloc(need);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    snprintf(out, need, "%s%s", base, tail);
    return out;
}

/* Compute the OS-specific default config-file path as a malloc'd string. */
static char *default_config_path(void) {
#if defined(_WIN32)
    /* Windows: %LOCALAPPDATA%\ownsona\config.ini */
    const char *base = getenv("LOCALAPPDATA");
    if (base == NULL || *base == '\0')
        base = getenv("APPDATA");           /* roaming AppData fallback */
    if (base == NULL || *base == '\0')
        base = getenv("USERPROFILE");
    if (base == NULL || *base == '\0')
        base = getenv("HOME");              /* MSYS2 sets HOME */
    if (base == NULL || *base == '\0')
        return xstrdup("ownsona\\config.ini");
    return join_path(base, "\\ownsona\\config.ini");
#elif defined(__APPLE__)
    /* macOS: ~/Library/Application Support/ownsona/config.ini */
    const char *home = getenv("HOME");
    if (home == NULL || *home == '\0')
        return xstrdup("Library/Application Support/ownsona/config.ini");
    return join_path(home, "/Library/Application Support/ownsona/config.ini");
#else
    /* Linux / BSD: ~/.config/ownsona/config.ini */
    const char *home = getenv("HOME");
    if (home == NULL || *home == '\0')
        return xstrdup(".config/ownsona/config.ini");
    return join_path(home, "/.config/ownsona/config.ini");
#endif
}

/* ----- file parser ---------------------------------------------------- */

/* Read the file at `path` and set `cfg`'s fields from any keys it
 * recognizes (server_url, token).  Returns 0 on success.  Missing file is
 * NOT an error if `required` is false --- the caller may be relying on
 * env vars / CLI flags. */
static int parse_file(const char *path, bool required, ownsona_config_t *cfg) {
    FILE *fp = fopen(path, "r");
    if (fp == NULL) {
        if (errno == ENOENT && !required) {
            /* Remember the path anyway --- `ownsona auth login` needs
             * somewhere to write the first time. */
            cfg->source_path = xstrdup(path);
            return 0;
        }
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
        } else if (strcmp(key, "oauth_client_id") == 0) {
            free(cfg->oauth_client_id);
            cfg->oauth_client_id = xstrdup(value);
        } else if (strcmp(key, "oauth_refresh_token") == 0) {
            free(cfg->oauth_refresh_token);
            cfg->oauth_refresh_token = xstrdup(value);
        } else if (strcmp(key, "oauth_access_token") == 0) {
            free(cfg->oauth_access_token);
            cfg->oauth_access_token = xstrdup(value);
        } else if (strcmp(key, "oauth_access_token_expires_at") == 0) {
            cfg->oauth_access_token_expires_at = strtoll(value, NULL, 10);
        } else if (strcmp(key, "oauth_authorization_server") == 0) {
            free(cfg->oauth_authorization_server);
            cfg->oauth_authorization_server = xstrdup(value);
        } else if (strcmp(key, "oauth_resource") == 0) {
            free(cfg->oauth_resource);
            cfg->oauth_resource = xstrdup(value);
        } else if (strcmp(key, "llm_api_key") == 0) {
            free(cfg->llm_api_key);
            cfg->llm_api_key = xstrdup(value);
        } else if (strcmp(key, "llm_model") == 0) {
            free(cfg->llm_model);
            cfg->llm_model = xstrdup(value);
        } else if (strcmp(key, "llm_base_url") == 0) {
            free(cfg->llm_base_url);
            cfg->llm_base_url = xstrdup(value);
        } else if (strcmp(key, "subject_name") == 0 || strcmp(key, "subject") == 0) {
            free(cfg->subject_name);
            cfg->subject_name = xstrdup(value);
        }
        /* Unknown keys are silently ignored --- forward-compat. */
    }

    fclose(fp);
    return 0;
}

/* ----- public API ----------------------------------------------------- */

static int load_internal(const char *explicit_path,
                         const ownsona_config_t *cli,
                         ownsona_config_t *cfg,
                         bool enforce_credentials);

int ownsona_config_load(const char *explicit_path,
                        const ownsona_config_t *cli,
                        ownsona_config_t *cfg) {
    return load_internal(explicit_path, cli, cfg, true);
}

int ownsona_config_load_permissive(const char *explicit_path,
                                   const ownsona_config_t *cli,
                                   ownsona_config_t *cfg) {
    return load_internal(explicit_path, cli, cfg, false);
}

static int load_internal(const char *explicit_path,
                         const ownsona_config_t *cli,
                         ownsona_config_t *cfg,
                         bool enforce_credentials) {
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
    const char *env_llm_key = getenv("OWNSONA_LLM_API_KEY");
    if (env_llm_key != NULL && *env_llm_key != '\0') {
        free(cfg->llm_api_key);
        cfg->llm_api_key = xstrdup(env_llm_key);
    }
    const char *env_llm_model = getenv("OWNSONA_LLM_MODEL");
    if (env_llm_model != NULL && *env_llm_model != '\0') {
        free(cfg->llm_model);
        cfg->llm_model = xstrdup(env_llm_model);
    }
    const char *env_llm_url = getenv("OWNSONA_LLM_BASE_URL");
    if (env_llm_url != NULL && *env_llm_url != '\0') {
        free(cfg->llm_base_url);
        cfg->llm_base_url = xstrdup(env_llm_url);
    }
    const char *env_subject = getenv("OWNSONA_SUBJECT");
    if (env_subject != NULL && *env_subject != '\0') {
        free(cfg->subject_name);
        cfg->subject_name = xstrdup(env_subject);
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

    /* Apply defaults for LLM-side fields.  These don't fail if absent
     * --- a missing llm_api_key just means the `teach` subcommand will
     * complain when invoked, but other subcommands work fine. */
    if (cfg->llm_model == NULL || *cfg->llm_model == '\0') {
        free(cfg->llm_model);
        cfg->llm_model = xstrdup("gpt-4o");
    }
    if (cfg->llm_base_url == NULL || *cfg->llm_base_url == '\0') {
        free(cfg->llm_base_url);
        cfg->llm_base_url = xstrdup("https://api.openai.com/v1");
    }
    if (cfg->subject_name == NULL || *cfg->subject_name == '\0') {
        free(cfg->subject_name);
        cfg->subject_name = xstrdup("the user");
    }

    /* Required-ness check: must have server_url, and either a static
     * bearer token (legacy / external-IdP mode) or a refresh token from
     * a prior `ownsona auth login`.  The `auth` subcommand skips this
     * check (it's the one that *creates* the credentials). */
    if (cfg->server_url == NULL || *cfg->server_url == '\0') {
        fprintf(stderr, "ownsona: server URL not set "
                "(use --server, $OWNSONA_SERVER, or 'server_url = ...' in %s)\n",
                cfg->source_path ? cfg->source_path : "config file");
        return 1;
    }
    if (enforce_credentials) {
        const bool has_static  = cfg->token != NULL && *cfg->token != '\0';
        const bool has_refresh = cfg->oauth_refresh_token != NULL
                              && *cfg->oauth_refresh_token != '\0';
        if (!has_static && !has_refresh) {
            fprintf(stderr, "ownsona: no credentials configured.  Run "
                    "`ownsona auth login` to authenticate, or (legacy path) set "
                    "'token = ...' in %s.\n",
                    cfg->source_path ? cfg->source_path : "the config file");
            return 1;
        }
    }

    return 0;
}

void ownsona_config_free(ownsona_config_t *cfg) {
    if (cfg == NULL)
        return;
    free(cfg->server_url);
    free(cfg->token);
    free(cfg->source_path);
    free(cfg->oauth_client_id);
    free(cfg->oauth_refresh_token);
    free(cfg->oauth_access_token);
    free(cfg->oauth_authorization_server);
    free(cfg->oauth_resource);
    free(cfg->llm_api_key);
    free(cfg->llm_model);
    free(cfg->llm_base_url);
    free(cfg->subject_name);
    memset(cfg, 0, sizeof *cfg);
}
