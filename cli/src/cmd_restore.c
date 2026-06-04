/*
 * cmd_restore.c --- `ownsona restore <file>`
 *
 * Re-insert memories from a dump file produced by `ownsona dump`.  Active
 * (non-deleted) memories are re-inserted via remember_batch; the keep flag
 * is re-applied with set_keep where the memory was Y or N.
 *
 * Restore is content-level, NOT byte-faithful.  Via the MCP tool surface
 * it cannot preserve the original ids or created_at timestamps (the
 * server assigns fresh ones), and embeddings are re-derived from the
 * text.  Soft-deleted rows in the dump are skipped.  It is intended for
 * restoring into an empty store.
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define RESTORE_BATCH 200

static const char USAGE[] =
"usage: ownsona restore <file> [--dedup POLICY] [--dry-run]\n"
"\n"
"Re-insert memories from a dump file produced by `ownsona dump`.\n"
"\n"
"Active memories are re-inserted (text, tags, source_provider,\n"
"importance, capture_mode, session_id, expires_at, last_confirmed_at).\n"
"The keep flag is re-applied for Y/N rows when admin_secret is set in\n"
"the config.  Original ids and created_at timestamps are NOT preserved\n"
"(the server assigns new ones); embeddings are re-derived from text.\n"
"Soft-deleted rows are skipped.  Intended for restoring into an empty\n"
"store --- against a populated store the default `insert` policy creates\n"
"duplicates (use --dedup skip_if_near to merge instead).\n"
"\n"
"  --dedup POLICY   insert (default) | skip_if_near | ask\n"
"  --dry-run        report what would be restored, without writing\n"
"  -h, --help\n";

static const char *jstr(cJSON *o, const char *k) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(o, k);
    return (cJSON_IsString(v) && v->valuestring) ? v->valuestring : NULL;
}

static char *read_file(const char *path) {
    FILE *f = fopen(path, "rb");
    if (f == NULL)
        return NULL;
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return NULL;
    }
    const long sz = ftell(f);
    if (sz < 0) {
        fclose(f);
        return NULL;
    }
    rewind(f);
    char *buf = malloc((size_t) sz + 1);
    if (buf == NULL)
        ownsona_die(2, "out of memory");
    const size_t got = fread(buf, 1, (size_t) sz, f);
    fclose(f);
    buf[got] = '\0';
    return buf;
}

/* Re-apply a keep flag to a freshly-restored row. */
static void apply_keep(const ownsona_config_t *cfg, long id, char keep,
                       long *applied, long *no_secret, long *errors) {
    if (cfg->admin_secret == NULL || *cfg->admin_secret == '\0') {
        (*no_secret)++;
        return;
    }
    cJSON *args = cJSON_CreateObject();
    cJSON_AddNumberToObject(args, "id", (double) id);
    const char ks[2] = { keep, '\0' };
    cJSON_AddStringToObject(args, "keep", ks);
    cJSON_AddStringToObject(args, "admin_secret", cfg->admin_secret);

    char *err = NULL;
    cJSON *res = ownsona_mcp_call(cfg, "set_keep", args, &err);
    if (res == NULL) {
        (*errors)++;
        free(err);
        return;
    }
    cJSON_Delete(res);
    (*applied)++;
}

/* Send one remember_batch and re-apply keep on the inserted rows.
 * Consumes `items` (handed to the MCP call). */
