/*
 * cmd_auth.c --- `ownsona auth <login|status>`
 *
 * `login`:  run the OAuth 2.1 auth code + PKCE flow against the AS
 *           that protects this CLI's configured server_url, dynamic-
 *           register a client, and persist the resulting refresh +
 *           access token to the config file.
 * `status`: print whether the CLI has usable credentials, what their
 *           shape is (static-bearer / OAuth), and (for OAuth) when
 *           the access token expires.
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static const char USAGE[] =
"usage: ownsona auth <login|status>\n"
"\n"
"login    Open the browser, complete the OAuth login + consent flow\n"
"         against the AS that protects your configured server, and\n"
"         write the resulting tokens to the config file.\n"
"status   Print whether usable credentials are configured and, for\n"
"         OAuth credentials, how much time is left on the access token.\n";

static int do_login(const ownsona_global_opts_t *gopt) {
    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load_permissive(gopt->config_path, &cli, &cfg) != 0)
        return 1;
    const int rc = ownsona_oauth_bootstrap(&cfg);
    ownsona_config_free(&cfg);
    return rc;
}

static int do_status(const ownsona_global_opts_t *gopt) {
    ownsona_config_t cli = {0};
    cli.server_url = (char *) gopt->server_override;
    cli.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load_permissive(gopt->config_path, &cli, &cfg) != 0)
        return 1;

    printf("config file:           %s\n",
           cfg.source_path ? cfg.source_path : "(none)");
    printf("server_url:            %s\n",
           cfg.server_url ? cfg.server_url : "(unset)");

    if (cfg.token != NULL && *cfg.token != '\0') {
        printf("auth mode:             static bearer token\n");
        printf("token configured:      yes (length=%zu)\n", strlen(cfg.token));
        ownsona_config_free(&cfg);
        return 0;
    }
    if (cfg.oauth_refresh_token != NULL && *cfg.oauth_refresh_token != '\0') {
        printf("auth mode:             OAuth 2.1\n");
        printf("authorization server:  %s\n",
               cfg.oauth_authorization_server ? cfg.oauth_authorization_server : "(auto-discover)");
        printf("oauth_resource:        %s\n",
               cfg.oauth_resource ? cfg.oauth_resource : cfg.server_url);
        printf("client_id:             %s\n",
               cfg.oauth_client_id ? cfg.oauth_client_id : "(unset)");
        printf("refresh_token:         present (length=%zu)\n",
               strlen(cfg.oauth_refresh_token));
        if (cfg.oauth_access_token != NULL && cfg.oauth_access_token_expires_at > 0) {
            const long long now = (long long) time(NULL);
            const long long left = cfg.oauth_access_token_expires_at - now;
            if (left > 0)
                printf("access_token expires:  in %lld seconds (epoch %lld)\n",
                       left, cfg.oauth_access_token_expires_at);
            else
                printf("access_token expires:  %lld seconds ago "
                       "(will refresh on next call)\n", -left);
        } else {
            printf("access_token:          not yet issued (will refresh on next call)\n");
        }
        ownsona_config_free(&cfg);
        return 0;
    }

    printf("auth mode:             none configured\n");
    printf("Run `ownsona auth login` to authenticate.\n");
    ownsona_config_free(&cfg);
    return 0;
}

int cmd_auth(int argc, char **argv, const ownsona_global_opts_t *gopt) {
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
    if (optind >= argc) {
        fprintf(stderr, "ownsona auth: missing subcommand\n%s", USAGE);
        return 2;
    }
    const char *sub = argv[optind];
    if (strcmp(sub, "login") == 0)
        return do_login(gopt);
    if (strcmp(sub, "status") == 0)
        return do_status(gopt);
    fprintf(stderr, "ownsona auth: unknown subcommand '%s'\n%s", sub, USAGE);
    return 2;
}
