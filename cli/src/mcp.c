/*
 * mcp.c --- MCP-protocol-aware wrapper around http.c.
 *
 * Wire format reminder (see ../OWNSONA_SPEC.md and Kiss's MCPServerBase):
 *
 *   request:
 *     POST /mcp  Authorization: Bearer <token>
 *     {"jsonrpc":"2.0","id":N,
 *      "method":"tools/call",
 *      "params":{"name":"<TOOL>","arguments":{...}}}
 *
 *   response (on success):
 *     {"jsonrpc":"2.0","id":N,
 *      "result":{"content":[{"type":"text","text":"<JSON tool output>"}],
 *                "isError":false?}}
 *
 *   The actual tool's structured output is a *JSON string* inside
 *   result.content[0].text.  We parse it here and hand the caller a
 *   plain cJSON object.  isError=true is surfaced as an error.
 */
#include "ownsona.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Build the outer JSON-RPC envelope.  Caller owns the returned string. */
static char *build_envelope(const char *tool_name, cJSON *arguments) {
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "jsonrpc", "2.0");
    cJSON_AddNumberToObject(root, "id",      1);
    cJSON_AddStringToObject(root, "method",  "tools/call");

    cJSON *params = cJSON_AddObjectToObject(root, "params");
    cJSON_AddStringToObject(params, "name", tool_name);
    if (arguments == NULL)
        arguments = cJSON_CreateObject();
    cJSON_AddItemToObject(params, "arguments", arguments);

    char *s = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return s;
}

/* Pull the user-facing error message out of a tools/call error response.
 * Returns a malloc'd string; never NULL.  Tolerates malformed shapes. */
static char *extract_error_text(cJSON *envelope) {
    /* Could be an envelope-level error: {"error":{"code":N,"message":"..."}} */
    cJSON *e = cJSON_GetObjectItemCaseSensitive(envelope, "error");
    if (cJSON_IsObject(e)) {
        cJSON *msg = cJSON_GetObjectItemCaseSensitive(e, "message");
        if (cJSON_IsString(msg) && msg->valuestring != NULL) {
            char *out = strdup(msg->valuestring);
            return out ? out : strdup("(error)");
        }
    }
    /* Or a tool-level error in result.content[0].text + result.isError. */
    cJSON *result = cJSON_GetObjectItemCaseSensitive(envelope, "result");
    if (cJSON_IsObject(result)) {
        cJSON *content = cJSON_GetObjectItemCaseSensitive(result, "content");
        if (cJSON_IsArray(content) && cJSON_GetArraySize(content) > 0) {
            cJSON *first = cJSON_GetArrayItem(content, 0);
            cJSON *text  = cJSON_GetObjectItemCaseSensitive(first, "text");
            if (cJSON_IsString(text) && text->valuestring != NULL) {
                char *out = strdup(text->valuestring);
                return out ? out : strdup("(error)");
            }
        }
    }
    return strdup("(unknown error: response shape was unexpected)");
}

