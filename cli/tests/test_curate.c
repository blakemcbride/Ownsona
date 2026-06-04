/*
 * test_curate.c --- standalone unit tests for the curation helpers
 * (ownsona_parse_ids and ownsona_ensure_trailing_period).
 *
 * Built and run by `make test-units`.  No server or network needed.
 */
#include "ownsona.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- stubs for symbols curate.c references but these tests don't use ---- */
void ownsona_die(int exit_code, const char *fmt, ...) {
    (void) fmt;
    fprintf(stderr, "ownsona_die(%d)\n", exit_code);
    exit(exit_code);
}
cJSON *ownsona_mcp_call(const ownsona_config_t *cfg, const char *tool,
                        cJSON *arguments, char **err) {
    (void) cfg; (void) tool; (void) err;
    if (arguments) cJSON_Delete(arguments);
    return NULL;
}

static int failures = 0;
#define CHECK(cond, msg) do { \
    if (!(cond)) { printf("FAIL: %s\n", (msg)); failures++; } \
    else         { printf("ok:   %s\n", (msg)); } \
} while (0)

/* Assert parse_ids(spec) over `known` yields exactly `expect`. */
static void expect_ids(const char *spec, const long *known, size_t kn,
                       const long *expect, size_t en, const char *msg) {
    size_t n = 0;
    char *err = NULL;
    long *got = ownsona_parse_ids(spec, known, kn, &n, &err);
    int ok = (err == NULL) && (n == en);
    for (size_t i = 0; ok && i < n; i++)
        if (got[i] != expect[i])
            ok = 0;
    CHECK(ok, msg);
    free(got);
    free(err);
}

static void expect_parse_error(const char *spec, const long *known, size_t kn,
                               const char *msg) {
    size_t n = 99;
    char *err = NULL;
    long *got = ownsona_parse_ids(spec, known, kn, &n, &err);
    CHECK(got == NULL && err != NULL && n == 0, msg);
    free(got);
    free(err);
}

static void expect_period(const char *in, const char *want, const char *msg) {
    char *got = ownsona_ensure_trailing_period(in);
    CHECK(got != NULL && strcmp(got, want) == 0, msg);
    free(got);
}

int main(void) {
    const long known[] = { 2, 3, 5, 7, 11 };
    const size_t kn = sizeof known / sizeof known[0];

    expect_ids(NULL,      known, kn, known, kn, "NULL spec selects all");
    expect_ids("",        known, kn, known, kn, "empty spec selects all");
    expect_ids("5",       known, kn, (long[]){5}, 1, "single id");
    expect_ids("3-7",     known, kn, (long[]){3,5,7}, 3, "range keeps only existing");
    expect_ids("5,2,11",  known, kn, (long[]){2,5,11}, 3, "list is sorted");
    expect_ids("$",       known, kn, (long[]){11}, 1, "$ is max id");
    expect_ids("7-$",     known, kn, (long[]){7,11}, 2, "x-$ to end");
    expect_ids("100",     known, kn, NULL, 0, "non-existent id yields empty");
    expect_ids("1-3,$",   known, kn, (long[]){2,3,11}, 3, "combination with $");
    expect_ids("5,5,5",   known, kn, (long[]){5}, 1, "duplicates collapse");

    expect_parse_error("abc",  known, kn, "non-numeric token errors");
    expect_parse_error("5-",   known, kn, "dangling range errors");
    expect_ids("5,,7", known, kn, (long[]){5,7}, 2, "empty list elements are skipped");

    expect_period("hello",   "hello.",  "appends missing period");
    expect_period("done.",   "done.",   "keeps existing period");
    expect_period("hi   ",   "hi.",     "drops trailing space, adds period");
    expect_period("",        "",        "empty stays empty");

    if (failures == 0)
        printf("\nAll curate unit tests passed.\n");
    else
        printf("\n%d failure(s).\n", failures);
    return failures == 0 ? 0 : 1;
}
