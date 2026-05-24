/*
 * oauth.c --- OAuth 2.1 (auth code + PKCE) client for the OwnSona CLI.
 *
 * Two operations:
 *   1. ownsona_oauth_bootstrap()           --- one-time setup invoked by
 *      `ownsona auth login`.  Discovers the AS via RFC 9728, does
 *      dynamic client registration (RFC 7591), runs the auth code flow
 *      with PKCE (RFC 7636) and RFC 8707 resource indicators, persists
 *      the resulting tokens to the config file.
 *   2. ownsona_oauth_ensure_fresh_token()  --- called from http.c before
 *      every MCP request.  Trades the saved refresh token for a fresh
 *      access token whenever the cached one is close to expiry,
 *      rotating both and rewriting the config file atomically.
 *
 * The browser-open + localhost-callback pieces are POSIX-socket based
 * with a Winsock shim.  No background threads --- the listener accepts
 * exactly one connection (the redirect from the browser) and then
 * closes.
 */
#include "ownsona.h"

#include "cJSON.h"
#include <curl/curl.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  include <io.h>
#  define close_socket closesocket
   typedef int socklen_t_compat;
#else
#  include <arpa/inet.h>
#  include <netinet/in.h>
#  include <sys/socket.h>
#  include <sys/stat.h>
#  include <unistd.h>
#  define close_socket close
#endif

#ifndef PATH_MAX
#  define PATH_MAX 4096
#endif

/* ---------------------------------------------------------------------- */
/* small helpers (mirrors of the trim/xstrdup used elsewhere)             */
/* ---------------------------------------------------------------------- */

static char *xstrdup(const char *s) {
    if (s == NULL)
        return NULL;
    char *out = strdup(s);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    return out;
}

static void xfree(char **p) {
    if (p == NULL || *p == NULL)
        return;
    free(*p);
    *p = NULL;
}

static long long now_epoch(void) {
    return (long long) time(NULL);
}

/* ---------------------------------------------------------------------- */
/* base64url                                                              */
/* ---------------------------------------------------------------------- */

/* base64url alphabet --- 64 chars, indexed; the NUL byte at the end of
 * the string literal is unused but harmless. */
static const char B64URL_ALPHABET[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/* Encode `in` (len bytes) as base64url, no padding.  Returns malloc'd. */
static char *b64url_encode(const unsigned char *in, size_t len) {
    /* 3 bytes -> 4 chars; ceil(len*4/3) and +1 for NUL. */
    const size_t out_cap = ((len + 2) / 3) * 4 + 1;
    char *out = malloc(out_cap);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    size_t o = 0;
    size_t i = 0;
    while (i + 3 <= len) {
        const unsigned v = ((unsigned)in[i] << 16)
                         | ((unsigned)in[i+1] << 8)
                         |  (unsigned)in[i+2];
        out[o++] = B64URL_ALPHABET[(v >> 18) & 0x3F];
        out[o++] = B64URL_ALPHABET[(v >> 12) & 0x3F];
        out[o++] = B64URL_ALPHABET[(v >>  6) & 0x3F];
        out[o++] = B64URL_ALPHABET[ v        & 0x3F];
        i += 3;
    }
    if (i < len) {
        unsigned v = (unsigned)in[i] << 16;
        if (i + 1 < len)
            v |= (unsigned)in[i+1] << 8;
        out[o++] = B64URL_ALPHABET[(v >> 18) & 0x3F];
        out[o++] = B64URL_ALPHABET[(v >> 12) & 0x3F];
        if (i + 1 < len)
            out[o++] = B64URL_ALPHABET[(v >> 6) & 0x3F];
    }
    out[o] = '\0';
    return out;
}

/* ---------------------------------------------------------------------- */
/* URL building helpers                                                   */
/* ---------------------------------------------------------------------- */

/* URL-encode `s` for use in a query string.  Returns malloc'd. */
static char *url_encode(CURL *curl, const char *s) {
    char *enc = curl_easy_escape(curl, s, 0);
    if (enc == NULL)
        ownsona_die(2, "url_encode failed");
    char *out = xstrdup(enc);
    curl_free(enc);
    return out;
}

/* Extract the {scheme}://{host}[:port] prefix from `url`.  Returns malloc'd. */
static char *origin_of(const char *url) {
    /* find "://" */
    const char *sep = strstr(url, "://");
    if (sep == NULL)
        return NULL;
    const char *host = sep + 3;
    const char *end  = strchr(host, '/');
    const size_t len = (end == NULL) ? strlen(url) : (size_t)(end - url);
    char *out = malloc(len + 1);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    memcpy(out, url, len);
    out[len] = '\0';
    return out;
}

/* ---------------------------------------------------------------------- */
/* libcurl wrappers --- minimal duplicate of http.c, but for non-bearer   */
/* requests (discovery, register, token, refresh).                        */
/* ---------------------------------------------------------------------- */

typedef struct {
    char  *data;
    size_t size;
    size_t cap;
} buf_t;

static size_t buf_write_cb(char *ptr, size_t size, size_t nmemb, void *userdata) {
    buf_t *b = (buf_t *) userdata;
    const size_t added = size * nmemb;
    const size_t need  = b->size + added + 1;
    if (need > b->cap) {
        size_t new_cap = b->cap == 0 ? 4096 : b->cap;
        while (new_cap < need)
            new_cap *= 2;
        char *resized = realloc(b->data, new_cap);
        if (resized == NULL)
            return 0;
        b->data = resized;
        b->cap  = new_cap;
    }
    memcpy(b->data + b->size, ptr, added);
    b->size += added;
    b->data[b->size] = '\0';
    return added;
}

/* GET URL; on success returns malloc'd body (status in *status). */
static char *http_get(const char *url, long *status, char **err) {
    *status = 0;
    *err    = NULL;
    CURL *c = curl_easy_init();
    if (c == NULL) {
        *err = xstrdup("curl_easy_init failed");
        return NULL;
    }
    buf_t buf = {0};
    curl_easy_setopt(c, CURLOPT_URL,            url);
    curl_easy_setopt(c, CURLOPT_HTTPGET,        1L);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION,  buf_write_cb);
    curl_easy_setopt(c, CURLOPT_WRITEDATA,      &buf);
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(c, CURLOPT_USERAGENT,      "ownsona-cli/" OWNSONA_VERSION);
    curl_easy_setopt(c, CURLOPT_TIMEOUT,        30L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 10L);
    const CURLcode rc = curl_easy_perform(c);
    if (rc != CURLE_OK) {
        free(buf.data);
        {
            char buf[512];
            snprintf(buf, sizeof buf, "GET %s failed: %s", url, curl_easy_strerror(rc));
            *err = xstrdup(buf);
        }
        curl_easy_cleanup(c);
        return NULL;
    }
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, status);
    curl_easy_cleanup(c);
    return buf.data == NULL ? xstrdup("") : buf.data;
}

