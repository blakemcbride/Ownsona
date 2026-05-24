/*
 * ownsona.h --- shared declarations across the OwnSona CLI.
 *
 * Layering:
 *   config.c  --- reads server URL + bearer token from disk + env + flags.
 *   http.c    --- thin libcurl wrapper: HTTPS POST a JSON-RPC envelope,
 *                 return the response body as a malloc'd buffer.
 *   mcp.c     --- one function per MCP tool.  Builds the JSON-RPC
 *                 request, calls http.c, parses the tools/call response,
 *                 unwraps the inner tool result, surfaces errors.
 *   main.c    --- argv parsing + subcommand dispatch.
 *   cmd_*.c   --- per-subcommand argv parsing and output formatting.
 */
#ifndef OWNSONA_H
#define OWNSONA_H

#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"

#ifndef OWNSONA_VERSION
#define OWNSONA_VERSION "dev"
#endif

/* ---------------------------------------------------------------------- */
/* config                                                                 */
/* ---------------------------------------------------------------------- */

typedef struct {
    char *server_url;       /* https://host/mcp                          */
    char *token;            /* optional static bearer token (legacy /    *
                             * external-IdP path); when set, http.c uses *
                             * it verbatim with no refresh.              */
    char *source_path;      /* config file path, for messages            */

    /* OAuth 2.1 state populated by `ownsona auth login` and updated on
     * each refresh.  When oauth_refresh_token is set, http.c calls
     * ownsona_oauth_ensure_fresh_token() before every request, which
     * trades the refresh token for a new access token whenever the
     * cached one is within a minute of expiry.                          */
    char     *oauth_client_id;
    char     *oauth_refresh_token;
    char     *oauth_access_token;
    long long oauth_access_token_expires_at;   /* Unix epoch seconds   */
    char     *oauth_authorization_server;      /* AS issuer URL; auto-
                                                * discovered via RFC 9728
                                                * during `auth login` if
                                                * the user does not set
                                                * it explicitly.        */
    char     *oauth_resource;                  /* RFC 8707 resource
                                                * indicator; defaults to
                                                * server_url.           */

    /* Optional LLM credentials, used only by the `teach` subcommand
     * to extract facts from prose.  llm_api_key may be NULL when no
     * LLM-based feature is being invoked.  llm_model and llm_base_url
     * always have sane defaults applied by the loader. */
    char *llm_api_key;
    char *llm_model;
    char *llm_base_url;

    /* How to refer to the subject in extracted facts ("Blake", "the
     * user", etc.).  Defaults to "the user". */
    char *subject_name;
} ownsona_config_t;

/*
 * Resolve the effective config.  Order of precedence:
 *   1. CLI flags (--server, --token) passed in via *cli_overrides
 *   2. Environment variables: OWNSONA_SERVER, OWNSONA_TOKEN
 *   3. The config file (path = $OWNSONA_CONFIG or the OS-specific default:
 *        Linux/BSD  ~/.config/ownsona/config.ini
 *        macOS      ~/Library/Application Support/ownsona/config.ini
 *        Windows    %LOCALAPPDATA%\ownsona\config.ini)
 *
 * Returns 0 on success.  On failure prints to stderr and returns non-zero.
 * Caller owns *cfg and must call ownsona_config_free() to release it.
 */
int  ownsona_config_load(const char *explicit_path,
                         const ownsona_config_t *cli_overrides,
                         ownsona_config_t *cfg);

/*
 * Same as ownsona_config_load() but does NOT enforce the
 * credentials check.  Used by `ownsona auth login` --- the whole
 * point of that subcommand is to populate credentials, so they
 * cannot be required for it to start.  server_url is still required.
 */
int  ownsona_config_load_permissive(const char *explicit_path,
                                    const ownsona_config_t *cli_overrides,
                                    ownsona_config_t *cfg);

void ownsona_config_free(ownsona_config_t *cfg);

/* ---------------------------------------------------------------------- */
/* http                                                                   */
/* ---------------------------------------------------------------------- */

typedef struct {
    long   http_status;     /* HTTP status code as reported by libcurl    */
    char  *body;            /* malloc'd response body, null-terminated    */
    size_t body_len;
} ownsona_http_response_t;

/*
 * POST a JSON body to the configured server.  Sets the bearer token
 * header.  Returns 0 if libcurl succeeded (regardless of HTTP status);
 * non-zero on transport failure.  Caller must call
 * ownsona_http_response_free() either way.
 */
