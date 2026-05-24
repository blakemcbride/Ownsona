/*
 * main.c --- argv parsing + subcommand dispatch + shared output helpers.
 *
 * Top-level usage:
 *     ownsona [GLOBAL-OPTS] <subcommand> [SUBCOMMAND-OPTS] [ARGS...]
 *
 * Global options come BEFORE the subcommand name.  Subcommand options
 * come after.  This keeps each subcommand's flag namespace independent.
 *
 * Global opts:
 *     --config PATH       use this config file (OS-specific default;
 *                           Linux/BSD: ~/.config/ownsona/config.ini,
 *                           macOS:     ~/Library/Application Support/ownsona/config.ini,
 *                           Windows:   %LOCALAPPDATA%\ownsona\config.ini)
 *     --server URL        override server_url from config
 *     --token  STR        override bearer token from config
 *     --json              emit raw JSON instead of human-readable output
 *     -h, --help          show top-level help
 *     -V, --version       print version and exit
 */
#include "ownsona.h"

#include <getopt.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ===================================================================== */
/* shared output helpers                                                 */
/* ===================================================================== */

void ownsona_die(int exit_code, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs("ownsona: ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    exit(exit_code);
}

void ownsona_print_json(cJSON *result) {
    char *s = cJSON_Print(result);
    if (s == NULL) {
        fputs("(could not serialize result)\n", stderr);
        return;
    }
    fputs(s, stdout);
    fputc('\n', stdout);
    free(s);
}

static const char *opt_string(cJSON *obj, const char *key) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (!cJSON_IsString(v) || v->valuestring == NULL)
        return NULL;
    return v->valuestring;
}

static double opt_number(cJSON *obj, const char *key, double dflt) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (cJSON_IsNumber(v))
        return v->valuedouble;
    return dflt;
}

static long opt_long(cJSON *obj, const char *key, long dflt) {
    cJSON *v = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (cJSON_IsNumber(v))
        return (long) v->valuedouble;
    return dflt;
}

static void print_tags(cJSON *tags) {
    if (!cJSON_IsArray(tags) || cJSON_GetArraySize(tags) == 0)
        return;
    fputs("    tags:", stdout);
    cJSON *t;
    cJSON_ArrayForEach(t, tags) {
        if (cJSON_IsString(t) && t->valuestring != NULL)
            printf(" %s", t->valuestring);
    }
    fputc('\n', stdout);
}

/* Render one memory row (used by recall, list, search, candidates). */
static void print_one_memory(cJSON *m, bool show_score) {
    if (!cJSON_IsObject(m))
        return;
    const long   id    = opt_long(m, "id", -1);
    const char  *text  = opt_string(m, "text");
    if (show_score) {
        const double score = opt_number(m, "score", 0.0);
        printf("  [%ld] score=%.3f  %s\n", id, score, text ? text : "(no text)");
    } else {
        printf("  [%ld]  %s\n", id, text ? text : "(no text)");
    }
    print_tags(cJSON_GetObjectItemCaseSensitive(m, "tags"));
    const char *created = opt_string(m, "created_at");
    const char *updated = opt_string(m, "updated_at");
    const char *expires = opt_string(m, "expires_at");
    const char *confirmed = opt_string(m, "last_confirmed_at");
    const char *deleted = opt_string(m, "deleted_at");
    const char *forget_reason = opt_string(m, "forget_reason");
    const char *capture_mode = opt_string(m, "capture_mode");
    if (created && updated && strcmp(created, updated) == 0)
        printf("    at:        %s\n", created);
    else {
        if (created) printf("    created:   %s\n", created);
        if (updated) printf("    updated:   %s\n", updated);
    }
    if (confirmed)     printf("    confirmed: %s\n", confirmed);
    if (expires)       printf("    expires:   %s\n", expires);
    if (deleted)       printf("    deleted:   %s\n", deleted);
    if (forget_reason) printf("    reason:    %s\n", forget_reason);
    if (capture_mode)  printf("    capture:   %s\n", capture_mode);
}

