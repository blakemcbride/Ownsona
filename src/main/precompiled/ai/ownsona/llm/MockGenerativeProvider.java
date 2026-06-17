package ai.ownsona.llm;

/**
 * Deterministic in-memory {@link GenerativeProvider} for tests and local
 * dry-runs.  Returns a fixed canned reply (or one supplied by the test),
 * so the consolidation orchestration can be exercised without a network
 * call or an API key.
 */
public final class MockGenerativeProvider implements GenerativeProvider {

    private final String cannedReply;

    public MockGenerativeProvider() {
        this("{\"merge\": false}");
    }

    public MockGenerativeProvider(String cannedReply) {
        this.cannedReply = cannedReply;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return cannedReply;
    }

    @Override
    public String modelName() {
        return "mock-generative";
    }
}