cJSON *ownsona_mcp_call(const ownsona_config_t *cfg,
                        const char *tool_name,
                        cJSON *arguments,
                        char **err) {
    if (err != NULL)
        *err = NULL;

    char *json_body = build_envelope(tool_name, arguments);
    if (json_body == NULL) {
        if (err != NULL)
            *err = strdup("failed to build request body");
        return NULL;
    }

    ownsona_http_response_t resp = {0};
    const int http_rc = ownsona_http_post_json(cfg, json_body, &resp);
    free(json_body);
    if (http_rc != 0) {
        ownsona_http_response_free(&resp);
        if (err != NULL)
            *err = strdup("HTTP request failed");
        return NULL;
    }

    if (resp.http_status == 401 || resp.http_status == 403) {
        ownsona_http_response_free(&resp);
        if (err != NULL)
            *err = strdup("authentication rejected (check token)");
        return NULL;
    }
    if (resp.http_status >= 500) {
        if (err != NULL) {
            char buf[256];
            snprintf(buf, sizeof buf, "server error (HTTP %ld)", resp.http_status);
            *err = strdup(buf);
        }
        ownsona_http_response_free(&resp);
        return NULL;
    }

    if (resp.body == NULL || resp.body_len == 0) {
        ownsona_http_response_free(&resp);
        if (err != NULL)
            *err = strdup("empty response body");
        return NULL;
    }

    cJSON *envelope = cJSON_Parse(resp.body);
    if (envelope == NULL) {
        if (err != NULL) {
            const char *parse_at = cJSON_GetErrorPtr();
            char buf[256];
            snprintf(buf, sizeof buf,
                     "could not parse response as JSON near: %.80s",
                     parse_at ? parse_at : "(unknown)");
            *err = strdup(buf);
        }
        ownsona_http_response_free(&resp);
        return NULL;
    }
    ownsona_http_response_free(&resp);

    /* Inspect the JSON-RPC result. */
    cJSON *result = cJSON_GetObjectItemCaseSensitive(envelope, "result");
    if (!cJSON_IsObject(result)) {
        if (err != NULL)
            *err = extract_error_text(envelope);
        cJSON_Delete(envelope);
        return NULL;
    }

    /* Tool-level error: result.isError == true */
    cJSON *is_error = cJSON_GetObjectItemCaseSensitive(result, "isError");
    if (cJSON_IsBool(is_error) && cJSON_IsTrue(is_error)) {
        if (err != NULL)
            *err = extract_error_text(envelope);
        cJSON_Delete(envelope);
        return NULL;
    }

    /* Extract result.content[0].text (a string holding the actual tool
     * output as JSON).  The Kiss MCPServerBase always uses this shape. */
    cJSON *content = cJSON_GetObjectItemCaseSensitive(result, "content");
    if (!cJSON_IsArray(content) || cJSON_GetArraySize(content) == 0) {
        if (err != NULL)
            *err = strdup("response has no content blocks");
        cJSON_Delete(envelope);
        return NULL;
    }
    cJSON *first = cJSON_GetArrayItem(content, 0);
    cJSON *text  = cJSON_GetObjectItemCaseSensitive(first, "text");
    if (!cJSON_IsString(text) || text->valuestring == NULL) {
        if (err != NULL)
            *err = strdup("first content block is not a text payload");
        cJSON_Delete(envelope);
        return NULL;
    }

    cJSON *tool_result = cJSON_Parse(text->valuestring);
    cJSON_Delete(envelope);
    if (tool_result == NULL) {
        if (err != NULL)
            *err = strdup("could not parse inner tool result as JSON");
        return NULL;
    }

    /* The tool's own ok=false comes through with the same JSON shape:
     *   {"ok":false,"error":{"code":"...","message":"..."}}
     * Surface that as an error to the caller. */
    cJSON *ok = cJSON_GetObjectItemCaseSensitive(tool_result, "ok");
    if (cJSON_IsBool(ok) && cJSON_IsFalse(ok)) {
        cJSON *e = cJSON_GetObjectItemCaseSensitive(tool_result, "error");
        if (cJSON_IsObject(e)) {
            cJSON *msg  = cJSON_GetObjectItemCaseSensitive(e, "message");
            cJSON *code = cJSON_GetObjectItemCaseSensitive(e, "code");
            char buf[512];
            snprintf(buf, sizeof buf, "%s%s%s",
                     cJSON_IsString(code) && code->valuestring ? code->valuestring : "ERROR",
                     cJSON_IsString(msg)  && msg->valuestring  ? ": "              : "",
                     cJSON_IsString(msg)  && msg->valuestring  ? msg->valuestring  : "");
            if (err != NULL)
                *err = strdup(buf);
        } else if (err != NULL) {
            *err = strdup("tool returned ok:false with no error block");
        }
        cJSON_Delete(tool_result);
        return NULL;
    }

    return tool_result;
}