void ownsona_print_human(cJSON *result) {
    if (!cJSON_IsObject(result)) {
        ownsona_print_json(result);
        return;
    }

    /* recall: { ok, query, matches: [...] } */
    cJSON *matches = cJSON_GetObjectItemCaseSensitive(result, "matches");
    if (cJSON_IsArray(matches)) {
        const int n = cJSON_GetArraySize(matches);
        printf("%d match%s\n", n, n == 1 ? "" : "es");
        cJSON *m;
        cJSON_ArrayForEach(m, matches)
            print_one_memory(m, /*show_score=*/true);
        return;
    }

    /* list_memories: { ok, memories: [...] } */
    cJSON *memories = cJSON_GetObjectItemCaseSensitive(result, "memories");
    if (cJSON_IsArray(memories)) {
        const int n = cJSON_GetArraySize(memories);
        printf("%d memor%s\n", n, n == 1 ? "y" : "ies");
        cJSON *m;
        cJSON_ArrayForEach(m, memories)
            print_one_memory(m, /*show_score=*/false);
        return;
    }

    /* build_context_prompt: { ok, prompt: "..." } */
    const char *prompt = opt_string(result, "prompt");
    if (prompt != NULL) {
        fputs(prompt, stdout);
        fputc('\n', stdout);
        return;
    }

    /* remember / update / confirm / forget: { ok, memory_id, message, candidates?, previously_corrected? } */
    const long   mid     = opt_long(result, "memory_id", -1);
    const char  *message = opt_string(result, "message");
    if (mid >= 0 || message != NULL) {
        if (message != NULL)
            printf("%s", message);
        if (mid >= 0)
            printf(" (id=%ld)", mid);
        fputc('\n', stdout);

        cJSON *candidates = cJSON_GetObjectItemCaseSensitive(result, "candidates");
        if (cJSON_IsArray(candidates) && cJSON_GetArraySize(candidates) > 0) {
            printf("near-duplicates of the new fact:\n");
            cJSON *m;
            cJSON_ArrayForEach(m, candidates)
                print_one_memory(m, /*show_score=*/true);
        }
        cJSON *prev = cJSON_GetObjectItemCaseSensitive(result, "previously_corrected");
        if (cJSON_IsArray(prev) && cJSON_GetArraySize(prev) > 0) {
            printf("previously corrected near-duplicates "
                   "(this fact was forgotten before --- check before re-adding):\n");
            cJSON *m;
            cJSON_ArrayForEach(m, prev)
                print_one_memory(m, /*show_score=*/true);
        }
        return;
    }

    /* remember_batch: { ok, results: [...], summary: {...} } */
    cJSON *results_arr = cJSON_GetObjectItemCaseSensitive(result, "results");
    cJSON *summary     = cJSON_GetObjectItemCaseSensitive(result, "summary");
    if (cJSON_IsArray(results_arr) && cJSON_IsObject(summary)) {
        printf("inserted=%ld  duplicates=%ld  errors=%ld  (total=%ld)\n",
               opt_long(summary, "inserted",   0),
               opt_long(summary, "duplicates", 0),
               opt_long(summary, "errors",     0),
               opt_long(summary, "total",      0));
        cJSON *r;
        cJSON_ArrayForEach(r, results_arr) {
            const long  ii = opt_long(r, "input_index", -1);
            cJSON *ok = cJSON_GetObjectItemCaseSensitive(r, "ok");
            if (cJSON_IsTrue(ok)) {
                printf("  [%ld] id=%ld  %s\n",
                       ii, opt_long(r, "memory_id", -1),
                       opt_string(r, "message") ? opt_string(r, "message") : "ok");
            } else {
                cJSON *e = cJSON_GetObjectItemCaseSensitive(r, "error");
                printf("  [%ld] FAILED  %s: %s\n", ii,
                       opt_string(e, "code")    ? opt_string(e, "code")    : "ERROR",
                       opt_string(e, "message") ? opt_string(e, "message") : "");
            }
        }
        return;
    }

    /* Fallback */
    ownsona_print_json(result);
}

/* ===================================================================== */
/* help text                                                             */
/* ===================================================================== */

static const char USAGE_TOP[] =
"usage: ownsona [GLOBAL-OPTS] <command> [OPTIONS] [ARGS...]\n"
"\n"
"Commands:\n"
"  add        store a new memory (remember)\n"
"  query      find memories by semantic similarity (recall)\n"
"  search     plain substring search over stored text\n"
"  list       list recent memories\n"
"  update     replace text/tags/etc on an existing memory\n"
"  confirm    mark a memory as still-current (refresh last_confirmed_at)\n"
"  forget     delete a memory (soft by default, hard with --hard)\n"
"  prompt     build an LLM prompt augmented with relevant facts\n"
"  import     bulk-load facts from a file (remember_batch)\n"
"  teach      extract facts from prose via an LLM and load them in bulk\n"
"  auth       manage OAuth credentials (login, status)\n"
"\n"
"Global options:\n"
"  --config PATH    use this config file (default is OS-specific:\n"
"                     Linux/BSD: ~/.config/ownsona/config.ini\n"
"                     macOS:     ~/Library/Application Support/ownsona/config.ini\n"
"                     Windows:   %LOCALAPPDATA%\\ownsona\\config.ini)\n"
"  --server URL     override server URL from config\n"
"  --token  TOKEN   override bearer token from config\n"
"  --json           emit raw JSON output instead of human-readable\n"
"  -h, --help       show this message\n"
"  -V, --version    print version and exit\n"
"\n"
"Run 'ownsona <command> --help' for command-specific help.\n";

