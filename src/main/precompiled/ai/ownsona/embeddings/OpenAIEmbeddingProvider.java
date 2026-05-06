package ai.ownsona.embeddings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kissweb.json.JSONArray;
import org.kissweb.json.JSONObject;
import org.kissweb.restServer.MainServlet;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Embeds text via the OpenAI <code>/v1/embeddings</code>-style REST endpoint.
 *
 * <p>Endpoint, model, and dimensions are all configured explicitly in
 * {@code application.ini} --- there are no built-in defaults.  The
 * configured {@code EMBEDDING_DIMENSIONS} must match the
 * {@code vector(N)} column type in {@code sql/001_init.sql}.
 */
public final class OpenAIEmbeddingProvider implements EmbeddingProvider {

    private static final Logger logger = LogManager.getLogger(OpenAIEmbeddingProvider.class);

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final int    dimensions;

    public OpenAIEmbeddingProvider(String apiKey, String model, int dimensions) {
        if (apiKey == null || apiKey.isEmpty())
            throw new IllegalArgumentException("OpenAI API key is required");
        this.endpoint   = resolveEndpoint();
        this.apiKey     = apiKey;
        this.model      = model;
        this.dimensions = dimensions;
    }

    private static String resolveEndpoint() {
        final Object v = MainServlet.getEnvironment("EMBEDDING_ENDPOINT");
        if (v == null)
            throw new IllegalStateException("Required application.ini key EMBEDDING_ENDPOINT is not set");
        final String s = v.toString().trim();
        if (s.isEmpty())
            throw new IllegalStateException("Required application.ini key EMBEDDING_ENDPOINT is not set");
        return s;
    }

    @Override
    public float[] embed(String text) throws Exception {
        final List<String> single = new ArrayList<>(1);
        single.add(text);
        return embedBatch(single).get(0);
    }

    /**
     * Override the per-item fallback with a single batched HTTP call.
     * OpenAI's embeddings endpoint accepts an array of inputs and returns
     * an array of embeddings; one round-trip embeds the whole list.
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty())
            return new ArrayList<>();

        final long t0 = System.currentTimeMillis();
        int totalChars = 0;
        for (String t : texts)
            totalChars += (t == null ? 0 : t.length());

        final JSONArray inputs = new JSONArray();
        for (String t : texts)
            inputs.put(t == null ? "" : t);

        final JSONObject body = new JSONObject();
        body.put("input", inputs);
        body.put("model", model);
        body.put("dimensions", dimensions);

        final URL url = URI.create(endpoint).toURL();
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        // Larger payloads need more time; scale loosely with batch size.
        conn.setReadTimeout(Math.max(30_000, 1_000 + 1_000 * texts.size()));

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
                sb.append(line);
            response = sb.toString();
        }

        if (status < 200 || status >= 300)
            throw new RuntimeException("OpenAI embeddings HTTP " + status + ": " + truncate(response, 500));

        final JSONObject parsed = new JSONObject(response);
        final JSONArray data = parsed.getJSONArray("data");
        if (data.length() != texts.size())
            throw new RuntimeException("OpenAI returned " + data.length() + " embeddings for " + texts.size() + " inputs");

        // OpenAI's data array is ordered to match input order, but the spec
        // says clients should rely on the per-item `index` field.  Sort by
        // index defensively.
        final List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++)
            out.add(null);
        for (int i = 0; i < data.length(); i++) {
            final JSONObject row = data.getJSONObject(i);
            final int idx = row.has("index") ? row.getInt("index") : i;
            final JSONArray vec = row.getJSONArray("embedding");
            if (vec.length() != dimensions)
                throw new RuntimeException("OpenAI returned " + vec.length() + " dims, expected " + dimensions);
            final float[] v = new float[vec.length()];
            for (int k = 0; k < vec.length(); k++)
                v[k] = vec.getFloat(k);
            if (idx < 0 || idx >= out.size())
                throw new RuntimeException("OpenAI returned invalid index " + idx);
            out.set(idx, v);
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == null)
                throw new RuntimeException("OpenAI did not return an embedding for input index " + i);
        }

        logger.info("OpenAI embedBatch model={} dims={} count={} totalChars={} ms={}",
                model, dimensions, texts.size(), totalChars, System.currentTimeMillis() - t0);
        return out;
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max)
            return s;
        return s.substring(0, max) + "...";
    }
}