/* POST application/x-www-form-urlencoded body to URL; returns malloc'd. */
static char *http_post_form(const char *url, const char *body,
                            long *status, char **err) {
    *status = 0;
    *err    = NULL;
    CURL *c = curl_easy_init();
    if (c == NULL) {
        *err = xstrdup("curl_easy_init failed");
        return NULL;
    }
    buf_t buf = {0};
    struct curl_slist *hdr = NULL;
    hdr = curl_slist_append(hdr, "Content-Type: application/x-www-form-urlencoded");
    hdr = curl_slist_append(hdr, "Accept: application/json");
    curl_easy_setopt(c, CURLOPT_URL,            url);
    curl_easy_setopt(c, CURLOPT_POST,           1L);
    curl_easy_setopt(c, CURLOPT_POSTFIELDS,     body);
    curl_easy_setopt(c, CURLOPT_HTTPHEADER,     hdr);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION,  buf_write_cb);
    curl_easy_setopt(c, CURLOPT_WRITEDATA,      &buf);
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(c, CURLOPT_USERAGENT,      "ownsona-cli/" OWNSONA_VERSION);
    curl_easy_setopt(c, CURLOPT_TIMEOUT,        30L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 10L);
    const CURLcode rc = curl_easy_perform(c);
    if (rc != CURLE_OK) {
        free(buf.data);
        curl_slist_free_all(hdr);
        {
            char buf[512];
            snprintf(buf, sizeof buf, "POST %s failed: %s", url, curl_easy_strerror(rc));
            *err = xstrdup(buf);
        }
        curl_easy_cleanup(c);
        return NULL;
    }
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, status);
    curl_slist_free_all(hdr);
    curl_easy_cleanup(c);
    return buf.data == NULL ? xstrdup("") : buf.data;
}

/* POST application/json body. */
static char *http_post_json(const char *url, const char *json,
                            long *status, char **err) {
    *status = 0;
    *err    = NULL;
    CURL *c = curl_easy_init();
    if (c == NULL) {
        *err = xstrdup("curl_easy_init failed");
        return NULL;
    }
    buf_t buf = {0};
    struct curl_slist *hdr = NULL;
    hdr = curl_slist_append(hdr, "Content-Type: application/json");
    hdr = curl_slist_append(hdr, "Accept: application/json");
    curl_easy_setopt(c, CURLOPT_URL,            url);
    curl_easy_setopt(c, CURLOPT_POST,           1L);
    curl_easy_setopt(c, CURLOPT_POSTFIELDS,     json);
    curl_easy_setopt(c, CURLOPT_HTTPHEADER,     hdr);
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION,  buf_write_cb);
    curl_easy_setopt(c, CURLOPT_WRITEDATA,      &buf);
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(c, CURLOPT_USERAGENT,      "ownsona-cli/" OWNSONA_VERSION);
    curl_easy_setopt(c, CURLOPT_TIMEOUT,        30L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 10L);
    const CURLcode rc = curl_easy_perform(c);
    if (rc != CURLE_OK) {
        free(buf.data);
        curl_slist_free_all(hdr);
        {
            char buf[512];
            snprintf(buf, sizeof buf, "POST %s failed: %s", url, curl_easy_strerror(rc));
            *err = xstrdup(buf);
        }
        curl_easy_cleanup(c);
        return NULL;
    }
    curl_easy_getinfo(c, CURLINFO_RESPONSE_CODE, status);
    curl_slist_free_all(hdr);
    curl_easy_cleanup(c);
    return buf.data == NULL ? xstrdup("") : buf.data;
}

/* ---------------------------------------------------------------------- */
/* config persistence (atomic rewrite of cfg->source_path)                */
/* ---------------------------------------------------------------------- */

