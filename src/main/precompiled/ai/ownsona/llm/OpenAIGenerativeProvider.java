package ai.ownsona.llm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Generative provider that talks to an OpenAI-compatible
 * {@code /v1/chat/completions} endpoint.  Works with any compatible
 * server (OpenAI, Azure OpenAI, a local Ollama/OpenAI shim, etc.) ---
 * the endpoint, key, and model are all configured explicitly via the
 * {@code LLM_*} keys, with no built-in defaults.
 *
 * <p>Temperature is pinned to 0 so the background consolidation job's
 * merges are as reproducible as the model allows.
 */
public final class OpenAIGenerativeProvider implements GenerativeProvider {

    private static final Logger logger = LogManager.getLogger(OpenAIGenerativeProvider.class);

    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAIGenerativeProvider(String apiKey, String model, String endpoint) {
        if (apiKey == null || apiKey.isEmpty())
            throw new IllegalArgumentException("LLM_API_KEY is required when a generative provider is configured");
        if (model == null || model.isEmpty())
            throw new IllegalArgumentException("LLM_MODEL is required when a generative provider is configured");
        if (endpoint == null || endpoint.isEmpty())
            throw new IllegalArgumentException("LLM_ENDPOINT is required when a generative provider is configured");
        this.apiKey   = apiKey;
        this.model    = model;
        this.endpoint = endpoint;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) throws Exception {
        final long t0 = System.currentTimeMillis();

        final JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            final JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.put(sys);
        }
        final JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", userPrompt == null ? "" : userPrompt);
        messages.put(usr);

        final JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0);

        final URL url = URI.create(endpoint).toURL();
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        final int status = conn.getResponseCode();
        final String response;
        try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null)
                sb.append(line).append('\n');
            response = sb.toString();
        }

        if (status < 200 || status >= 300)
            throw new RuntimeException("LLM chat HTTP " + status + ": " + truncate(response, 500));

        final JSONObject parsed = new JSONObject(response);
        final JSONArray choices = parsed.getJSONArray("choices");
        if (choices.length() == 0)
            throw new RuntimeException("LLM chat returned no choices");
        final JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        final String content = message.getString("content");

        logger.info("LLM complete model={} ms={}", model, System.currentTimeMillis() - t0);
        return content == null ? "" : content;
    }

    @Override
    public String modelName() {
        return model;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max)
            return s;
        return s.substring(0, max) + "...";
    }
}
