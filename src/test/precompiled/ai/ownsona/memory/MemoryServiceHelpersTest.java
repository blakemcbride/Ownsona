package ai.ownsona.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for MemoryService's package-private validation/serialization
 * helpers added in Phase 1A (capture_mode + session_id provenance).
 */
class MemoryServiceHelpersTest {

    // -------------------------------------------------------------------
    // validateCaptureMode
    // -------------------------------------------------------------------

    @Test
    void captureModeNullPassesThrough() {
        assertNull(MemoryService.validateCaptureMode(null));
    }

    @Test
    void captureModeEmptyOrWhitespaceTreatedAsNull() {
        assertNull(MemoryService.validateCaptureMode(""));
        assertNull(MemoryService.validateCaptureMode("   "));
    }

    @Test
    void captureModeExplicitAndInferredAccepted() {
        assertEquals("explicit", MemoryService.validateCaptureMode("explicit"));
        assertEquals("inferred", MemoryService.validateCaptureMode("inferred"));
        // Surrounding whitespace tolerated; result is the trimmed value.
        assertEquals("explicit", MemoryService.validateCaptureMode("  explicit  "));
    }

    @Test
    void captureModeRejectsUnknownValue() {
        final ServiceException e = assertThrows(ServiceException.class,
                () -> MemoryService.validateCaptureMode("guessed"));
        assertEquals(ServiceException.INVALID_INPUT, e.getCode());
    }

    @Test
    void captureModeRejectsWrongCase() {
        // The enum is case-sensitive; only the lower-case forms are valid.
        assertThrows(ServiceException.class,
                () -> MemoryService.validateCaptureMode("EXPLICIT"));
        assertThrows(ServiceException.class,
                () -> MemoryService.validateCaptureMode("Inferred"));
    }

    // -------------------------------------------------------------------
    // validateSessionId
    // -------------------------------------------------------------------

    @Test
    void sessionIdNullPassesThrough() {
        assertNull(MemoryService.validateSessionId(null));
    }

    @Test
    void sessionIdEmptyOrWhitespaceTreatedAsNull() {
        assertNull(MemoryService.validateSessionId(""));
        assertNull(MemoryService.validateSessionId("   "));
    }

    @Test
    void sessionIdTrimmed() {
        assertEquals("abc-123", MemoryService.validateSessionId("  abc-123  "));
    }

    @Test
    void sessionIdRejectsOverlyLong() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 257; i++)
            sb.append('x');
        final ServiceException e = assertThrows(ServiceException.class,
                () -> MemoryService.validateSessionId(sb.toString()));
        assertEquals(ServiceException.INVALID_INPUT, e.getCode());
    }

    @Test
    void sessionIdAcceptsBoundaryLength() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++)
            sb.append('x');
        assertEquals(sb.toString(), MemoryService.validateSessionId(sb.toString()));
    }

    // -------------------------------------------------------------------
    // buildMetadataJson
    // -------------------------------------------------------------------

    @Test
    void buildMetadataJsonNullCaptureModeReturnsNull() {
        // null = "client didn't say"; let the repository default to '{}'.
        assertNull(MemoryService.buildMetadataJson(null));
    }

    @Test
    void buildMetadataJsonProducesValidJsonWithCaptureMode() {
        final String json = MemoryService.buildMetadataJson("explicit");
        assertNotNull(json);
        // The exact serialization is JSON library territory --- just assert
        // the key/value pair survives.
        assertEquals("{\"capture_mode\":\"explicit\"}", json);
    }
}
