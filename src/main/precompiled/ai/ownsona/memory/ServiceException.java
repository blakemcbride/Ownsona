package ai.ownsona.memory;

/**
 * Service-layer error with a stable code that maps to one of the spec's
 * documented error codes (INVALID_INPUT, NOT_FOUND, SECRET_REJECTED, ...).
 *
 * <p>The MCP layer turns these into {@code isError: true} tool results so
 * the calling LLM sees the failure and can react.
 */
public final class ServiceException extends RuntimeException {

    public static final String INVALID_INPUT     = "INVALID_INPUT";
    public static final String NOT_FOUND         = "NOT_FOUND";
    public static final String SECRET_REJECTED   = "SECRET_REJECTED";
    public static final String EMBEDDING_ERROR   = "EMBEDDING_ERROR";
    public static final String DATABASE_ERROR    = "DATABASE_ERROR";
    public static final String LIMIT_EXCEEDED    = "LIMIT_EXCEEDED";

    private final String code;

    public ServiceException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ServiceException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
