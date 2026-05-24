package ai.ownsona;

import org.kissweb.restServer.MainServlet;

/**
 * Application configuration for the Ownsona MCP server.
 *
 * <p>All values are resolved from {@code application.ini} (via
 * {@link MainServlet#getEnvironment(String)}) once at class load.  Required
 * keys throw {@link IllegalStateException} when missing, which causes the
 * servlet to fail to load --- the desired behavior, since the server cannot
 * do its job without embedding credentials and login credentials.
 *
 * <p>OAuth 2.1 resource-server and authorization-server settings
 * ({@code OAuthAuthorizationServer}, {@code OAuthResourceIdentifier},
 * {@code OAuthAsEnabled}, etc.) are not read here --- Kiss's
 * {@link org.kissweb.oauth.OAuthConfig} and
 * {@link org.kissweb.oauth.as.AuthorizationServerConfig} read them
 * directly from {@code application.ini}.
 *
 * <p>The Postgres connection itself is not configured here; it uses Kiss's
 * {@code DatabaseHost / DatabasePort / DatabaseName / DatabaseUser /
 * DatabasePassword} keys via {@link MainServlet#openNewConnection()}.
 */
public final class Config {

    /** API key for the embeddings endpoint (provider-agnostic). */
    public static final String EMBEDDING_API_KEY;

    /**
     * Username the OAuth AS login page accepts.  Single-user server, so
     * one credential pair lives in {@code application.ini}.
     */
    public static final String OWNSONA_LOGIN_USERNAME;

    /**
     * Password the OAuth AS login page accepts.  Plaintext in
     * {@code application.ini}; the file is chmod 600 and already holds
     * the database password and the embedding API key, so the marginal
     * exposure is bounded.
     */
    public static final String OWNSONA_LOGIN_PASSWORD;

    /** User-id stamped on every memory in single-user mode. */
    public static final String OWNSONA_USER_ID;

    /** Embedding provider name, recorded on each row. */
    public static final String EMBEDDING_PROVIDER;

    /** Embedding model name, recorded on each row. */
    public static final String EMBEDDING_MODEL;

    /** Embedding vector dimensions. Must match the {@code vector(N)} column type. */
    public static final int EMBEDDING_DIMENSIONS;

    /** Default {@code limit} for the recall tool when the client doesn't specify one. */
    public static final int DEFAULT_RECALL_LIMIT;

    /** Hard cap on {@code limit} the client may request from any tool. */
    public static final int MAX_RECALL_LIMIT;

    /** Maximum chars in a single memory's text field. */
    public static final int MAX_TEXT_CHARS;

    /** Maximum items per {@code remember_batch} call. */
    public static final int MAX_BATCH_SIZE;

    static {
        EMBEDDING_API_KEY        = required("EMBEDDING_API_KEY");
        OWNSONA_LOGIN_USERNAME = required("OWNSONA_LOGIN_USERNAME");
        OWNSONA_LOGIN_PASSWORD = required("OWNSONA_LOGIN_PASSWORD");
        EMBEDDING_MODEL       = required("EMBEDDING_MODEL");
        EMBEDDING_DIMENSIONS  = requiredInt("EMBEDDING_DIMENSIONS");

        OWNSONA_USER_ID       = optional("OWNSONA_USER_ID",      "default");
        EMBEDDING_PROVIDER    = optional("EMBEDDING_PROVIDER",   "openai");
        DEFAULT_RECALL_LIMIT  = parseInt("DEFAULT_RECALL_LIMIT", 8);
        MAX_RECALL_LIMIT      = parseInt("MAX_RECALL_LIMIT",     50);
        MAX_TEXT_CHARS        = parseInt("MAX_TEXT_CHARS",       16_000);
        MAX_BATCH_SIZE        = parseInt("MAX_BATCH_SIZE",       200);
    }

    private static String required(String name) {
        final String v = lookup(name);
        if (v == null || v.isEmpty())
            throw new IllegalStateException("Required application.ini key " + name + " is not set");
        return v;
    }

    private static String optional(String name, String def) {
        final String v = lookup(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static int parseInt(String name, int def) {
        final String v = lookup(name);
        if (v == null || v.isEmpty())
            return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("application.ini key " + name + " must be an integer (got: " + v + ")");
        }
    }

    private static int requiredInt(String name) {
        final String v = required(name);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("application.ini key " + name + " must be an integer (got: " + v + ")");
        }
    }

    private static String lookup(String name) {
        final Object v = MainServlet.getEnvironment(name);
        return (v == null) ? null : v.toString();
    }

    private Config() {
    }
}
