package com.finpath.user;

import java.time.Instant;
import java.util.UUID;

/**
 * A FinPath user (FR-01). Holds no account, card or identity numbers. {@code cycleStartDay}
 * is 1-31 and clamped per month by the rules CycleCalendar; the currency is always INR.
 */
public record User(
        UUID id,
        String name,
        String email,
        short cycleStartDay,
        String currency,
        RiskProfile riskProfile,
        Short fiTargetAge,
        Instant createdAt) {

    public static final short DEFAULT_CYCLE_START_DAY = 25;

    public enum RiskProfile { CONSERVATIVE, MODERATE, AGGRESSIVE }
}
