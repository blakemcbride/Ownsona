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
    char *server_url;   /* https://host/mcp                              */
    char *token;        /* bearer token                                   */
    char *source_path;  /* path the config was loaded from, for messages  */
} ownsona_config_t;

/*
 * Resolve the effective config.  Order of precedence:
 *   1. CLI flags (--server, --token) passed in via *cli_overrides
 *   2. Environment variables: OWNSONA_SERVER, OWNSONA_TOKEN
 *   3. The config file (path = $OWNSONA_CONFIG or ~/.ownsona/config.ini)
 *
 * Returns 0 on success.  On failure prints to stderr and returns non-zero.
 * Caller owns *cfg and must call ownsona_config_free() to release it.
 */
int  ownsona_config_load(const char *explicit_path,
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

#endif /* OWNSONA_H */
