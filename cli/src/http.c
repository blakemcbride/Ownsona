/*
 * http.c --- thin libcurl wrapper for the OwnSona CLI.
 *
 * One job: HTTPS POST a JSON body with a bearer token, return the body
 * and HTTP status.  Connection reuse is not a concern --- the CLI makes
 * one request per invocation.
 */
#include "ownsona.h"

#include <curl/curl.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- buffer-into-malloc callback used by CURLOPT_WRITEFUNCTION ----- */

typedef struct {
    char  *data;
    size_t size;
    size_t cap;
} buf_t;

static size_t write_cb(char *ptr, size_t size, size_t nmemb, void *userdata) {
    buf_t *b = (buf_t *) userdata;
    const size_t added = size * nmemb;
    const size_t need  = b->size + added + 1;
    if (need > b->cap) {
        size_t new_cap = b->cap == 0 ? 4096 : b->cap;
        while (new_cap < need)
            new_cap *= 2;
        char *resized = realloc(b->data, new_cap);
        if (resized == NULL)
            return 0;  /* signals failure to libcurl */
        b->data = resized;
        b->cap  = new_cap;
    }
    memcpy(b->data + b->size, ptr, added);
    b->size += added;
    b->data[b->size] = '\0';
    return added;
}

/* ----- lifecycle ----------------------------------------------------- */

void ownsona_http_global_init(void) {
    curl_global_init(CURL_GLOBAL_DEFAULT);
}

void ownsona_http_global_cleanup(void) {
    curl_global_cleanup();
}

void ownsona_http_response_free(ownsona_http_response_t *resp) {
    if (resp == NULL)
        return;
    free(resp->body);
    resp->body = NULL;
    resp->body_len = 0;
    resp->http_status = 0;
}

/* ----- the POST ------------------------------------------------------ */

int ownsona_http_post_json(const ownsona_config_t *cfg,
                           const char *json_body,
                           ownsona_http_response_t *resp) {
    memset(resp, 0, sizeof *resp);

    CURL *curl = curl_easy_init();
    if (curl == NULL) {
        fprintf(stderr, "ownsona: curl_easy_init failed\n");
        return 1;
    }

    buf_t buf = {0};

    /* Build Authorization header.  64-char bearer fits in ~96 bytes;
     * still allocate generously. */
    const size_t auth_sz = strlen(cfg->token) + 32;
    char *auth_hdr = malloc(auth_sz);
    if (auth_hdr == NULL)
        ownsona_die(2, "out of memory");
    snprintf(auth_hdr, auth_sz, "Authorization: Bearer %s", cfg->token);

    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    headers = curl_slist_append(headers, "Accept: application/json");
    headers = curl_slist_append(headers, auth_hdr);

    curl_easy_setopt(curl, CURLOPT_URL,            cfg->server_url);
    curl_easy_setopt(curl, CURLOPT_POST,           1L);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS,     json_body);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER,     headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION,  write_cb);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA,      &buf);
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(curl, CURLOPT_USERAGENT,      "ownsona-cli/" OWNSONA_VERSION);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT,        60L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 15L);
    /* TLS: trust the system root store.  libcurl figures this out on
     * Linux and macOS automatically; on MSYS2 UCRT64 it uses the bundled
     * CA store.  No explicit CAINFO needed unless the user wants to
     * override --- not exposed in v1. */

    const CURLcode rc = curl_easy_perform(curl);
    if (rc != CURLE_OK) {
        fprintf(stderr, "ownsona: HTTP request failed: %s\n",
                curl_easy_strerror(rc));
        free(buf.data);
        free(auth_hdr);
        curl_slist_free_all(headers);
        curl_easy_cleanup(curl);
        return 1;
    }

    long status = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
    resp->http_status = status;
    resp->body        = buf.data;     /* may be NULL on a 0-byte response */
    resp->body_len    = buf.size;

    free(auth_hdr);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    return 0;
}