static const char *OAUTH_KEYS[] = {
    "oauth_client_id",
    "oauth_refresh_token",
    "oauth_access_token",
    "oauth_access_token_expires_at",
    "oauth_authorization_server",
    "oauth_resource",
    NULL,
};

static const char *value_for_key(const ownsona_config_t *cfg, const char *key,
                                 char *scratch, size_t scratch_sz) {
    if (strcmp(key, "oauth_client_id") == 0)
        return cfg->oauth_client_id;
    if (strcmp(key, "oauth_refresh_token") == 0)
        return cfg->oauth_refresh_token;
    if (strcmp(key, "oauth_access_token") == 0)
        return cfg->oauth_access_token;
    if (strcmp(key, "oauth_access_token_expires_at") == 0) {
        snprintf(scratch, scratch_sz, "%lld", cfg->oauth_access_token_expires_at);
        return scratch;
    }
    if (strcmp(key, "oauth_authorization_server") == 0)
        return cfg->oauth_authorization_server;
    if (strcmp(key, "oauth_resource") == 0)
        return cfg->oauth_resource;
    return NULL;
}

/* Strip leading whitespace; return pointer into the same buffer. */
static const char *skip_ws(const char *s) {
    while (*s == ' ' || *s == '\t')
        s++;
    return s;
}

/* True if `line` (no leading whitespace) starts with "key" followed by
 * optional whitespace then '='. */
static int line_matches_key(const char *line, const char *key) {
    const size_t klen = strlen(key);
    if (strncmp(line, key, klen) != 0)
        return 0;
    const char *p = line + klen;
    while (*p == ' ' || *p == '\t')
        p++;
    return *p == '=';
}

/*
 * Read the config file, replace any oauth_* lines we own, append any
 * keys not yet present, write tmp + atomic rename.  Returns 0 on success.
 * Lines we don't recognize (other keys, comments, blank lines, sections)
 * are preserved verbatim.
 */
static int persist_oauth_to_config(const ownsona_config_t *cfg) {
    if (cfg->source_path == NULL || *cfg->source_path == '\0') {
        fprintf(stderr, "ownsona: cannot persist OAuth tokens --- no config "
                "file path is known.  Set --config or $OWNSONA_CONFIG so the "
                "CLI knows where to write.\n");
        return 1;
    }

    /* Read existing file (may not exist on first run). */
    FILE *in = fopen(cfg->source_path, "r");
    char *existing = NULL;
    size_t existing_len = 0;
    if (in != NULL) {
        fseek(in, 0, SEEK_END);
        long sz = ftell(in);
        if (sz < 0)
            sz = 0;
        fseek(in, 0, SEEK_SET);
        existing = malloc((size_t)sz + 1);
        if (existing == NULL)
            ownsona_die(2, "out of memory");
        existing_len = fread(existing, 1, (size_t)sz, in);
        existing[existing_len] = '\0';
        fclose(in);
    }

    /* Track which keys we've already substituted so we don't append a
     * duplicate at the bottom. */
    int seen[8] = {0};

    /* Build new content in memory. */
    buf_t out = {0};
    char scratch[64];

    if (existing != NULL) {
        char *line = existing;
        while (line != NULL && *line != '\0') {
            char *nl = strchr(line, '\n');
            const size_t llen = (nl == NULL) ? strlen(line) : (size_t)(nl - line);
            /* Make a stack-friendly copy for matching. */
            char tmp[2048];
            const size_t copy_len = llen < sizeof tmp - 1 ? llen : sizeof tmp - 1;
            memcpy(tmp, line, copy_len);
            tmp[copy_len] = '\0';
            const char *trimmed = skip_ws(tmp);
            int matched = 0;
            for (int i = 0; OAUTH_KEYS[i] != NULL; i++) {
                if (line_matches_key(trimmed, OAUTH_KEYS[i])) {
                    const char *val = value_for_key(cfg, OAUTH_KEYS[i],
                                                    scratch, sizeof scratch);
                    if (val != NULL) {
                        char repl[3072];
                        snprintf(repl, sizeof repl, "%s = %s\n",
                                 OAUTH_KEYS[i], val);
                        buf_write_cb(repl, 1, strlen(repl), &out);
                    }
                    seen[i] = 1;
                    matched = 1;
                    break;
                }
            }
            if (!matched) {
                /* Pass through the line verbatim, including the trailing \n
                 * if present. */
                size_t pass = llen + (nl != NULL ? 1 : 0);
                buf_write_cb(line, 1, pass, &out);
            }
            if (nl == NULL)
                break;
            line = nl + 1;
        }
        free(existing);
    }

    /* Append any oauth keys that weren't already in the file. */
    int appended_header = 0;
    for (int i = 0; OAUTH_KEYS[i] != NULL; i++) {
        if (seen[i])
            continue;
        const char *val = value_for_key(cfg, OAUTH_KEYS[i], scratch, sizeof scratch);
        if (val == NULL || *val == '\0')
            continue;
        if (!appended_header) {
            const char *hdr =
                "\n# --- OAuth state (auto-managed by `ownsona auth login`) ---\n";
            buf_write_cb((char *)hdr, 1, strlen(hdr), &out);
            appended_header = 1;
        }
        char line[3072];
        snprintf(line, sizeof line, "%s = %s\n", OAUTH_KEYS[i], val);
        buf_write_cb(line, 1, strlen(line), &out);
    }

    /* Atomic write: tmp + rename. */
    char tmp_path[PATH_MAX];
    snprintf(tmp_path, sizeof tmp_path, "%s.tmp", cfg->source_path);
    FILE *fp = fopen(tmp_path, "w");
    if (fp == NULL) {
        fprintf(stderr, "ownsona: cannot write %s: %s\n", tmp_path, strerror(errno));
        free(out.data);
        return 1;
    }
    if (out.size > 0)
        fwrite(out.data, 1, out.size, fp);
    fflush(fp);
#ifndef _WIN32
    /* Best-effort fsync; ignore failure (e.g. on tmpfs). */
    fsync(fileno(fp));
#endif
    fclose(fp);
    free(out.data);

#ifdef _WIN32
    /* Windows rename() fails if destination exists. */
    remove(cfg->source_path);
#endif
    if (rename(tmp_path, cfg->source_path) != 0) {
        fprintf(stderr, "ownsona: cannot rename %s -> %s: %s\n",
                tmp_path, cfg->source_path, strerror(errno));
        remove(tmp_path);
        return 1;
    }

#ifndef _WIN32
    /* Make sure the config is owner-only readable; it holds tokens. */
    chmod(cfg->source_path, 0600);
#endif
    return 0;
}

