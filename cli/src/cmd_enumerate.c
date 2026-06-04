/*
 * cmd_enumerate.c --- `ownsona enumerate [ids] [-k <YNU>]`
 *
 * Like display, but interactive: walk the selected memories one at a time
 * and act on each.  Commands:
 *   d  hard-delete the memory
 *   k  set keep = Y (protect)
 *   r  set keep = N (un-protect)
 *   c  change the memory's text (prompts for the new text)
 *   ?  / h   show this command list
 *   <Enter>  skip to the next memory
 *   q  quit
 *
 * Setting keep (k / r) requires admin_secret in the config; the server
 * gates the keep flag to the CLI alone.
 */
#include "ownsona.h"

#include <ctype.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona enumerate [ids] [-k <YNU>]\n"
"\n"
"Walk selected memories one at a time and act on each.\n"
"\n"
"  ids          id (5), range (5-9), list (5,7,9), or combination (5-9,12,20-$).\n"
"               '$' means the last id.  Omit to walk all.\n"
"  -k <YNU>     only walk rows whose keep flag is one of the given letters.\n"
"  -h, --help\n";

static const char COMMANDS[] =
"  d  delete (hard)        k  keep=Y (protect)     r  keep=N (un-protect)\n"
"  c  change text          ?  / h  this list       <Enter>  next     q  quit\n";

static int keep_passes(const char *filter, char keep) {
    if (filter == NULL || *filter == '\0')
        return 1;
    for (const char *p = filter; *p != '\0'; p++)
        if (toupper((unsigned char) *p) == keep)
            return 1;
    return 0;
}

/* Read a line from stdin into buf (without newline).  Returns 0 on
 * success, non-zero on EOF. */
static int read_line(char *buf, size_t n) {
    if (fgets(buf, (int) n, stdin) == NULL)
        return 1;
    size_t len = strlen(buf);
    while (len > 0 && (buf[len-1] == '\n' || buf[len-1] == '\r'))
        buf[--len] = '\0';
    return 0;
}

static int do_set_keep(const ownsona_config_t *cfg, long id, char keep) {
    if (cfg->admin_secret == NULL || *cfg->admin_secret == '\0') {
        printf("  cannot set keep: admin_secret is not configured "
               "(add it to your config to manage the keep flag)\n");
        return 1;
    }
    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    const char ks[2] = { keep, '\0' };
    cJSON_AddStringToObject(args, "keep", ks);
    cJSON_AddStringToObject(args, "admin_secret", cfg->admin_secret);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "set_keep", args, &err);
    if (result == NULL) {
        printf("  set keep failed: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    cJSON_Delete(result);
    printf("  keep set to %c\n", keep);
    return 0;
}

static int do_hard_delete(const ownsona_config_t *cfg, long id) {
    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    cJSON_AddBoolToObject(args, "hard_delete", 1);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "forget", args, &err);
    if (result == NULL) {
        printf("  delete failed: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    cJSON_Delete(result);
    printf("  deleted\n");
    return 0;
}

static int do_change(const ownsona_config_t *cfg, long id) {
    printf("  new text: ");
    fflush(stdout);
    char line[8192];
    if (read_line(line, sizeof line) != 0 || line[0] == '\0') {
        printf("  (no text entered; unchanged)\n");
        return 1;
    }
    char *text = ownsona_ensure_trailing_period(line);
    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    cJSON_AddStringToObject(args, "text", text);
    cJSON_AddStringToObject(args, "source_provider", "ownsona");
    cJSON_AddStringToObject(args, "source_client", "cli");
    free(text);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "update_memory", args, &err);
    if (result == NULL) {
        printf("  change failed: %s\n", err ? err : "(unknown error)");
        free(err);
        return 1;
    }
    cJSON_Delete(result);
    printf("  changed\n");
    return 0;
}

int cmd_enumerate(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "keep", required_argument, 0, 'k' },
        { "help", no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *keep_filter = NULL;
    int c;
    while ((c = getopt_long(argc, argv, "k:h", longopts, NULL)) != -1) {
        switch (c) {
            case 'k': keep_filter = optarg; break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    const char *ids_spec = NULL;
    if (optind < argc)
        ids_spec = argv[optind++];
    if (optind < argc) {
        fprintf(stderr, "ownsona enumerate: unexpected extra argument '%s'\n%s", argv[optind], USAGE);
        return 2;
    }

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0)
        return 1;

    ownsona_mem_t *mems = NULL;
    size_t nmems = 0;
    char *err = NULL;
    if (ownsona_fetch_active(&cfg, &mems, &nmems, &err) != 0) {
        fprintf(stderr, "ownsona enumerate: %s\n", err ? err : "(unknown error)");
        free(err);
        ownsona_config_free(&cfg);
        return 1;
    }

    long *known = malloc((nmems ? nmems : 1) * sizeof *known);
    if (known == NULL)
        ownsona_die(2, "out of memory");
    for (size_t i = 0; i < nmems; i++)
        known[i] = mems[i].id;

    size_t nsel = 0;
    long *sel = ownsona_parse_ids(ids_spec, known, nmems, &nsel, &err);
    free(known);
    if (sel == NULL && err != NULL) {
        fprintf(stderr, "ownsona enumerate: %s\n", err);
        free(err);
        ownsona_mems_free(mems, nmems);
        ownsona_config_free(&cfg);
        return 2;
    }

    int quit = 0;
    int walked = 0;
    for (size_t i = 0; i < nmems && !quit; i++) {
        /* Is this row selected? */
        int selected = 0;
        for (size_t s = 0; s < nsel; s++)
            if (sel[s] == mems[i].id) { selected = 1; break; }
        if (!selected || !keep_passes(keep_filter, mems[i].keep))
            continue;

        walked++;
        /* Re-prompt on the same memory until an advancing key is hit. */
        int advance = 0;
        while (!advance && !quit) {
            printf("\n  [%ld] %c  %s\n", mems[i].id, mems[i].keep, mems[i].text);
            printf("  (d/k/r/c/?/Enter/q) > ");
            fflush(stdout);
            char line[64];
            if (read_line(line, sizeof line) != 0) {   /* EOF */
                quit = 1;
                break;
            }
            const char cmd = line[0];
            switch (cmd) {
                case '\0':            advance = 1; break;          /* Enter -> next */
                case 'd': do_hard_delete(&cfg, mems[i].id); advance = 1; break;
                case 'k': do_set_keep(&cfg, mems[i].id, 'Y'); advance = 1; break;
                case 'r': do_set_keep(&cfg, mems[i].id, 'N'); advance = 1; break;
                case 'c': do_change(&cfg, mems[i].id); advance = 1; break;
                case 'q': quit = 1; break;
                case '?':
                case 'h': fputs(COMMANDS, stdout); break;
                default:  printf("  unknown command '%c'\n", cmd); fputs(COMMANDS, stdout); break;
            }
        }
    }

    free(sel);
    ownsona_mems_free(mems, nmems);
    ownsona_config_free(&cfg);
    if (walked == 0)
        printf("(no matching memories)\n");
    return 0;
}
