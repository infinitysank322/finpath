package com.finpath.loan;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.finpath.dpdp.ConsentService;
import com.finpath.loan.LoanRepository.Draft;
import com.finpath.loan.LoanRepository.Row;
import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.LoanDetail;
import com.finpath.rules.LoanType;
import com.finpath.rules.Money;
import java.math.BigDecimal;
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
 * Loans: validation and persistence. A stated EMI is stored verbatim and never recomputed; when none
 * is stated the instalment comes from the rules package (AmortisingLoan.emi, BulletLoan.monthlyInterest)
 * and is stored with emiDerived = true. This class does no money arithmetic and logs nothing.
 */
@Service
public class LoanService {

    static final String EMI = "emi";
    static final String BULLET = "bullet";
    static final String CARD_EMI = "card_emi";
    private static final Set<String> TYPES = Set.of(EMI, BULLET, CARD_EMI);
    private static final int MAX_LENDER = 60;
    private static final int RATE_SCALE = 10;

    /** Write payload. The owner is never part of it. Paise and counts must be JSON integers; rate is a decimal string. */
    public record LoanInput(String lender, String type,
            @JsonDeserialize(using = StrictInteger.class) Long principalOutstandingPaise, String rate,
            @JsonDeserialize(using = StrictInteger.class) Long emiPaise,
            @JsonDeserialize(using = StrictInteger.class) Long emisLeft, LocalDate bulletDueDate,
            @JsonDeserialize(using = StrictInteger.class) Long upfrontFeePaise) {
    }

    public record LoanView(UUID id, String lender, String type, long principalOutstandingPaise, String rate,
            Long emiPaise, boolean emiDerived, Integer emisLeft, LocalDate bulletDueDate, long upfrontFeePaise,
            Instant createdAt) {
    }

    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");
    static final String COMPUTED_EMI_LABEL = "Calculated from principal, rate and tenure (comparison only)";

    /** One remaining payment; paise throughout. */
    public record InstalmentView(int number, long openingPaise, long interestPaise, long principalPaise,
            long paymentPaise, long closingPaise) {
    }

    /** The cycle [start, endExclusive) of the final payment. */
    public record PayoffCycle(LocalDate start, LocalDate endExclusive) {
    }

    /** The rules-computed EMI, shown only next to a stated one and never used for cash flow. */
    public record ComputedEmiComparison(String label, long emiPaise) {
    }

    public record LoanDetailView(UUID id, String lender, String type, long emiUsedPaise, boolean emiDerived,
            ComputedEmiComparison computedEmiComparison, String effectiveAnnualRate, long interestRemainingPaise,
            long totalRemainingPayablePaise, int instalmentsRemaining, PayoffCycle payoffCycle, boolean closed,
            boolean pastDue, boolean balloonFinalPayment, List<InstalmentView> instalments) {
    }

    private final LoanRepository loans;
    private final ConsentService consents;
    private final Clock clock;

    @Autowired
    public LoanService(LoanRepository loans, ConsentService consents) {
        this(loans, consents, Clock.system(CYCLE_ZONE));
    }

    LoanService(LoanRepository loans, ConsentService consents, Clock clock) {
        this.loans = loans;
        this.consents = consents;
        this.clock = clock;
    }

    /** FR-13 detail. Every figure comes from LoanDetail.compute; the stated EMI drives cash flow. */
    public LoanDetailView detail(UUID userId, UUID id) {
        Row r = loans.find(userId, id).orElseThrow(LoanNotFoundException::new);
        int startDay = loans.cycleStartDay(userId).orElseThrow(LoanNotFoundException::new);
        LoanDetail.Result d;
        try {
            d = LoanDetail.compute(loanType(r.type()), Money.ofPaise(r.principalOutstandingPaise()), r.rate(),
                    r.emiDerived() || r.emiPaise() == null ? null : Money.ofPaise(r.emiPaise()), r.emisLeft(),
                    r.bulletDueDate(), Money.ofPaise(r.upfrontFeePaise()), LocalDate.now(clock), startDay);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidLoanException();
        }
        ComputedEmiComparison cmp = d.emiDerived() ? null
                : d.computedEmi().map(m -> new ComputedEmiComparison(COMPUTED_EMI_LABEL, m.paise())).orElse(null);
        PayoffCycle payoff = d.payoffCycle().map(LoanService::payoff).orElse(null);
        List<InstalmentView> rows = d.instalments().stream().map(i -> new InstalmentView(i.number(),
                i.opening().paise(), i.interest().paise(), i.principal().paise(), i.payment().paise(),
                i.closing().paise())).toList();
        return new LoanDetailView(r.id(), r.lender(), r.type(), d.emiUsed().paise(), d.emiDerived(), cmp,
                d.effectiveAnnualRate().toPlainString(), d.interestRemaining().paise(),
                d.totalRemainingPayable().paise(), d.instalmentsRemaining(), payoff, d.closed(), d.pastDue(),
                d.balloonFinalPayment(), rows);
    }