/* ---------------------------------------------------------------------- */
/* AS discovery (RFC 9728 + RFC 8414)                                     */
/* ---------------------------------------------------------------------- */

typedef struct {
    char *authorization_server;   /* issuer URL                     */
    char *authorization_endpoint;
    char *token_endpoint;
    char *registration_endpoint;
} as_metadata_t;

static void as_metadata_free(as_metadata_t *m) {
    if (m == NULL)
        return;
    xfree(&m->authorization_server);
    xfree(&m->authorization_endpoint);
    xfree(&m->token_endpoint);
    xfree(&m->registration_endpoint);
}

/*
 * Resolve the AS issuer URL.  If cfg->oauth_authorization_server is
 * already set, use it verbatim.  Otherwise GET the protected-resource
 * metadata at <server_origin>/.well-known/oauth-protected-resource
 * and read authorization_servers[0].
 */
static int discover_authorization_server(const ownsona_config_t *cfg,
                                         char **issuer_out) {
    if (cfg->oauth_authorization_server != NULL && *cfg->oauth_authorization_server != '\0') {
        *issuer_out = xstrdup(cfg->oauth_authorization_server);
        return 0;
    }
    char *origin = origin_of(cfg->server_url);
    if (origin == NULL) {
        fprintf(stderr, "ownsona: server_url '%s' is malformed\n", cfg->server_url);
        return 1;
    }
    char url[1024];
    snprintf(url, sizeof url, "%s/.well-known/oauth-protected-resource", origin);
    free(origin);

    long status = 0;
    char *err = NULL;
    char *body = http_get(url, &status, &err);
    if (body == NULL) {
        fprintf(stderr, "ownsona: AS discovery failed: %s\n", err ? err : "(no body)");
        free(err);
        return 1;
    }
    if (status != 200) {
        fprintf(stderr, "ownsona: %s returned HTTP %ld --- is OAuth configured "
                "on this server?\n", url, status);
        free(body);
        return 1;
    }
    cJSON *doc = cJSON_Parse(body);
    free(body);
    if (doc == NULL) {
        fprintf(stderr, "ownsona: AS discovery: response was not valid JSON\n");
        return 1;
    }
    cJSON *arr = cJSON_GetObjectItemCaseSensitive(doc, "authorization_servers");
    if (!cJSON_IsArray(arr) || cJSON_GetArraySize(arr) < 1) {
        fprintf(stderr, "ownsona: AS discovery: no authorization_servers in metadata\n");
        cJSON_Delete(doc);
        return 1;
    }
    cJSON *first = cJSON_GetArrayItem(arr, 0);
    if (!cJSON_IsString(first) || first->valuestring == NULL) {
        fprintf(stderr, "ownsona: AS discovery: authorization_servers[0] is not a string\n");
        cJSON_Delete(doc);
        return 1;
    }
    *issuer_out = xstrdup(first->valuestring);
    cJSON_Delete(doc);
    return 0;
}

/* Given the issuer URL, fetch the RFC 8414 metadata document and pull
 * out the endpoints we need. */
static int fetch_as_metadata(const char *issuer, as_metadata_t *out) {
    memset(out, 0, sizeof *out);
    out->authorization_server = xstrdup(issuer);
    char url[1024];
    snprintf(url, sizeof url, "%s/.well-known/oauth-authorization-server", issuer);
    long status = 0;
    char *err = NULL;
    char *body = http_get(url, &status, &err);
    if (body == NULL) {
        fprintf(stderr, "ownsona: AS metadata fetch failed: %s\n", err ? err : "(no body)");
        free(err);
        as_metadata_free(out);
        return 1;
    }
    if (status != 200) {
        fprintf(stderr, "ownsona: %s returned HTTP %ld\n", url, status);
        free(body);
        as_metadata_free(out);
        return 1;
    }
    cJSON *doc = cJSON_Parse(body);
    free(body);
    if (doc == NULL) {
        fprintf(stderr, "ownsona: AS metadata: response was not valid JSON\n");
        as_metadata_free(out);
        return 1;
    }
    cJSON *ae  = cJSON_GetObjectItemCaseSensitive(doc, "authorization_endpoint");
    cJSON *te  = cJSON_GetObjectItemCaseSensitive(doc, "token_endpoint");
    cJSON *re  = cJSON_GetObjectItemCaseSensitive(doc, "registration_endpoint");
    if (!cJSON_IsString(ae) || !cJSON_IsString(te)) {
        fprintf(stderr, "ownsona: AS metadata missing authorization_endpoint or "
                "token_endpoint\n");
        cJSON_Delete(doc);
        as_metadata_free(out);
        return 1;
    }
    out->authorization_endpoint = xstrdup(ae->valuestring);
    out->token_endpoint         = xstrdup(te->valuestring);
    if (cJSON_IsString(re))
        out->registration_endpoint = xstrdup(re->valuestring);
    cJSON_Delete(doc);
    return 0;
}

