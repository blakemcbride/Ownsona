/*
 * llm.c --- OpenAI-compatible chat-completion call used by `teach`.
 *
 * Vendor-neutral by configuration: any endpoint that exposes
 * /chat/completions in OpenAI's JSON shape works.  Defaults point at
 * OpenAI; the user can swap to OpenRouter, Anthropic via a compat
 * proxy, a local Ollama server, etc.
 *
 * Wire shape:
 *   request:
 *     POST {llm_base_url}/chat/completions
 *     Authorization: Bearer {llm_api_key}
 *     Content-Type: application/json
 *     { "model": "...",
 *       "messages": [ {"role":"system","content":"..."},
 *                     {"role":"user",  "content":"..."} ],
 *       "response_format": { "type": "json_object" } }
 *
 *   response (on success):
 *     { "choices": [
 *         { "message": { "role":"assistant","content":"<JSON-as-string>" } }
 *       ], ... }
 *
 *   The "content" field is itself a JSON string the LLM produced.  We
 *   parse that for the caller and return a cJSON tree.
 */
#include "ownsona.h"

#include <curl/curl.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
            return 0;
        b->data = resized;
        b->cap  = new_cap;
    }
    memcpy(b->data + b->size, ptr, added);
    b->size += added;
    b->data[b->size] = '\0';
    return added;
}

/* Build "{base}/chat/completions" with exactly one slash between. */
static char *build_endpoint(const char *base) {
    const size_t bl  = strlen(base);
    const bool trail = (bl > 0 && base[bl - 1] == '/');
    const size_t need = bl + (trail ? 0 : 1) + strlen("chat/completions") + 1;
    char *out = malloc(need);
    if (out == NULL)
        ownsona_die(2, "out of memory");
    snprintf(out, need, "%s%schat/completions", base, trail ? "" : "/");
    return out;
}

cJSON *ownsona_llm_chat(const ownsona_config_t *cfg,
                        const char *model_override,
                        const char *system_prompt,
                        const char *user_message,
                        char **err) {
    if (err != NULL)
        *err = NULL;
    if (cfg->llm_api_key == NULL || *cfg->llm_api_key == '\0') {
        if (err != NULL)
            *err = strdup("LLM api key not set "
                          "(use llm_api_key in config, OWNSONA_LLM_API_KEY env, "
                          "or --llm-key)");
        return NULL;
    }

    const char *model = (model_override && *model_override) ? model_override
                                                            : cfg->llm_model;

    /* Build the request body */
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "model", model);

    cJSON *messages = cJSON_AddArrayToObject(root, "messages");
    if (system_prompt != NULL) {
        cJSON *sys = cJSON_CreateObject();
        cJSON_AddStringToObject(sys, "role",    "system");
        cJSON_AddStringToObject(sys, "content", system_prompt);
        cJSON_AddItemToArray(messages, sys);
    }
    cJSON *usr = cJSON_CreateObject();
    cJSON_AddStringToObject(usr, "role",    "user");
    cJSON_AddStringToObject(usr, "content", user_message);
    cJSON_AddItemToArray(messages, usr);

    /* response_format=json_object lets us trust the content is JSON. */
    cJSON *rfmt = cJSON_AddObjectToObject(root, "response_format");
    cJSON_AddStringToObject(rfmt, "type", "json_object");

    char *body = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (body == NULL) {
        if (err != NULL) *err = strdup("failed to serialize LLM request");
        return NULL;
    }

    char *url = build_endpoint(cfg->llm_base_url);

    CURL *curl = curl_easy_init();
    if (curl == NULL) {
        if (err != NULL) *err = strdup("curl_easy_init failed");
        free(body); free(url);
        return NULL;
    }
    buf_t buf = {0};

    const size_t auth_sz = strlen(cfg->llm_api_key) + 32;
    char *auth = malloc(auth_sz);
    if (auth == NULL) ownsona_die(2, "out of memory");
    snprintf(auth, auth_sz, "Authorization: Bearer %s", cfg->llm_api_key);

    struct curl_slist *headers = NULL;
    headers = curl_slist_append(headers, "Content-Type: application/json");
    headers = curl_slist_append(headers, "Accept: application/json");
    headers = curl_slist_append(headers, auth);

    curl_easy_setopt(curl, CURLOPT_URL,           url);
    curl_easy_setopt(curl, CURLOPT_POST,          1L);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS,    body);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER,    headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_cb);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA,     &buf);
    curl_easy_setopt(curl, CURLOPT_USERAGENT,     "ownsona-cli/" OWNSONA_VERSION);
    /* Long timeout: gpt-4o on a 4KB chunk routinely takes 10-30s. */
    curl_easy_setopt(curl, CURLOPT_TIMEOUT,       300L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 30L);

    const CURLcode rc = curl_easy_perform(curl);
    long status = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);

    free(body);
    free(auth);
    free(url);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (rc != CURLE_OK) {
        if (err != NULL) {
            char ebuf[256];
            snprintf(ebuf, sizeof ebuf, "LLM request failed: %s",
                     curl_easy_strerror(rc));
            *err = strdup(ebuf);
        }
        free(buf.data);
        return NULL;
    }

    if (status == 401 || status == 403) {
        if (err != NULL) *err = strdup("LLM auth rejected (check llm_api_key)");
        free(buf.data);
        return NULL;
    }
    if (status == 429) {
        if (err != NULL) *err = strdup("LLM rate-limited (HTTP 429); wait and retry");
        free(buf.data);
        return NULL;
    }
    if (status >= 400) {
        if (err != NULL) {
            char ebuf[512];
            const char *peek = buf.data ? buf.data : "";
            snprintf(ebuf, sizeof ebuf,
                     "LLM returned HTTP %ld: %.300s",
                     status, peek);
            *err = strdup(ebuf);
        }
        free(buf.data);
        return NULL;
    }
    if (buf.data == NULL || buf.size == 0) {
        if (err != NULL) *err = strdup("LLM returned empty body");
        free(buf.data);
        return NULL;
    }

    /* Parse the envelope and pull out choices[0].message.content */
    cJSON *envelope = cJSON_Parse(buf.data);
    free(buf.data);
    if (envelope == NULL) {
        if (err != NULL) *err = strdup("LLM response was not valid JSON");
        return NULL;
    }
    cJSON *choices = cJSON_GetObjectItemCaseSensitive(envelope, "choices");
    if (!cJSON_IsArray(choices) || cJSON_GetArraySize(choices) == 0) {
        if (err != NULL) *err = strdup("LLM response had no choices");
        cJSON_Delete(envelope);
        return NULL;
    }
    cJSON *first = cJSON_GetArrayItem(choices, 0);
    cJSON *msg   = cJSON_GetObjectItemCaseSensitive(first, "message");
    cJSON *cont  = cJSON_GetObjectItemCaseSensitive(msg, "content");
    if (!cJSON_IsString(cont) || cont->valuestring == NULL) {
        if (err != NULL) *err = strdup("LLM response had no message.content string");
        cJSON_Delete(envelope);
        return NULL;
    }

    cJSON *result = cJSON_Parse(cont->valuestring);
    cJSON_Delete(envelope);
    if (result == NULL) {
        if (err != NULL)
            *err = strdup("LLM did not return valid JSON in message.content "
                          "(model may not honor response_format)");
        return NULL;
    }
    return result;
}
