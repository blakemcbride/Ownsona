/*
 * cmd_display.c --- `ownsona display [ids] [-k <YNU>]`
 *
 * Show selected memories (id order), printing only the id, keep flag, and
 * text.  With no ids, show all.  -k filters by keep flag, e.g. "-k NU"
 * shows only the No and Unspecified rows.
 */
#include "ownsona.h"

#include <ctype.h>
#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char USAGE[] =
"usage: ownsona display [ids] [-k <YNU>]\n"
"\n"
"Show memories (id, keep, text), in id order.\n"
"\n"
"  ids          id (5), range (5-9), list (5,7,9), or a combination\n"
"               (5-9,12,20-$).  '$' means the last id.  Omit to show all.\n"
"  -k <YNU>     only show rows whose keep flag is one of the given letters\n"
"               (Y=protected, N=no, U=unspecified).  e.g. -k NU\n"
"  -h, --help\n";

/* True when `keep` passes the (optional) -k filter. */
static int keep_passes(const char *filter, char keep) {
    if (filter == NULL || *filter == '\0')
        return 1;
    for (const char *p = filter; *p != '\0'; p++)
        if (toupper((unsigned char) *p) == keep)
            return 1;
    return 0;
}

int cmd_display(int argc, char **argv, const ownsona_global_opts_t *gopt) {
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
        fprintf(stderr, "ownsona display: unexpected extra argument '%s'\n"
                        "(ids must be a single token with no spaces, e.g. 5-9,12)\n%s",
                argv[optind], USAGE);
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
        fprintf(stderr, "ownsona display: %s\n", err ? err : "(unknown error)");
        free(err);
        ownsona_config_free(&cfg);
        return 1;
    }
    ownsona_config_free(&cfg);

    long *known = malloc((nmems ? nmems : 1) * sizeof *known);
    if (known == NULL)
        ownsona_die(2, "out of memory");
    for (size_t i = 0; i < nmems; i++)
        known[i] = mems[i].id;

    size_t nsel = 0;
    long *sel = ownsona_parse_ids(ids_spec, known, nmems, &nsel, &err);
    free(known);
    if (sel == NULL && err != NULL) {
        fprintf(stderr, "ownsona display: %s\n", err);
        free(err);
        ownsona_mems_free(mems, nmems);
        return 2;
    }

    /* mems is sorted by id; iterate it and print the ones selected. */
    int shown = 0;
    cJSON *jarr = gopt->json_output ? cJSON_CreateArray() : NULL;
    for (size_t i = 0; i < nmems; i++) {
        int selected = (sel == NULL && nsel == 0 && (ids_spec == NULL || *ids_spec == '\0'));
        for (size_t s = 0; s < nsel && !selected; s++)
            if (sel[s] == mems[i].id)
                selected = 1;
        if (!selected)
            continue;
        if (!keep_passes(keep_filter, mems[i].keep))
            continue;
        if (jarr != NULL) {
            cJSON *o = cJSON_CreateObject();
            cJSON_AddNumberToObject(o, "id", (double) mems[i].id);
            const char ks[2] = { mems[i].keep, '\0' };
            cJSON_AddStringToObject(o, "keep", ks);
            cJSON_AddStringToObject(o, "text", mems[i].text);
            cJSON_AddItemToArray(jarr, o);
        } else {
            printf("  [%ld] %c  %s\n", mems[i].id, mems[i].keep, mems[i].text);
        }
        shown++;
    }
    free(sel);
    ownsona_mems_free(mems, nmems);

    if (jarr != NULL) {
        char *s = cJSON_Print(jarr);
        if (s != NULL) {
            fputs(s, stdout);
            fputc('\n', stdout);
            free(s);
        }
        cJSON_Delete(jarr);
    } else if (shown == 0) {
        printf("(no matching memories)\n");
    }
    return 0;
}