/* ---------------------------------------------------------------------- */
/* dynamic client registration                                            */
/* ---------------------------------------------------------------------- */

static int register_client(const char *reg_endpoint, const char *redirect_uri,
                           char **client_id_out) {
    cJSON *body = cJSON_CreateObject();
    cJSON_AddStringToObject(body, "client_name", "ownsona-cli");
    cJSON *redirects = cJSON_AddArrayToObject(body, "redirect_uris");
    cJSON_AddItemToArray(redirects, cJSON_CreateString(redirect_uri));
    cJSON *grants = cJSON_AddArrayToObject(body, "grant_types");
    cJSON_AddItemToArray(grants, cJSON_CreateString("authorization_code"));
    cJSON_AddItemToArray(grants, cJSON_CreateString("refresh_token"));
    cJSON *responses = cJSON_AddArrayToObject(body, "response_types");
    cJSON_AddItemToArray(responses, cJSON_CreateString("code"));
    cJSON_AddStringToObject(body, "token_endpoint_auth_method", "none");

    char *json = cJSON_PrintUnformatted(body);
    cJSON_Delete(body);

    long status = 0;
    char *err   = NULL;
    char *resp  = http_post_json(reg_endpoint, json, &status, &err);
    free(json);
    if (resp == NULL) {
        fprintf(stderr, "ownsona: dynamic client registration failed: %s\n",
                err ? err : "(no body)");
        free(err);
        return 1;
    }
    if (status != 200 && status != 201) {
        fprintf(stderr, "ownsona: registration endpoint returned HTTP %ld: %s\n",
                status, resp);
        free(resp);
        return 1;
    }
    cJSON *doc = cJSON_Parse(resp);
    free(resp);
    if (doc == NULL) {
        fprintf(stderr, "ownsona: registration response was not valid JSON\n");
        return 1;
    }
    cJSON *cid = cJSON_GetObjectItemCaseSensitive(doc, "client_id");
    if (!cJSON_IsString(cid)) {
        fprintf(stderr, "ownsona: registration response missing client_id\n");
        cJSON_Delete(doc);
        return 1;
    }
    *client_id_out = xstrdup(cid->valuestring);
    cJSON_Delete(doc);
    return 0;
}

/* ---------------------------------------------------------------------- */
/* PKCE                                                                   */
/* ---------------------------------------------------------------------- */

static void make_pkce(char **verifier_out, char **challenge_out) {
    unsigned char raw[48];   /* 48 bytes -> 64 base64url chars */
    if (RAND_bytes(raw, sizeof raw) != 1)
        ownsona_die(2, "RAND_bytes failed");
    *verifier_out = b64url_encode(raw, sizeof raw);

    unsigned char digest[SHA256_DIGEST_LENGTH];
    SHA256((const unsigned char *)*verifier_out, strlen(*verifier_out), digest);
    *challenge_out = b64url_encode(digest, sizeof digest);
}

static char *random_state(void) {
    unsigned char raw[16];
    if (RAND_bytes(raw, sizeof raw) != 1)
        ownsona_die(2, "RAND_bytes failed");
    return b64url_encode(raw, sizeof raw);
}

/* ---------------------------------------------------------------------- */
/* localhost callback listener                                            */
/* ---------------------------------------------------------------------- */

#ifdef _WIN32
static void wsa_startup_once(void) {
    static int done = 0;
    if (done) return;
    WSADATA wsa;
    WSAStartup(MAKEWORD(2, 2), &wsa);
    done = 1;
}
#endif

/* Bind a TCP socket on 127.0.0.1 to a random free port.  Returns the
 * socket fd; *port_out receives the bound port. */
static int bind_localhost(int *port_out) {
#ifdef _WIN32
    wsa_startup_once();
#endif
    const int fd = (int) socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, (const char *)&yes, sizeof yes);
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof addr);
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port        = 0;   /* kernel picks */
    if (bind(fd, (struct sockaddr *)&addr, sizeof addr) != 0) {
        close_socket(fd);
        return -1;
    }
    if (listen(fd, 1) != 0) {
        close_socket(fd);
        return -1;
    }
    struct sockaddr_in bound;
    socklen_t blen = sizeof bound;
    if (getsockname(fd, (struct sockaddr *)&bound, &blen) != 0) {
        close_socket(fd);
        return -1;
    }
    *port_out = ntohs(bound.sin_port);
    return fd;
}

/* Read the first HTTP request line from the redirected browser, extract
 * the query-string params we care about (code, state, error).  Writes a
 * minimal 200 OK back so the browser tab shows "you can close this".
 * On success returns 0 with code/state malloc'd (either may be NULL on
 * an error= return).  Times out after 120s.
 */