static void flush_batch(const ownsona_config_t *cfg, cJSON *items,
                        const char *keep_chars, int n, const char *dedup,
                        long *restored, long *errors,
                        long *keep_applied, long *keep_no_secret, long *keep_errors) {
    cJSON *args = cJSON_CreateObject();
    cJSON_AddItemToObject(args, "items", items);   /* transfers ownership of items */
    if (dedup != NULL)
        cJSON_AddStringToObject(args, "dedup_policy", dedup);

    char *err = NULL;
    cJSON *result = ownsona_mcp_call(cfg, "remember_batch", args, &err);   /* consumes args */
    if (result == NULL) {
        fprintf(stderr, "  batch failed: %s\n", err ? err : "(unknown error)");
        free(err);
        *errors += n;
        return;
    }

    cJSON *results = cJSON_GetObjectItemCaseSensitive(result, "results");
    if (cJSON_IsArray(results)) {
        cJSON *r;
        cJSON_ArrayForEach(r, results) {
            const int idx = (int) cJSON_GetNumberValue(
                cJSON_GetObjectItemCaseSensitive(r, "input_index"));
            cJSON *ok = cJSON_GetObjectItemCaseSensitive(r, "ok");
            if (cJSON_IsTrue(ok)) {
                (*restored)++;
                if (idx >= 0 && idx < n && (keep_chars[idx] == 'Y' || keep_chars[idx] == 'N')) {
                    const long id = (long) cJSON_GetNumberValue(
                        cJSON_GetObjectItemCaseSensitive(r, "memory_id"));
                    apply_keep(cfg, id, keep_chars[idx],
                               keep_applied, keep_no_secret, keep_errors);
                }
            } else {
                (*errors)++;
                cJSON *e = cJSON_GetObjectItemCaseSensitive(r, "error");
                const char *msg = e ? jstr(e, "message") : NULL;
                fprintf(stderr, "  item %d not restored: %s\n", idx, msg ? msg : "(error)");
            }
        }
    }
    cJSON_Delete(result);
}

