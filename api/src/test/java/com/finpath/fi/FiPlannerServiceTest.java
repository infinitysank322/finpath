package com.finpath.fi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.fi.FiPlannerService.FiPlanView;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.golden.GoldenAssert;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** T63: corpus figures from the fixture, band shape, assumptions echoed, opening balance defaulting to zero. */
class FiPlannerServiceTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long EXPENSE = FX.paise(GoldenKeys.FI_TODAY_EXPENSE);
    private static final long PASSIVE = FX.paise(GoldenKeys.FI_PASSIVE_MONTHLY);
    private static final int YEARS = FX.integer(GoldenKeys.FI_HORIZON_YEARS);

    private final FiPlannerService service = new FiPlannerService(RuleConfig.defaults(),
            Clock.fixed(LocalDate.of(2026, 10, 22).atStartOfDay(IST).toInstant(), IST));

    @Test
    void grossAndNetCorpusMatchTheFixtureWithinOnePercent() {
        FiPlanView v = service.plan(EXPENSE, PASSIVE, null, null, null, YEARS);
        GoldenAssert.assertWithinOnePercent(FX.paise(GoldenKeys.FI_GROSS_CORPUS), v.grossCorpusPaise());
        GoldenAssert.assertWithinOnePercent(FX.paise(GoldenKeys.FI_NET_CORPUS), v.netCorpusPaise());
        GoldenAssert.assertWithinOnePercent(FX.paise(GoldenKeys.FI_INFLATED_EXPENSE), v.inflatedMonthlyExpensePaise());
    }

    @Test
    void responseCarriesThreeBandsAndTheAssumptions() {
        FiPlanView v = service.plan(EXPENSE, PASSIVE, null, 100_000L, "0.1", YEARS);
        assertThat(v.bands()).extracting(FiPlannerService.BandView::band).containsExactly("LOW", "BASE", "HIGH");
        assertThat(v.openingBalancePaise()).isZero();
        assertThat(v.assumptions().inflation()).isEqualTo(RuleConfig.defaults().inflation().toPlainString());
        assertThat(v.assumptions().equityReturn()).isEqualTo(RuleConfig.defaults().equityReturn().toPlainString());
        assertThat(v.assumptions().fiMultiplier()).isEqualTo(RuleConfig.defaults().fiMultiplier());
        assertThat(v.assumptions().stepUp()).isEqualTo("0.1");
        assertThat(v.bands().get(0).requiredFirstMonthlyPaise())
                .isGreaterThan(v.bands().get(2).requiredFirstMonthlyPaise());
    }

    @Test
    void fiDateIsTodayPlusSolvedYearsOrNull() {
        FiPlanView none = service.plan(EXPENSE, PASSIVE, null, null, null, YEARS);
        assertThat(none.bands()).allSatisfy(b -> {
            assertThat(b.fiYears()).isNull();
            assertThat(b.fiDate()).isNull();
        });
        FiPlanView rich = service.plan(EXPENSE, PASSIVE, 90_000_000_000L, null, null, YEARS);
        assertThat(rich.bands().get(0).fiYears()).isZero();
        assertThat(rich.bands().get(0).fiDate()).isEqualTo(LocalDate.of(2026, 10, 22));
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> service.plan(null, PASSIVE, null, null, null, YEARS)).isInstanceOf(InvalidFiException.class);
        assertThatThrownBy(() -> service.plan(EXPENSE, PASSIVE, null, null, null, 0)).isInstanceOf(InvalidFiException.class);
        assertThatThrownBy(() -> service.plan(EXPENSE, PASSIVE, null, null, null, null)).isInstanceOf(InvalidFiException.class);
        assertThatThrownBy(() -> service.plan(EXPENSE, -1L, null, null, null, YEARS)).isInstanceOf(InvalidFiException.class);
        assertThatThrownBy(() -> service.plan(EXPENSE, PASSIVE, -1L, null, null, YEARS)).isInstanceOf(InvalidFiException.class);
        assertThatThrownBy(() -> service.plan(EXPENSE, PASSIVE, null, null, "abc", YEARS)).isInstanceOf(InvalidFiException.class);
        assertThatThrownBy(() -> service.plan(EXPENSE, PASSIVE, null, null, "2", YEARS)).isInstanceOf(InvalidFiException.class);
    }
}