static int accept_callback(int listen_fd, char **code_out, char **state_out,
                           char **error_out) {
    *code_out = *state_out = *error_out = NULL;

    /* Wait for the connection with a generous timeout. */
    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(listen_fd, &rfds);
    struct timeval tv = { 120, 0 };
    const int sel = select(listen_fd + 1, &rfds, NULL, NULL, &tv);
    if (sel == 0) {
        fprintf(stderr, "ownsona: timed out waiting for the OAuth redirect\n");
        return 1;
    }
    if (sel < 0) {
        fprintf(stderr, "ownsona: select failed: %s\n", strerror(errno));
        return 1;
    }
    struct sockaddr_in peer;
    socklen_t plen = sizeof peer;
    const int conn = (int) accept(listen_fd, (struct sockaddr *)&peer, &plen);
    if (conn < 0) {
        fprintf(stderr, "ownsona: accept failed: %s\n", strerror(errno));
        return 1;
    }

    char req[4096];
    const ssize_t n = recv(conn, req, sizeof req - 1, 0);
    if (n <= 0) {
        close_socket(conn);
        fprintf(stderr, "ownsona: empty request on callback socket\n");
        return 1;
    }
    req[n] = '\0';

    /* Parse first line: "GET /cb?code=...&state=... HTTP/1.1" */
    char *space1 = strchr(req, ' ');
    if (space1 == NULL) {
        close_socket(conn);
        return 1;
    }
    char *path = space1 + 1;
    char *space2 = strchr(path, ' ');
    if (space2 != NULL)
        *space2 = '\0';

    /* Drop the path; we want the query string. */
    char *q = strchr(path, '?');
    if (q != NULL) {
        q++;
        char *tok = q;
        while (tok != NULL && *tok != '\0') {
            char *amp = strchr(tok, '&');
            if (amp != NULL)
                *amp = '\0';
            char *eq = strchr(tok, '=');
            if (eq != NULL) {
                *eq = '\0';
                char *raw_val = eq + 1;
                /* URL-decode (just %XX, no '+' handling --- redirect URI
                 * params from the AS don't use form encoding). */
                char *dec = malloc(strlen(raw_val) + 1);
                if (dec == NULL)
                    ownsona_die(2, "out of memory");
                char *dp = dec;
                for (const char *sp = raw_val; *sp != '\0'; sp++) {
                    if (*sp == '%' && sp[1] && sp[2]) {
                        char hex[3] = { sp[1], sp[2], 0 };
                        *dp++ = (char) strtol(hex, NULL, 16);
                        sp += 2;
                    } else {
                        *dp++ = *sp;
                    }
                }
                *dp = '\0';
                if (strcmp(tok, "code") == 0)
                    *code_out = dec;
                else if (strcmp(tok, "state") == 0)
                    *state_out = dec;
                else if (strcmp(tok, "error") == 0)
                    *error_out = dec;
                else
                    free(dec);
            }
            tok = (amp == NULL) ? NULL : amp + 1;
        }
    }

    /* Write a friendly confirmation page. */
    const char *page =
        "HTTP/1.1 200 OK\r\n"
        "Content-Type: text/html; charset=utf-8\r\n"
        "Connection: close\r\n"
        "\r\n"
        "<!DOCTYPE html><html><head><title>OwnSona CLI</title></head>"
        "<body style='font-family:sans-serif;max-width:480px;margin:80px auto'>"
        "<h1>You're signed in.</h1>"
        "<p>The CLI received the authorization code.  You can close this tab "
        "and return to your terminal.</p></body></html>\r\n";
    send(conn, page, (int) strlen(page), 0);
    close_socket(conn);
    return 0;
}

static int open_browser(const char *url) {
    /* Best-effort.  If the user is headless they can copy the URL we
     * also printed to stderr. */
#ifdef _WIN32
    char cmd[4096];
    snprintf(cmd, sizeof cmd, "start \"\" \"%s\"", url);
    return system(cmd);
#elif defined(__APPLE__)
    char cmd[4096];
    snprintf(cmd, sizeof cmd, "open '%s' >/dev/null 2>&1", url);
    return system(cmd);
#else
    char cmd[4096];
    snprintf(cmd, sizeof cmd, "xdg-open '%s' >/dev/null 2>&1 &", url);
    return system(cmd);
#endif
}

/* ---------------------------------------------------------------------- */
/* token-endpoint exchange (shared by bootstrap + refresh)                */
/* ---------------------------------------------------------------------- */

/*
 * POST to the AS's token endpoint, parse the JSON, populate the
 * relevant cfg fields (access_token, refresh_token, expires_at).
 * Returns 0 on success.
 */
