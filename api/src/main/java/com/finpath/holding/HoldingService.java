package com.finpath.holding;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.finpath.dpdp.ConsentService;
import com.finpath.holding.HoldingRepository.Draft;
import com.finpath.holding.HoldingRepository.Row;
import com.finpath.loan.StrictInteger;
import com.finpath.rules.HoldingTotals;
import com.finpath.rules.HoldingTotals.ClassTotal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Holdings: validation and persistence, plus the FR-23 asset-class aggregate (the pure {@link HoldingTotals}). This class does no money arithmetic and logs nothing.
 */
@Service
public class HoldingService {

    static final int MAX_NAME = 60;
    /** Nine or more digits, ignoring any separators: looks like a folio, demat, account, PAN or UAN number. */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d(?:[^A-Za-z0-9]*\\d){8,}");
    /** PAN shape: five letters, four digits, one letter. */
    private static final Pattern PAN = Pattern.compile("(?i)[A-Z]{5}\\d{4}[A-Z]");

    /**
     * Write payload. The owner is never part of it. name is a short label (max 60 chars) and must not hold a
     * folio, demat or account number. Paise must be JSON integers; omitted amounts default to 0.
     */
    public record HoldingInput(String assetClass, String name,
            @JsonDeserialize(using = StrictInteger.class) Long investedPaise,
            @JsonDeserialize(using = StrictInteger.class) Long currentValuePaise,
            @JsonDeserialize(using = StrictInteger.class) Long monthlyContributionPaise) {
    }

    public record HoldingView(UUID id, String assetClass, String name, long investedPaise, long currentValuePaise,
            long monthlyContributionPaise, Instant createdAt) {
    }

    /** One asset class (or the grand total, assetClass "total"). */
    public record ClassTotalView(String assetClass, long investedPaise, long currentValuePaise,
            long monthlyContributionPaise, long count) {
    }

    public record AggregateView(List<ClassTotalView> classes, ClassTotalView total) {
    }

    private final HoldingRepository holdings;
    private final ConsentService consents;

    public HoldingService(HoldingRepository holdings, ConsentService consents) {
        this.holdings = holdings;
        this.consents = consents;
    }

    public HoldingView create(UUID userId, HoldingInput in) {
        Draft d = draft(in);
        consents.require(userId);
        return view(holdings.insert(userId, d));
    }

    public HoldingView replace(UUID userId, UUID id, HoldingInput in) {
        Draft d = draft(in);
        consents.require(userId);
        return view(holdings.update(userId, id, d).orElseThrow(HoldingNotFoundException::new));
    }

    public HoldingView get(UUID userId, UUID id) {
        return view(holdings.find(userId, id).orElseThrow(HoldingNotFoundException::new));
    }

    public List<HoldingView> list(UUID userId) {
        return holdings.list(userId).stream().map(HoldingService::view).toList();
    }

    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        if (!holdings.delete(userId, id)) {
            throw new HoldingNotFoundException();
        }
    }

    /** Per-class totals for every class (zeros where none held) and grand totals. */
    public AggregateView aggregate(UUID userId) {
        HoldingTotals.Result r = HoldingTotals.aggregate(holdings.amounts(userId));
        return new AggregateView(r.classes().stream().map(HoldingService::totalView).toList(),
                totalView(r.total()));
    }

    /** Validates; throws InvalidHoldingException before any write (the DB CHECKs are the second line). */
    private static Draft draft(HoldingInput in) {
        if (in == null || in.assetClass() == null || !HoldingTotals.ASSET_CLASSES.contains(in.assetClass())
                || in.name() == null) {
            throw new InvalidHoldingException();
        }
        String name = in.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME || LONG_DIGITS.matcher(name).find()
                || PAN.matcher(name).find()) {
            throw new InvalidHoldingException();
        }
        long invested = in.investedPaise() == null ? 0L : in.investedPaise();
        long current = in.currentValuePaise() == null ? 0L : in.currentValuePaise();
        long monthly = in.monthlyContributionPaise() == null ? 0L : in.monthlyContributionPaise();
        if (invested < 0 || current < 0 || monthly < 0) {
            throw new InvalidHoldingException();
        }
        return new Draft(in.assetClass(), name, invested, current, monthly);
    }

    private static HoldingView view(Row r) {
        return new HoldingView(r.id(), r.assetClass(), r.name(), r.investedPaise(), r.currentValuePaise(),
                r.monthlyContributionPaise(), r.createdAt());
    }

    private static ClassTotalView totalView(ClassTotal t) {
        return new ClassTotalView(t.assetClass(), t.investedPaise(), t.currentValuePaise(),
                t.monthlyContributionPaise(), t.count());
    }
}