int  ownsona_http_post_json(const ownsona_config_t *cfg,
                            const char *json_body,
                            ownsona_http_response_t *resp);
void ownsona_http_response_free(ownsona_http_response_t *resp);
void ownsona_http_global_init(void);
void ownsona_http_global_cleanup(void);

/* ---------------------------------------------------------------------- */
/* mcp                                                                    */
/* ---------------------------------------------------------------------- */

/*
 * Call an MCP tool by name with the given arguments object.  *arguments
 * is consumed (deleted) by this function.  On success returns the parsed
 * tool result (always a JSON object).  On transport or protocol failure
 * returns NULL and fills *err with a short message (free'd by caller).
 *
 * The MCP protocol nests the actual tool output as a JSON string inside
 * result.content[0].text; this function unwraps that for you.
 */
cJSON *ownsona_mcp_call(const ownsona_config_t *cfg,
                        const char *tool_name,
                        cJSON *arguments,
                        char **err);

/* ---------------------------------------------------------------------- */
/* output helpers                                                         */
/* ---------------------------------------------------------------------- */

/*
 * Pretty-print a tool result for human consumption.  Knows about the
 * shapes returned by recall/list/text_search/etc; falls back to a JSON
 * dump when it doesn't recognize the shape.
 */
void ownsona_print_human(cJSON *result);

/*
 * Print the raw JSON of a result (for --json mode and piping to jq).
 * Adds a trailing newline.
 */
void ownsona_print_json(cJSON *result);

void ownsona_die(int exit_code, const char *fmt, ...)
    __attribute__((noreturn, format(printf, 2, 3)));

/* ---------------------------------------------------------------------- */
/* subcommand entry points                                                */
/* ---------------------------------------------------------------------- */

typedef struct {
    const char *config_path;     /* --config */
    const char *server_override; /* --server */
    const char *token_override;  /* --token */
    bool json_output;            /* --json   */
} ownsona_global_opts_t;

int cmd_add    (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_query  (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_search (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_list   (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_update (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_confirm(int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_forget (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_prompt (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_import (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_teach  (int argc, char **argv, const ownsona_global_opts_t *gopt);
int cmd_auth   (int argc, char **argv, const ownsona_global_opts_t *gopt);

/* ---------------------------------------------------------------------- */
/* oauth --- bootstrap (auth login) + per-request refresh                 */
/* ---------------------------------------------------------------------- */

/*
 * Ensure cfg->oauth_access_token is non-expired.  Called by http.c
 * before every MCP request.  Behavior:
 *   - If cfg->token is set (static-bearer mode), this is a no-op.
 *   - If cfg->oauth_access_token is set and expires more than 60s in
 *     the future, return immediately.
 *   - Otherwise, trade cfg->oauth_refresh_token at the AS token
 *     endpoint for a fresh access token (and rotated refresh token),
 *     update cfg in memory, and atomically rewrite the config file
 *     so the new tokens survive across CLI invocations.
 * Returns 0 on success.  Non-zero on failure (no usable token); a
 * diagnostic has already been printed to stderr.  Caller is expected
 * to abort the operation if non-zero.
 */
int ownsona_oauth_ensure_fresh_token(ownsona_config_t *cfg);

/*
 * Drive the full OAuth 2.1 auth code + PKCE flow once: dynamic client
 * registration, browser-driven login + consent, code-to-token
 * exchange, atomic config persist.  Called by `ownsona auth login`.
 * Returns 0 on success.  Non-zero on any failure (diagnostic printed
 * to stderr).
 */
int ownsona_oauth_bootstrap(ownsona_config_t *cfg);

/* ---------------------------------------------------------------------- */
/* llm --- OpenAI-compatible chat-completion call (used by `teach`)       */
/* ---------------------------------------------------------------------- */

/*
 * POST a chat-completion request to cfg->llm_base_url/chat/completions
 * with the given system + user messages.  model_override (may be NULL)
 * temporarily replaces cfg->llm_model for this one call --- used by
 * --model on the teach subcommand.  Returns the parsed JSON content of
 * the assistant's message (the model is told to reply in JSON).  On
 * failure returns NULL and *err is set (caller frees).
 */
cJSON *ownsona_llm_chat(const ownsona_config_t *cfg,
                        const char *model_override,
                        const char *system_prompt,
                        const char *user_message,
                        char **err);

#endif /* OWNSONA_H */