static int parse_token_response(const char *body, ownsona_config_t *cfg) {
    cJSON *doc = cJSON_Parse(body);
    if (doc == NULL) {
        fprintf(stderr, "ownsona: token response was not valid JSON\n");
        return 1;
    }
    cJSON *err_obj = cJSON_GetObjectItemCaseSensitive(doc, "error");
    if (cJSON_IsString(err_obj)) {
        cJSON *desc = cJSON_GetObjectItemCaseSensitive(doc, "error_description");
        fprintf(stderr, "ownsona: token endpoint error '%s'%s%s\n",
                err_obj->valuestring,
                cJSON_IsString(desc) ? ": " : "",
                cJSON_IsString(desc) ? desc->valuestring : "");
        cJSON_Delete(doc);
        return 1;
    }
    cJSON *at = cJSON_GetObjectItemCaseSensitive(doc, "access_token");
    cJSON *rt = cJSON_GetObjectItemCaseSensitive(doc, "refresh_token");
    cJSON *ex = cJSON_GetObjectItemCaseSensitive(doc, "expires_in");
    if (!cJSON_IsString(at)) {
        fprintf(stderr, "ownsona: token response missing access_token\n");
        cJSON_Delete(doc);
        return 1;
    }
    xfree(&cfg->oauth_access_token);
    cfg->oauth_access_token = xstrdup(at->valuestring);
    if (cJSON_IsString(rt)) {
        xfree(&cfg->oauth_refresh_token);
        cfg->oauth_refresh_token = xstrdup(rt->valuestring);
    }
    if (cJSON_IsNumber(ex))
        cfg->oauth_access_token_expires_at = now_epoch() + (long long) ex->valuedouble;
    else
        cfg->oauth_access_token_expires_at = now_epoch() + 3600;
    cJSON_Delete(doc);
    return 0;
}

/* ---------------------------------------------------------------------- */
/* public API: bootstrap                                                  */
/* ---------------------------------------------------------------------- */

int ownsona_oauth_bootstrap(ownsona_config_t *cfg) {
    if (cfg->server_url == NULL || *cfg->server_url == '\0') {
        fprintf(stderr, "ownsona: server_url must be set before `auth login`\n");
        return 1;
    }
    if (cfg->source_path == NULL || *cfg->source_path == '\0') {
        fprintf(stderr, "ownsona: cannot run `auth login` without a config file "
                "path (set --config or $OWNSONA_CONFIG, or copy "
                "cli/config.ini.example to its default location first)\n");
        return 1;
    }

    /* 1) Resolve AS issuer + endpoints. */
    char *issuer = NULL;
    if (discover_authorization_server(cfg, &issuer) != 0)
        return 1;
    as_metadata_t md = {0};
    if (fetch_as_metadata(issuer, &md) != 0) {
        free(issuer);
        return 1;
    }
    if (md.registration_endpoint == NULL) {
        fprintf(stderr, "ownsona: AS does not advertise registration_endpoint; "
                "manual client_id required (not yet supported)\n");
        free(issuer);
        as_metadata_free(&md);
        return 1;
    }

    /* 2) Bind localhost listener for the redirect. */
    int port = 0;
    const int listen_fd = bind_localhost(&port);
    if (listen_fd < 0) {
        fprintf(stderr, "ownsona: could not bind a localhost callback port\n");
        free(issuer);
        as_metadata_free(&md);
        return 1;
    }
    char redirect_uri[64];
    snprintf(redirect_uri, sizeof redirect_uri, "http://127.0.0.1:%d/cb", port);

    /* 3) Dynamic client registration. */
    char *client_id = NULL;
    if (register_client(md.registration_endpoint, redirect_uri, &client_id) != 0) {
        close_socket(listen_fd);
        free(issuer);
        as_metadata_free(&md);
        return 1;
    }

    /* 4) Build authorize URL.  PKCE + state + RFC 8707 resource. */
    char *verifier = NULL, *challenge = NULL;
    make_pkce(&verifier, &challenge);
    char *state = random_state();
    const char *resource = (cfg->oauth_resource != NULL && *cfg->oauth_resource != '\0')
        ? cfg->oauth_resource : cfg->server_url;

    CURL *enc = curl_easy_init();
    if (enc == NULL)
        ownsona_die(2, "curl_easy_init failed");
    char *e_redirect  = url_encode(enc, redirect_uri);
    char *e_challenge = url_encode(enc, challenge);
    char *e_state     = url_encode(enc, state);
    char *e_resource  = url_encode(enc, resource);
    char *e_client    = url_encode(enc, client_id);
    curl_easy_cleanup(enc);

    char auth_url[3072];
    snprintf(auth_url, sizeof auth_url,
             "%s?response_type=code&client_id=%s&redirect_uri=%s"
             "&code_challenge=%s&code_challenge_method=S256"
             "&state=%s&resource=%s",
             md.authorization_endpoint,
             e_client, e_redirect, e_challenge, e_state, e_resource);
    free(e_redirect); free(e_challenge); free(e_state); free(e_resource); free(e_client);

    fprintf(stderr, "Opening browser for OAuth login.  If it doesn't appear, "
            "paste this URL:\n  %s\n", auth_url);
    open_browser(auth_url);

    /* 5) Wait for redirect, exchange code for tokens. */
    char *code = NULL, *recv_state = NULL, *recv_error = NULL;
    const int acc_rc = accept_callback(listen_fd, &code, &recv_state, &recv_error);
    close_socket(listen_fd);
    if (acc_rc != 0) {
        free(state); free(verifier); free(challenge);
        free(issuer); free(client_id);
        as_metadata_free(&md);
        return 1;
    }
    if (recv_error != NULL) {
        fprintf(stderr, "ownsona: AS returned error: %s\n", recv_error);
        free(code); free(recv_state); free(recv_error);
        free(state); free(verifier); free(challenge);
        free(issuer); free(client_id);
        as_metadata_free(&md);
        return 1;
    }
    if (code == NULL || recv_state == NULL || strcmp(recv_state, state) != 0) {
        fprintf(stderr, "ownsona: missing code or state mismatch on callback\n");
        free(code); free(recv_state); free(recv_error);
        free(state); free(verifier); free(challenge);
        free(issuer); free(client_id);
        as_metadata_free(&md);
        return 1;
    }
    free(recv_state); free(recv_error);

    /* 6) Token exchange. */
    enc = curl_easy_init();
    char *e_code     = url_encode(enc, code);
    char *e_redir2   = url_encode(enc, redirect_uri);
    char *e_verifier = url_encode(enc, verifier);
    char *e_client2  = url_encode(enc, client_id);
    char *e_resource2 = url_encode(enc, resource);
    curl_easy_cleanup(enc);

    char body[4096];
    snprintf(body, sizeof body,
             "grant_type=authorization_code&code=%s&redirect_uri=%s"
             "&code_verifier=%s&client_id=%s&resource=%s",
             e_code, e_redir2, e_verifier, e_client2, e_resource2);
    free(e_code); free(e_redir2); free(e_verifier); free(e_client2); free(e_resource2);

    long t_status = 0;
    char *t_err = NULL;
    char *t_body = http_post_form(md.token_endpoint, body, &t_status, &t_err);
    free(code); free(verifier); free(challenge); free(state);
    if (t_body == NULL) {
        fprintf(stderr, "ownsona: token endpoint POST failed: %s\n",
                t_err ? t_err : "(no body)");
        free(t_err);
        free(issuer); free(client_id);
        as_metadata_free(&md);
        return 1;
    }
    if (t_status != 200) {
        fprintf(stderr, "ownsona: token endpoint returned HTTP %ld: %s\n",
                t_status, t_body);
        free(t_body);
        free(issuer); free(client_id);
        as_metadata_free(&md);
        return 1;
    }

    /* 7) Populate cfg and persist. */
    xfree(&cfg->oauth_client_id);
    cfg->oauth_client_id = client_id;            /* hand off, don't free */
    xfree(&cfg->oauth_authorization_server);
    cfg->oauth_authorization_server = issuer;    /* hand off, don't free */
    if (cfg->oauth_resource == NULL || *cfg->oauth_resource == '\0') {
        xfree(&cfg->oauth_resource);
        cfg->oauth_resource = xstrdup(resource);
    }
    const int parse_rc = parse_token_response(t_body, cfg);
    free(t_body);
    as_metadata_free(&md);
    if (parse_rc != 0)
        return 1;

    if (persist_oauth_to_config(cfg) != 0)
        return 1;

    fprintf(stderr, "OAuth login complete.  Access token expires at %lld (Unix "
            "epoch); refresh token saved in %s.\n",
            cfg->oauth_access_token_expires_at, cfg->source_path);
    return 0;
}