int cmd_restore(int argc, char **argv, const ownsona_global_opts_t *gopt) {
    static const struct option longopts[] = {
        { "dedup",   required_argument, 0, 'D' },
        { "dry-run", no_argument,       0, 'n' },
        { "help",    no_argument,       0, 'h' },
        { 0, 0, 0, 0 }
    };
    const char *dedup = "insert";
    int dry_run = 0;
    int c;
    while ((c = getopt_long(argc, argv, "h", longopts, NULL)) != -1) {
        switch (c) {
            case 'D': dedup   = optarg; break;
            case 'n': dry_run = 1;      break;
            case 'h': fputs(USAGE, stdout); return 0;
            default:  fputs(USAGE, stderr); return 2;
        }
    }
    if (optind >= argc) {
        fprintf(stderr, "ownsona restore: missing required <file> argument\n%s", USAGE);
        return 2;
    }
    const char *infile = argv[optind++];
    if (optind < argc) {
        fprintf(stderr, "ownsona restore: unexpected extra argument '%s'\n%s", argv[optind], USAGE);
        return 2;
    }

    char *buf = read_file(infile);
    if (buf == NULL) {
        fprintf(stderr, "ownsona restore: cannot read '%s'\n", infile);
        return 1;
    }
    cJSON *root = cJSON_Parse(buf);
    free(buf);
    if (root == NULL) {
        fprintf(stderr, "ownsona restore: '%s' is not valid JSON\n", infile);
        return 1;
    }

    /* Accept either the raw dump object {..,"memories":[...]} or a bare array. */
    cJSON *memories = cJSON_GetObjectItemCaseSensitive(root, "memories");
    if (!cJSON_IsArray(memories))
        memories = cJSON_IsArray(root) ? root : NULL;
    if (memories == NULL) {
        fprintf(stderr, "ownsona restore: no \"memories\" array found in '%s'\n", infile);
        cJSON_Delete(root);
        return 1;
    }

    ownsona_config_t cli_overrides = {0};
    cli_overrides.server_url = (char *) gopt->server_override;
    cli_overrides.token      = (char *) gopt->token_override;
    ownsona_config_t cfg = {0};
    if (ownsona_config_load(gopt->config_path, &cli_overrides, &cfg) != 0) {
        cJSON_Delete(root);
        return 1;
    }

    long total = 0, skipped_deleted = 0, skipped_no_text = 0;
    long to_restore = 0, keep_pending = 0;
    long restored = 0, errors = 0;
    long keep_applied = 0, keep_no_secret = 0, keep_errors = 0;

    cJSON *items = cJSON_CreateArray();
    char keep_chars[RESTORE_BATCH];
    int batch_n = 0;

    cJSON *m;
    cJSON_ArrayForEach(m, memories) {
        total++;
        if (!cJSON_IsObject(m)) {
            skipped_no_text++;
            continue;
        }
        const char *del = jstr(m, "deleted_at");
        if (del != NULL && del[0] != '\0') {
            skipped_deleted++;
            continue;
        }
        const char *text = jstr(m, "text");
        if (text == NULL || text[0] == '\0') {
            skipped_no_text++;
            continue;
        }

        to_restore++;
        const char *kp = jstr(m, "keep");
        const char kch = (kp != NULL && kp[0] != '\0') ? kp[0] : 'U';
        if (kch == 'Y' || kch == 'N')
            keep_pending++;

        if (dry_run)
            continue;

        cJSON *it = cJSON_CreateObject();
        cJSON_AddStringToObject(it, "text", text);
        cJSON *tags = cJSON_GetObjectItemCaseSensitive(m, "tags");
        if (cJSON_IsArray(tags))
            cJSON_AddItemToObject(it, "tags", cJSON_Duplicate(tags, 1));
        const char *prov = jstr(m, "source_provider");
        if (prov != NULL)
            cJSON_AddStringToObject(it, "source_provider", prov);
        cJSON *imp = cJSON_GetObjectItemCaseSensitive(m, "importance");
        if (cJSON_IsNumber(imp))
            cJSON_AddNumberToObject(it, "importance", imp->valuedouble);
        const char *cm = jstr(m, "capture_mode");
        if (cm != NULL)
            cJSON_AddStringToObject(it, "capture_mode", cm);
        const char *sid = jstr(m, "session_id");
        if (sid != NULL)
            cJSON_AddStringToObject(it, "session_id", sid);
        const char *exp = jstr(m, "expires_at");
        if (exp != NULL)
            cJSON_AddStringToObject(it, "expires_at", exp);
        const char *lca = jstr(m, "last_confirmed_at");
        if (lca != NULL)
            cJSON_AddStringToObject(it, "last_confirmed_at", lca);

        cJSON_AddItemToArray(items, it);
        keep_chars[batch_n] = kch;
        batch_n++;

        if (batch_n == RESTORE_BATCH) {
            flush_batch(&cfg, items, keep_chars, batch_n, dedup,
                        &restored, &errors, &keep_applied, &keep_no_secret, &keep_errors);
            items = cJSON_CreateArray();
            batch_n = 0;
        }
    }

    if (!dry_run && batch_n > 0)
        flush_batch(&cfg, items, keep_chars, batch_n, dedup,
                    &restored, &errors, &keep_applied, &keep_no_secret, &keep_errors);
    else
        cJSON_Delete(items);   /* empty / unused leftover array */

    cJSON_Delete(root);
    ownsona_config_free(&cfg);

    printf("%ld memories in dump\n", total);
    if (skipped_deleted > 0)
        printf("  %ld skipped (soft-deleted)\n", skipped_deleted);
    if (skipped_no_text > 0)
        printf("  %ld skipped (no text)\n", skipped_no_text);

    if (dry_run) {
        printf("would restore %ld (re-applying keep on %ld); run without --dry-run to apply\n",
               to_restore, keep_pending);
        return 0;
    }

    printf("restored %ld, errors %ld\n", restored, errors);
    if (keep_applied > 0)
        printf("keep re-applied on %ld\n", keep_applied);
    if (keep_errors > 0)
        printf("keep failed on %ld\n", keep_errors);
    if (keep_no_secret > 0)
        printf("keep NOT re-applied on %ld (admin_secret not configured)\n", keep_no_secret);
    return errors == 0 ? 0 : 1;
}
