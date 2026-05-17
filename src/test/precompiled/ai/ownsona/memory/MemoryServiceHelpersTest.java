package ai.ownsona.memory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // -------------------------------------------------------------------
    // validateMaxChars (Phase 1C)
    // -------------------------------------------------------------------

    @Test
    void maxCharsNullPassesThrough() {
        // null = no budget; caller's responsibility to interpret.
        assertNull(MemoryService.validateMaxChars(null));
    }

    @Test
    void maxCharsPositiveReturned() {
        assertEquals(1, MemoryService.validateMaxChars(1));
        assertEquals(8000, MemoryService.validateMaxChars(8000));
    }

    @Test
    void maxCharsZeroOrNegativeRejected() {
        final ServiceException zero = assertThrows(ServiceException.class,
                () -> MemoryService.validateMaxChars(0));
        assertEquals(ServiceException.INVALID_INPUT, zero.getCode());

        final ServiceException neg = assertThrows(ServiceException.class,
                () -> MemoryService.validateMaxChars(-100));
        assertEquals(ServiceException.INVALID_INPUT, neg.getCode());
    }

    // -------------------------------------------------------------------
    // selectFactsByCharBudget (Phase 1C)
    // -------------------------------------------------------------------

    @Test
    void selectFactsEmptyInputYieldsEmptyList() {
        assertEquals(Collections.emptyList(),
                MemoryService.selectFactsByCharBudget(null, 100));
        assertEquals(Collections.emptyList(),
                MemoryService.selectFactsByCharBudget(Collections.emptyList(), 100));
    }

    @Test
    void selectFactsNullBudgetIncludesAll() {
        final List<MemoryRow> rows = Arrays.asList(
                makeRow("alpha"),
                makeRow("beta"),
                makeRow("gamma"));
        final List<String> out = MemoryService.selectFactsByCharBudget(rows, null);
        assertEquals(Arrays.asList("alpha", "beta", "gamma"), out);
    }

    @Test
    void selectFactsBudgetExactlyFitsAll() {
        // Three 5-char facts → 15 total. Budget = 15 fits all.
        final List<MemoryRow> rows = Arrays.asList(
                makeRow("alpha"),
                makeRow("betas"),
                makeRow("gamma"));
        final List<String> out = MemoryService.selectFactsByCharBudget(rows, 15);
        assertEquals(Arrays.asList("alpha", "betas", "gamma"), out);
    }

    @Test
    void selectFactsBudgetTrimsTail() {
        // Three 5-char facts, budget = 12: first two fit (10), third
        // would push total to 15 → dropped.
        final List<MemoryRow> rows = Arrays.asList(
                makeRow("alpha"),
                makeRow("betas"),
                makeRow("gamma"));
        final List<String> out = MemoryService.selectFactsByCharBudget(rows, 12);
        assertEquals(Arrays.asList("alpha", "betas"), out);
    }

    @Test
    void selectFactsBudgetTooSmallForFirstYieldsEmpty() {
        // First fact is 10 chars; budget = 4 can't even hold it.
        final List<MemoryRow> rows = Arrays.asList(
                makeRow("ten--chars"),
                makeRow("short"));
        final List<String> out = MemoryService.selectFactsByCharBudget(rows, 4);
        assertTrue(out.isEmpty(),
                "budget below first fact should yield empty, got: " + out);
    }

    @Test
    void selectFactsStopsAtFirstOverflowEvenIfLaterFactsWouldFit() {
        // alpha=5, megafact=20, x=1.  Budget=10: alpha fits (5 < 10),
        // megafact would push to 25 → stop.  Even though x alone would
        // fit, we don't keep walking past the first overflow because
        // facts are similarity-ranked: x is less relevant than megafact.
        final List<MemoryRow> rows = Arrays.asList(
                makeRow("alpha"),
                makeRow("twenty-chars-of-text"),  // 20 chars
                makeRow("x"));
        final List<String> out = MemoryService.selectFactsByCharBudget(rows, 10);
        assertEquals(Arrays.asList("alpha"), out);
    }

    @Test
    void selectFactsSkipsNullRowsAndNullText() {
        final MemoryRow nullText = new MemoryRow();
        nullText.text = null;
        final List<MemoryRow> rows = Arrays.asList(
                makeRow("alpha"),
                null,
                nullText,
                makeRow("beta"));
        final List<String> out = MemoryService.selectFactsByCharBudget(rows, 100);
        assertEquals(Arrays.asList("alpha", "beta"), out);
    }

    private static MemoryRow makeRow(String text) {
        final MemoryRow r = new MemoryRow();
        r.text = text;
        return r;
    }

    // -------------------------------------------------------------------
    // validateDedupPolicy (Phase 4)
    // -------------------------------------------------------------------

    @Test
    void dedupPolicyNullDefaultsToAsk() {
        assertEquals("ask", MemoryService.validateDedupPolicy(null));
    }

    @Test
    void dedupPolicyEmptyOrWhitespaceDefaultsToAsk() {
        assertEquals("ask", MemoryService.validateDedupPolicy(""));
        assertEquals("ask", MemoryService.validateDedupPolicy("   "));
    }

    @Test
    void dedupPolicyAcceptsKnownValues() {
        assertEquals("insert",       MemoryService.validateDedupPolicy("insert"));
        assertEquals("skip_if_near", MemoryService.validateDedupPolicy("skip_if_near"));
        assertEquals("ask",          MemoryService.validateDedupPolicy("ask"));
        // Surrounding whitespace tolerated.
        assertEquals("insert", MemoryService.validateDedupPolicy("  insert  "));
    }

    @Test
    void dedupPolicyRejectsUnknownValue() {
        final ServiceException e = assertThrows(ServiceException.class,
                () -> MemoryService.validateDedupPolicy("merge"));
        assertEquals(ServiceException.INVALID_INPUT, e.getCode());
    }

    @Test
    void dedupPolicyIsCaseSensitive() {
        // The enum values are lowercase; uppercase variants are rejected.
        assertThrows(ServiceException.class,
                () -> MemoryService.validateDedupPolicy("INSERT"));
        assertThrows(ServiceException.class,
                () -> MemoryService.validateDedupPolicy("Skip_If_Near"));
    }

    // -------------------------------------------------------------------
    // validateExpiresAt (Phase 4)
    // -------------------------------------------------------------------

    @Test
    void expiresAtNullPassesThrough() {
        assertNull(MemoryService.validateExpiresAt(null));
    }

    @Test
    void expiresAtNearFutureAccepted() {
        final java.util.Date soon = new java.util.Date(System.currentTimeMillis()
                + 24L * 60L * 60L * 1000L);   // tomorrow
        assertEquals(soon, MemoryService.validateExpiresAt(soon));
    }

    @Test
    void expiresAtPastAccepted() {
        // Past values are allowed --- a row can be intentionally already-expired.
        final java.util.Date yesterday = new java.util.Date(System.currentTimeMillis()
                - 24L * 60L * 60L * 1000L);
        assertEquals(yesterday, MemoryService.validateExpiresAt(yesterday));
    }

    @Test
    void expiresAtFarFutureRejected() {
        // 200 years out is well past the 100-year cap.
        final java.util.Date farFuture = new java.util.Date(System.currentTimeMillis()
                + 200L * 365L * 24L * 60L * 60L * 1000L);
        final ServiceException e = assertThrows(ServiceException.class,
                () -> MemoryService.validateExpiresAt(farFuture));
        assertEquals(ServiceException.INVALID_INPUT, e.getCode());
    }

    // -------------------------------------------------------------------
    // validateLastConfirmedAt (Phase 4)
    // -------------------------------------------------------------------

    @Test
    void lastConfirmedAtNullPassesThrough() {
        assertNull(MemoryService.validateLastConfirmedAt(null));
    }

    @Test
    void lastConfirmedAtPastAccepted() {
        final java.util.Date pastWeek = new java.util.Date(System.currentTimeMillis()
                - 7L * 24L * 60L * 60L * 1000L);
        assertEquals(pastWeek, MemoryService.validateLastConfirmedAt(pastWeek));
    }

    @Test
    void lastConfirmedAtNearNowAccepted() {
        // Allow a small clock-skew tolerance (< 5 min in the future).
        final java.util.Date soon = new java.util.Date(System.currentTimeMillis() + 30L * 1000L);
        assertEquals(soon, MemoryService.validateLastConfirmedAt(soon));
    }

    @Test
    void lastConfirmedAtFarFutureRejected() {
        // 1 hour into the future is outside the clock-skew tolerance.
        final java.util.Date hourAhead = new java.util.Date(System.currentTimeMillis()
                + 60L * 60L * 1000L);
        final ServiceException e = assertThrows(ServiceException.class,
                () -> MemoryService.validateLastConfirmedAt(hourAhead));
        assertEquals(ServiceException.INVALID_INPUT, e.getCode());
    }

    // -------------------------------------------------------------------
    // validateForgetReason (Phase 5)
    // -------------------------------------------------------------------

    @Test
    void forgetReasonNullPassesThrough() {
        assertNull(MemoryService.validateForgetReason(null));
    }

    @Test
    void forgetReasonEmptyOrWhitespaceTreatedAsNull() {
        assertNull(MemoryService.validateForgetReason(""));
        assertNull(MemoryService.validateForgetReason("   "));
    }

    @Test
    void forgetReasonTrimmed() {
        assertEquals("user moved cities",
                MemoryService.validateForgetReason("  user moved cities  "));
    }

    @Test
    void forgetReasonAtBoundaryAccepted() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1024; i++) sb.append('x');
        assertEquals(sb.toString(), MemoryService.validateForgetReason(sb.toString()));
    }

    @Test
    void forgetReasonTooLongRejected() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1025; i++) sb.append('x');
        final ServiceException e = assertThrows(ServiceException.class,
                () -> MemoryService.validateForgetReason(sb.toString()));
        assertEquals(ServiceException.INVALID_INPUT, e.getCode());
    }
}
