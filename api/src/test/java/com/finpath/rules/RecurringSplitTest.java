package com.finpath.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.rules.RecurringSplit.Due;
import com.finpath.rules.RecurringSplit.Frequency;
import com.finpath.rules.RecurringSplit.Template;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RecurringSplitTest {

    private static final int DAY = 25;

    private static CycleCalendar.Cycle cycleOf(String date) {
        return CycleCalendar.cycleFor(LocalDate.parse(date), DAY);
    }

    @Test
    void periodMonths() {
        assertThat(RecurringSplit.periodMonths(Frequency.MONTHLY)).isEqualTo(1);
        assertThat(RecurringSplit.periodMonths(Frequency.QUARTERLY)).isEqualTo(3);
        assertThat(RecurringSplit.periodMonths(Frequency.YEARLY)).isEqualTo(12);
    }

    @Test
    void evenSplitIsEqualAndExact() {
        long sum = 0;
        for (int i = 0; i < 3; i++) {
            assertThat(RecurringSplit.instalment(600_000L, 3, i)).isEqualTo(200_000L);
            sum += RecurringSplit.instalment(600_000L, 3, i);
        }
        assertThat(sum).isEqualTo(600_000L);
    }

    @Test
    void remainderLandsOnTheLastInstalment() {
        assertThat(RecurringSplit.instalment(1000, 3, 0)).isEqualTo(333);
        assertThat(RecurringSplit.instalment(1000, 3, 1)).isEqualTo(333);
        assertThat(RecurringSplit.instalment(1000, 3, 2)).isEqualTo(334);
        assertThat(RecurringSplit.instalment(1_000_001L, 3, 2)).isEqualTo(333_335L);
    }

    @Test
    void monthlyIsTheFullAmount() {
        assertThat(RecurringSplit.instalment(123_457L, 1, 0)).isEqualTo(123_457L);
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> RecurringSplit.instalment(0, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurringSplit.instalment(-1, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurringSplit.instalment(100, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurringSplit.instalment(100, 3, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurringSplit.instalment(100, 3, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBillSmallerThanItsPeriodsIsNotSplittable() {
        assertThat(RecurringSplit.splittable(2, Frequency.QUARTERLY)).isFalse();
        assertThat(RecurringSplit.splittable(3, Frequency.QUARTERLY)).isTrue();
        assertThat(RecurringSplit.splittable(11, Frequency.YEARLY)).isFalse();
        assertThat(RecurringSplit.splittable(1, Frequency.MONTHLY)).isTrue();
    }

    @Test
    void instalmentsAlwaysSumToTheBill() {
        for (int periods : new int[] {1, 3, 12}) {
            for (long bill = periods; bill <= 5000L; bill++) {
                long sum = 0;
                for (int i = 0; i < periods; i++) {
                    long part = RecurringSplit.instalment(bill, periods, i);
                    assertThat(part).isPositive();
                    sum += part;
                }
                assertThat(sum).as("bill %d over %d", bill, periods).isEqualTo(bill);
            }
        }
    }

    @Test
    void quarterlyPostsOneInstalmentPerCycleThenRepeats() {
        Template t = new Template(1000, Frequency.QUARTERLY, LocalDate.parse("2026-09-25"), null);
        String[] starts = {"2026-09-25", "2026-10-25", "2026-11-25", "2026-12-25"};
        long[] expected = {333, 333, 334, 333};
        for (int i = 0; i < starts.length; i++) {
            Due d = RecurringSplit.due(t, cycleOf(starts[i]), DAY).orElseThrow();
            assertThat(d.amountPaise()).isEqualTo(expected[i]);
            assertThat(d.index()).isEqualTo(i % 3);
            assertThat(d.periods()).isEqualTo(3);
        }
    }

    @Test
    void startDateMidCycleBelongsToThatCycle() {
        Template t = new Template(600_000, Frequency.QUARTERLY, LocalDate.parse("2026-10-10"), null);
        // 2026-10-10 is in the cycle that began 2026-09-25.
        assertThat(RecurringSplit.due(t, cycleOf("2026-09-25"), DAY).orElseThrow().index()).isZero();
        assertThat(RecurringSplit.due(t, cycleOf("2026-10-25"), DAY).orElseThrow().index()).isEqualTo(1);
    }

    @Test
    void futureStartIsEmptyAndStartOnCycleBoundaryIsFirstInstalment() {
        Template t = new Template(600_000, Frequency.QUARTERLY, LocalDate.parse("2026-10-25"), null);
        assertThat(RecurringSplit.due(t, cycleOf("2026-09-25"), DAY)).isEmpty();   // starts the day the cycle ends
        assertThat(RecurringSplit.due(t, cycleOf("2026-10-25"), DAY).orElseThrow().index()).isZero();
    }

    @Test
    void endDateBoundaries() {
        Template t = new Template(300, Frequency.MONTHLY, LocalDate.parse("2026-01-25"),
                LocalDate.parse("2026-10-24"));
        assertThat(RecurringSplit.due(t, cycleOf("2026-09-25"), DAY)).isPresent();   // ends on the cycle's last day
        assertThat(RecurringSplit.due(t, cycleOf("2026-10-25"), DAY)).isEmpty();
        Template onStart = new Template(300, Frequency.MONTHLY, LocalDate.parse("2026-01-25"),
                LocalDate.parse("2026-10-25"));
        assertThat(RecurringSplit.due(onStart, cycleOf("2026-10-25"), DAY)).isPresent();
    }

    @Test
    void endDateInsideAPeriodStopsFurtherInstalments() {
        Template t = new Template(600_000, Frequency.QUARTERLY, LocalDate.parse("2026-09-25"),
                LocalDate.parse("2026-10-30"));
        assertThat(RecurringSplit.due(t, cycleOf("2026-09-25"), DAY)).isPresent();
        assertThat(RecurringSplit.due(t, cycleOf("2026-10-25"), DAY)).isPresent();
        assertThat(RecurringSplit.due(t, cycleOf("2026-11-25"), DAY)).isEmpty();
    }

    @Test
    void startDay31AcrossFebruary() {
        Template t = new Template(900, Frequency.QUARTERLY, LocalDate.parse("2026-01-31"), null);
        long sum = 0;
        for (String date : new String[] {"2026-01-31", "2026-02-28", "2026-03-31"}) {
            CycleCalendar.Cycle c = CycleCalendar.cycleFor(LocalDate.parse(date), 31);
            sum += RecurringSplit.due(t, c, 31).orElseThrow().amountPaise();
        }
        assertThat(sum).isEqualTo(900);
        CycleCalendar.Cycle feb = CycleCalendar.cycleFor(LocalDate.parse("2026-02-28"), 31);
        assertThat(feb.start()).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(RecurringSplit.due(t, feb, 31).orElseThrow().index()).isEqualTo(1);
    }

    @Test
    void yearlyRepeatsAfterTwelveCycles() {
        Template t = new Template(1200, Frequency.YEARLY, LocalDate.parse("2026-01-25"), null);
        assertThat(RecurringSplit.due(t, cycleOf("2026-12-25"), DAY).orElseThrow().index()).isEqualTo(11);
        assertThat(RecurringSplit.due(t, cycleOf("2027-01-25"), DAY).orElseThrow().index()).isZero();
    }

    @Test
    void templateRejectsBadInputs() {
        assertThatThrownBy(() -> new Template(0, Frequency.MONTHLY, LocalDate.parse("2026-01-01"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Template(10, Frequency.MONTHLY, LocalDate.parse("2026-01-02"),
                LocalDate.parse("2026-01-01"))).isInstanceOf(IllegalArgumentException.class);
    }
}