/* ---------------------------------------------------------------------- */
/* public API: per-request refresh                                        */
/* ---------------------------------------------------------------------- */

int ownsona_oauth_ensure_fresh_token(ownsona_config_t *cfg) {
    /* Static-bearer mode: nothing to do. */
    if (cfg->token != NULL && *cfg->token != '\0')
        return 0;

    if (cfg->oauth_refresh_token == NULL || *cfg->oauth_refresh_token == '\0') {
        fprintf(stderr, "ownsona: no credentials configured.  Run "
                "`ownsona auth login` to authenticate.\n");
        return 1;
    }

    /* Cached access token still good? */
    if (cfg->oauth_access_token != NULL && *cfg->oauth_access_token != '\0'
        && cfg->oauth_access_token_expires_at > now_epoch() + 60) {
        return 0;
    }

    /* Trade the refresh token for a fresh access token. */
    if (cfg->oauth_authorization_server == NULL || cfg->oauth_client_id == NULL) {
        fprintf(stderr, "ownsona: OAuth state incomplete --- run "
                "`ownsona auth login` again.\n");
        return 1;
    }
    as_metadata_t md = {0};
    if (fetch_as_metadata(cfg->oauth_authorization_server, &md) != 0)
        return 1;

    const char *resource = (cfg->oauth_resource != NULL && *cfg->oauth_resource != '\0')
        ? cfg->oauth_resource : cfg->server_url;

    CURL *enc = curl_easy_init();
    char *e_rt       = url_encode(enc, cfg->oauth_refresh_token);
    char *e_cid      = url_encode(enc, cfg->oauth_client_id);
    char *e_resource = url_encode(enc, resource);
    curl_easy_cleanup(enc);

    char body[4096];
    snprintf(body, sizeof body,
             "grant_type=refresh_token&refresh_token=%s&client_id=%s&resource=%s",
             e_rt, e_cid, e_resource);
    free(e_rt); free(e_cid); free(e_resource);

    long status = 0;
    char *err = NULL;
    char *resp = http_post_form(md.token_endpoint, body, &status, &err);
    as_metadata_free(&md);
    if (resp == NULL) {
        fprintf(stderr, "ownsona: refresh failed: %s\n", err ? err : "(no body)");
        free(err);
        return 1;
    }
    if (status != 200) {
        fprintf(stderr, "ownsona: refresh returned HTTP %ld: %s\n  "
                "Run `ownsona auth login` to reauthenticate.\n", status, resp);
        free(resp);
        return 1;
    }
    const int rc = parse_token_response(resp, cfg);
    free(resp);
    if (rc != 0)
        return 1;
    return persist_oauth_to_config(cfg);
}