/* ===================================================================== */
/* dispatch                                                              */
/* ===================================================================== */

typedef int (*cmd_fn_t)(int, char **, const ownsona_global_opts_t *);

typedef struct {
    const char *name;
    cmd_fn_t    fn;
} cmd_entry_t;

static const cmd_entry_t COMMANDS[] = {
    { "add",     cmd_add     },
    { "query",   cmd_query   },
    { "search",  cmd_search  },
    { "list",    cmd_list    },
    { "update",  cmd_update  },
    { "confirm", cmd_confirm },
    { "forget",  cmd_forget  },
    { "prompt",  cmd_prompt  },
    { "import",  cmd_import  },
    { "teach",   cmd_teach   },
    { "auth",    cmd_auth    },
    { NULL,      NULL        }
};

static cmd_fn_t lookup_command(const char *name) {
    for (const cmd_entry_t *c = COMMANDS; c->name != NULL; c++)
        if (strcmp(c->name, name) == 0)
            return c->fn;
    return NULL;
}

int main(int argc, char **argv) {
    /* Parse global options up to (but not consuming) the subcommand name.
     * getopt_long's POSIX-style "+" prefix would help but isn't portable
     * to every libc; we hand-roll it by scanning until a non-flag arg
     * appears, then handing the rest to the subcommand. */
    ownsona_global_opts_t gopt = {0};
    int i = 1;
    while (i < argc) {
        const char *a = argv[i];
        if (strcmp(a, "--config") == 0 && i + 1 < argc) {
            gopt.config_path = argv[++i];
        } else if (strncmp(a, "--config=", 9) == 0) {
            gopt.config_path = a + 9;
        } else if (strcmp(a, "--server") == 0 && i + 1 < argc) {
            gopt.server_override = argv[++i];
        } else if (strncmp(a, "--server=", 9) == 0) {
            gopt.server_override = a + 9;
        } else if (strcmp(a, "--token") == 0 && i + 1 < argc) {
            gopt.token_override = argv[++i];
        } else if (strncmp(a, "--token=", 8) == 0) {
            gopt.token_override = a + 8;
        } else if (strcmp(a, "--json") == 0) {
            gopt.json_output = true;
        } else if (strcmp(a, "-h") == 0 || strcmp(a, "--help") == 0) {
            fputs(USAGE_TOP, stdout);
            return 0;
        } else if (strcmp(a, "-V") == 0 || strcmp(a, "--version") == 0) {
            printf("ownsona %s\n", OWNSONA_VERSION);
            return 0;
        } else if (strcmp(a, "--") == 0) {
            i++;
            break;
        } else if (a[0] == '-') {
            fprintf(stderr, "ownsona: unknown global option '%s'\n\n%s", a, USAGE_TOP);
            return 2;
        } else {
            break;  /* this is the subcommand name */
        }
        i++;
    }

    if (i >= argc) {
        fputs(USAGE_TOP, stderr);
        return 2;
    }

    const char *cmd_name = argv[i++];
    cmd_fn_t cmd = lookup_command(cmd_name);
    if (cmd == NULL) {
        fprintf(stderr, "ownsona: unknown command '%s'\n\n%s", cmd_name, USAGE_TOP);
        return 2;
    }

    /* Re-pack argv so getopt_long inside the subcommand starts from
     * argv[0] = command name, argv[1..] = its own args. */
    const int sub_argc = argc - i + 1;
    char **sub_argv = malloc((sub_argc + 1) * sizeof *sub_argv);
    if (sub_argv == NULL)
        ownsona_die(2, "out of memory");
    sub_argv[0] = (char *) cmd_name;
    for (int j = 1; j < sub_argc; j++)
        sub_argv[j] = argv[i + j - 1];
    sub_argv[sub_argc] = NULL;

    /* getopt_long uses static state; reset it for the subcommand. */
    optind = 1;
#ifdef __APPLE__
    optreset = 1;
#endif

    ownsona_http_global_init();
    const int rc = cmd(sub_argc, sub_argv, &gopt);
    ownsona_http_global_cleanup();

    free(sub_argv);
    return rc;
}
