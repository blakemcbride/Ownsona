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

    /**
     * Shared secret that authorizes the CLI-only {@code set_keep}
     * operation.  Optional: when unset (null/empty) the keep flag cannot
     * be changed by anyone --- {@code set_keep} fails closed.  The same
     * value lives in the CLI's config file ({@code admin_secret}).
     */
    public static final String ADMIN_SECRET;

    // ------------------------------------------------------------------
    // Generative LLM seam (separate from embeddings).  All optional: when
    // LLM_API_KEY is unset, the generative provider is not constructed and
    // every feature that depends on it (consolidation) stays off.  This is
    // a SECOND vendor seam --- configure it independently of EMBEDDING_*.
    // ------------------------------------------------------------------

    /** API key for the generative endpoint.  Null/empty disables the seam. */
    public static final String LLM_API_KEY;

    /** Generative model id (e.g. an OpenAI-compatible chat model).  Required iff LLM_API_KEY is set. */
    public static final String LLM_MODEL;

    /** OpenAI-compatible chat-completions endpoint URL.  Required iff LLM_API_KEY is set. */
    public static final String LLM_ENDPOINT;

    /** Generative provider label, recorded only for logging/provenance. */
    public static final String LLM_PROVIDER;

    /** True when a generative provider is configured (LLM_API_KEY present). */
    public static final boolean LLM_ENABLED;

    // ------------------------------------------------------------------
    // Consolidation ("sleep") job (Tier 3).  Off unless explicitly enabled
    // AND a generative provider is configured.  The SCHEDULE lives in
    // backend/CronTasks/crontab (Kiss Cron runs it); these knobs only
    // gate and tune what a fired run does.
    // ------------------------------------------------------------------

    /** Runtime switch for the consolidation job.  Default false. */
    public static final boolean CONSOLIDATION_ENABLED;

    /**
     * Cosine cutoff for the near-duplicate clusters the consolidation job
     * will merge.  Deliberately high (default 0.95): the unattended job
     * should only merge near-identical rows.
     */
    public static final double CONSOLIDATION_THRESHOLD;

    /** Max clusters merged per run, to bound LLM cost.  Default 25. */
    public static final int CONSOLIDATION_MAX_GROUPS;

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
        ADMIN_SECRET          = optional("OwnsonaAdminSecret",   null);

        LLM_API_KEY   = optional("LLM_API_KEY",  null);
        LLM_MODEL     = optional("LLM_MODEL",    null);
        LLM_ENDPOINT  = optional("LLM_ENDPOINT", null);
        LLM_PROVIDER  = optional("LLM_PROVIDER", "openai");
        LLM_ENABLED   = LLM_API_KEY != null && !LLM_API_KEY.isEmpty();

        CONSOLIDATION_ENABLED    = parseBool("CONSOLIDATION_ENABLED", false);
        CONSOLIDATION_THRESHOLD  = parseDouble("CONSOLIDATION_THRESHOLD", 0.95);
        CONSOLIDATION_MAX_GROUPS = parseInt("CONSOLIDATION_MAX_GROUPS", 25);
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

    private static double parseDouble(String name, double def) {
        final String v = lookup(name);
        if (v == null || v.isEmpty())
            return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("application.ini key " + name + " must be a number (got: " + v + ")");
        }
    }

    private static boolean parseBool(String name, boolean def) {
        final String v = lookup(name);
        if (v == null || v.isEmpty())
            return def;
        return "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
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