    private static PayoffCycle payoff(CycleCalendar.Cycle c) {
        return new PayoffCycle(c.start(), c.endExclusive());
    }

    private static LoanType loanType(String dbValue) {
        return switch (dbValue) {
            case EMI -> LoanType.EMI;
            case BULLET -> LoanType.BULLET;
            case CARD_EMI -> LoanType.CARD_EMI;
            default -> throw new InvalidLoanException();
        };
    }

    public LoanView create(UUID userId, LoanInput in) {
        Draft d = draft(in, LocalDate.now(clock));
        consents.require(userId);
        return view(loans.insert(userId, d));
    }

    public LoanView replace(UUID userId, UUID id, LoanInput in) {
        Draft d = draft(in, LocalDate.now(clock));
        consents.require(userId);
        return view(loans.update(userId, id, d).orElseThrow(LoanNotFoundException::new));
    }

    public LoanView get(UUID userId, UUID id) {
        return view(loans.find(userId, id).orElseThrow(LoanNotFoundException::new));
    }

    public List<LoanView> list(UUID userId) {
        return loans.list(userId).stream().map(LoanService::view).toList();
    }

    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        if (!loans.delete(userId, id)) {
            throw new LoanNotFoundException();
        }
    }

    /** Validates and resolves the instalment; throws InvalidLoanException before any write. */
    private static Draft draft(LoanInput in, LocalDate today) {
        if (in == null || in.type() == null || !TYPES.contains(in.type()) || in.lender() == null
                || in.principalOutstandingPaise() == null || in.principalOutstandingPaise() < 0) {
            throw new InvalidLoanException();
        }
        String type = in.type();
        String lender = in.lender().strip();
        if (lender.isEmpty() || lender.length() > MAX_LENDER) {
            throw new InvalidLoanException();
        }
        BigDecimal rate = rate(in.rate());
        boolean bullet = BULLET.equals(type);
        if (bullet != (in.bulletDueDate() != null) || bullet != (in.emisLeft() == null)) {
            throw new InvalidLoanException();
        }
        Integer emisLeft = null;
        if (!bullet) {
            if (!LoanDetail.tenureWithinLimit(in.emisLeft())) {
                throw new InvalidLoanException();
            }
            emisLeft = in.emisLeft().intValue();
        }
        if (bullet && !LoanDetail.dueWithinHorizon(in.bulletDueDate(), today)) {
            throw new InvalidLoanException();
        }
        long fee = in.upfrontFeePaise() == null ? 0L : in.upfrontFeePaise();
        if (fee < 0 || (fee > 0 && (!CARD_EMI.equals(type) || fee >= in.principalOutstandingPaise()))) {
            throw new InvalidLoanException();
        }
        Long emi = in.emiPaise();
        boolean derived = false;
        if (emi != null) {
            if (emi <= 0) {
                throw new InvalidLoanException();
            }
        } else {
            emi = derive(type, in.principalOutstandingPaise(), rate, emisLeft);
            derived = true;
        }
        return new Draft(lender, type, in.principalOutstandingPaise(), rate, emi, derived, emisLeft,
                in.bulletDueDate(), fee);
    }

    /** Rules-engine instalment for an unstated EMI; card_emi has none (the lender's figure is required). */
    private static long derive(String type, long principalPaise, BigDecimal rate, Integer emisLeft) {
        try {
            Money principal = Money.ofPaise(principalPaise);
            Money m = switch (type) {
                case EMI -> AmortisingLoan.emi(principal, rate, emisLeft);
                case BULLET -> BulletLoan.monthlyInterest(principal, rate);
                default -> throw new InvalidLoanException();
            };
            if (m.paise() <= 0) {
                throw new InvalidLoanException();
            }
            return m.paise();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidLoanException();
        }
    }

    /** Annual fraction as a decimal string, at most 10 decimal places, in [0, 1). */
    private static BigDecimal rate(String raw) {
        if (raw == null) {
            throw new InvalidLoanException();
        }
        BigDecimal r;
        try {
            r = new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw new InvalidLoanException();
        }
        if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) >= 0 || r.stripTrailingZeros().scale() > RATE_SCALE) {
            throw new InvalidLoanException();
        }
        return r;
    }

    private static LoanView view(Row r) {
        return new LoanView(r.id(), r.lender(), r.type(), r.principalOutstandingPaise(),
                r.rate().stripTrailingZeros().toPlainString(), r.emiPaise(), r.emiDerived(), r.emisLeft(),
                r.bulletDueDate(), r.upfrontFeePaise(), r.createdAt());
    }
}
