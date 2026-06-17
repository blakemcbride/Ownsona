package ai.ownsona.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Tier 1 reinforcement / salience math and the new
 * validators ({@code reinforce} delta, {@code find_conflicts} threshold).
 * These exercise only pure, package-private helpers --- no DB needed.
 */
class MemoryReinforcementTest {

    private static final double EPS = 1e-9;

    // -------------------------------------------------------------------
    // applyReinforcement: salience <- clamp((1 - lambda)*salience + eta*reward)
    // -------------------------------------------------------------------

    @Test
    void positiveRewardRaisesSalience() {
        // 0.95*0.5 + 0.3*1 = 0.775
        final double s = MemoryService.applyReinforcement(
                0.5, 1.0, MemoryService.REINFORCE_ETA, MemoryService.REINFORCE_LAMBDA,
                MemoryService.SALIENCE_MIN, MemoryService.SALIENCE_MAX);
        assertEquals(0.775, s, EPS);
        assertTrue(s > 0.5, "a helpful reward should raise salience");
    }

    @Test
    void negativeRewardLowersSalience() {
        // 0.95*0.5 - 0.3*1 = 0.175
        final double s = MemoryService.applyReinforcement(
                0.5, -1.0, MemoryService.REINFORCE_ETA, MemoryService.REINFORCE_LAMBDA,
                MemoryService.SALIENCE_MIN, MemoryService.SALIENCE_MAX);
        assertEquals(0.175, s, EPS);
        assertTrue(s < 0.5, "an unhelpful reward should lower salience");
    }

    @Test
    void salienceClampedToMax() {
        // 0.95*3 + 0.3 = 3.15 -> clamped to 3.0
        final double s = MemoryService.applyReinforcement(
                3.0, 1.0, MemoryService.REINFORCE_ETA, MemoryService.REINFORCE_LAMBDA,
                MemoryService.SALIENCE_MIN, MemoryService.SALIENCE_MAX);
        assertEquals(MemoryService.SALIENCE_MAX, s, EPS);
    }

    @Test
    void salienceClampedToMin() {
        // 0.95*0.1 - 0.3 = -0.205 -> clamped to 0.0
        final double s = MemoryService.applyReinforcement(
                0.1, -1.0, MemoryService.REINFORCE_ETA, MemoryService.REINFORCE_LAMBDA,
                MemoryService.SALIENCE_MIN, MemoryService.SALIENCE_MAX);
        assertEquals(MemoryService.SALIENCE_MIN, s, EPS);
    }

    @Test
    void repeatedPositiveRewardsSaturateNotDiverge() {
        // The (1 - lambda) smoothing term keeps salience bounded under a
        // stream of +1 rewards instead of growing without limit.
        double s = 0.5;
        for (int i = 0; i < 1000; i++)
            s = MemoryService.applyReinforcement(
                    s, 1.0, MemoryService.REINFORCE_ETA, MemoryService.REINFORCE_LAMBDA,
                    MemoryService.SALIENCE_MIN, MemoryService.SALIENCE_MAX);
        assertEquals(MemoryService.SALIENCE_MAX, s, EPS);
    }

    @Test
    void zeroRewardAppliesSmoothingOnlyNotTimeDecay() {
        // A zero-reward event nudges salience by the smoothing factor; this
        // only happens on an explicit event, never with the passage of time.
        final double s = MemoryService.applyReinforcement(
                1.0, 0.0, MemoryService.REINFORCE_ETA, MemoryService.REINFORCE_LAMBDA,
                MemoryService.SALIENCE_MIN, MemoryService.SALIENCE_MAX);
        assertEquals(0.95, s, EPS);
    }

    // -------------------------------------------------------------------
    // validateReinforceDelta
    // -------------------------------------------------------------------

    @Test
    void deltaNullDefaultsToPlusOne() {
        assertEquals(MemoryService.DEFAULT_REINFORCE_DELTA,
                MemoryService.validateReinforceDelta(null), EPS);
    }

    @Test
    void deltaInRangeAccepted() {
        assertEquals(0.5, MemoryService.validateReinforceDelta(0.5), EPS);
        assertEquals(-1.0, MemoryService.validateReinforceDelta(-1.0), EPS);
        assertEquals(1.0, MemoryService.validateReinforceDelta(1.0), EPS);
    }

    @Test
    void deltaOutOfRangeRejected() {
        assertThrows(ServiceException.class, () -> MemoryService.validateReinforceDelta(1.0001));
        assertThrows(ServiceException.class, () -> MemoryService.validateReinforceDelta(-1.0001));
        assertThrows(ServiceException.class, () -> MemoryService.validateReinforceDelta(Double.NaN));
    }

    // -------------------------------------------------------------------
    // validateConflictThreshold
    // -------------------------------------------------------------------

    @Test
    void conflictThresholdNullDefaults() {
        assertEquals(MemoryService.DEFAULT_CONFLICT_THRESHOLD,
                MemoryService.validateConflictThreshold(null), EPS);
    }

    @Test
    void conflictThresholdInRangeAccepted() {
        assertEquals(0.80, MemoryService.validateConflictThreshold(0.80), EPS);
        assertEquals(1.0, MemoryService.validateConflictThreshold(1.0), EPS);
        assertEquals(MemoryService.MIN_CONFLICT_THRESHOLD,
                MemoryService.validateConflictThreshold(MemoryService.MIN_CONFLICT_THRESHOLD), EPS);
    }

    @Test
    void conflictThresholdOutOfRangeRejected() {
        assertThrows(ServiceException.class,
                () -> MemoryService.validateConflictThreshold(MemoryService.MIN_CONFLICT_THRESHOLD - 0.01));
        assertThrows(ServiceException.class, () -> MemoryService.validateConflictThreshold(1.01));
        assertThrows(ServiceException.class, () -> MemoryService.validateConflictThreshold(Double.NaN));
    }
}
