package com.finpath.goal;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.finpath.dpdp.ConsentService;
import com.finpath.goal.GoalRepository.Draft;
import com.finpath.goal.GoalRepository.Row;
import com.finpath.loan.StrictInteger;
import com.finpath.rules.DatedGoal;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Goals: validation and persistence, plus the FR-19 status, every figure of which comes from
 * {@link DatedGoal}. This class does no money arithmetic and logs nothing.
 */
@Service
public class GoalService {

    static final Set<String> TYPES = Set.of("emergency", "marriage", "education", "home", "health", "vehicle",
            "travel", "other");
    static final int DEFAULT_PRIORITY = 5;
    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 10;
    private static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");

    /** Write payload. The owner is never part of it. Paise and priority must be JSON integers. */
    public record GoalInput(String type,
            @JsonDeserialize(using = StrictInteger.class) Long targetAmountPaise, LocalDate targetDate,
            @JsonDeserialize(using = StrictInteger.class) Long currentAmountPaise,
            @JsonDeserialize(using = StrictInteger.class) Long priority) {
    }

    public record GoalView(UUID id, String type, long targetAmountPaise, LocalDate targetDate,
            long currentAmountPaise, int priority, Instant createdAt) {
    }

    /**
     * FR-19 status. status is ACHIEVED / ON_TRACK / BEHIND / PAST_DUE and onTrack is true for ACHIEVED and
     * ON_TRACK; instrumentClass is SAFE_ONLY below the equity horizon floor.
     */
    public record GoalStatusView(UUID id, int cyclesRemaining, long shortfallPaise, long monthlyNeedPaise,
            long plannedMonthlyPaise, String status, boolean onTrack, String instrumentClass) {
    }

    private final GoalRepository goals;
    private final ConsentService consents;
    private final RuleConfig config;
    private final Clock clock;

    @Autowired
    public GoalService(GoalRepository goals, ConsentService consents, RuleConfig config) {
        this(goals, consents, config, Clock.system(CYCLE_ZONE));
    }

    GoalService(GoalRepository goals, ConsentService consents, RuleConfig config, Clock clock) {
        this.goals = goals;
        this.consents = consents;
        this.config = config;
        this.clock = clock;
    }

    public GoalView create(UUID userId, GoalInput in) {
        Draft d = draft(in);
        consents.require(userId);
        return view(goals.insert(userId, d));
    }

    public GoalView replace(UUID userId, UUID id, GoalInput in) {
        Draft d = draft(in);
        consents.require(userId);
        return view(goals.update(userId, id, d).orElseThrow(GoalNotFoundException::new));
    }

    public GoalView get(UUID userId, UUID id) {
        return view(goals.find(userId, id).orElseThrow(GoalNotFoundException::new));
    }

    public List<GoalView> list(UUID userId) {
        return goals.list(userId).stream().map(GoalService::view).toList();
    }

    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        if (!goals.delete(userId, id)) {
            throw new GoalNotFoundException();
        }
    }

    /**
     * Status of one goal today. plannedMonthlyPaise is what the caller plans to set aside each cycle (required,
     * not stored); it decides on-track versus behind.
     */
    public GoalStatusView status(UUID userId, UUID id, Long plannedMonthlyPaise) {
        if (plannedMonthlyPaise == null || plannedMonthlyPaise < 0) {
            throw new InvalidGoalException();
        }
        Row r = goals.find(userId, id).orElseThrow(GoalNotFoundException::new);
        int startDay = goals.cycleStartDay(userId).orElseThrow(GoalNotFoundException::new);
        DatedGoal.Result res;
        try {
            res = DatedGoal.evaluate(Money.ofPaise(r.targetAmountPaise()), Money.ofPaise(r.currentAmountPaise()),
                    Money.ofPaise(plannedMonthlyPaise), LocalDate.now(clock), r.targetDate(), startDay, config);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidGoalException();
        }
        boolean onTrack = res.status() == DatedGoal.Status.ACHIEVED || res.status() == DatedGoal.Status.ON_TRACK;
        return new GoalStatusView(r.id(), res.cyclesRemaining(), res.shortfall().paise(), res.monthlyNeed().paise(),
                plannedMonthlyPaise, res.status().name(), onTrack, res.instrumentClass().name());
    }

    /** Validates; throws InvalidGoalException before any write (the DB CHECKs are the second line). */
    private static Draft draft(GoalInput in) {
        if (in == null || in.type() == null || !TYPES.contains(in.type()) || in.targetDate() == null
                || in.targetAmountPaise() == null || in.targetAmountPaise() <= 0) {
            throw new InvalidGoalException();
        }
        long current = in.currentAmountPaise() == null ? 0L : in.currentAmountPaise();
        long priority = in.priority() == null ? DEFAULT_PRIORITY : in.priority();
        if (current < 0 || priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new InvalidGoalException();
        }
        return new Draft(in.type(), in.targetAmountPaise(), in.targetDate(), current, (int) priority);
    }

    private static GoalView view(Row r) {
        return new GoalView(r.id(), r.type(), r.targetAmountPaise(), r.targetDate(), r.currentAmountPaise(),
                r.priority(), r.createdAt());
    }
}
