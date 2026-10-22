package com.finpath.loan;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.finpath.dpdp.ConsentService;
import com.finpath.loan.LoanRepository.Draft;
import com.finpath.loan.LoanRepository.Row;
import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    private final LoanRepository loans;
    private final ConsentService consents;

    public LoanService(LoanRepository loans, ConsentService consents) {
        this.loans = loans;
        this.consents = consents;
    }

    public LoanView create(UUID userId, LoanInput in) {
        Draft d = draft(in);
        consents.require(userId);
        return view(loans.insert(userId, d));
    }

    public LoanView replace(UUID userId, UUID id, LoanInput in) {
        Draft d = draft(in);
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
    private static Draft draft(LoanInput in) {
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
            if (in.emisLeft() <= 0 || in.emisLeft() > Integer.MAX_VALUE) {
                throw new InvalidLoanException();
            }
            emisLeft = in.emisLeft().intValue();
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
