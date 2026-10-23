package com.finpath.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.rules.HoldingTotals.ClassTotal;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoldingTotalsTest {

    @Test
    void emptyInputGivesAllSevenClassesAtZero() {
        HoldingTotals.Result r = HoldingTotals.aggregate(List.of());
        assertThat(r.classes()).extracting(ClassTotal::assetClass)
                .containsExactly("epf", "ppf", "nps", "mf", "stocks", "fd", "gold");
        assertThat(r.classes()).allMatch(c -> c.count() == 0 && c.currentValuePaise() == 0);
        assertThat(r.total()).isEqualTo(new ClassTotal("total", 0, 0, 0, 0));
    }

    @Test
    void grandTotalIsTheSumOfClassTotals() {
        HoldingTotals.Result r = HoldingTotals.aggregate(List.of(
                new ClassTotal("mf", 600, 700, 4, 1), new ClassTotal("mf", 400, 500, 6, 1),
                new ClassTotal("gold", 500, 450, 0, 1),
                new ClassTotal("fd", 7, 8, 9, 3)));
        assertThat(r.total().investedPaise()).isEqualTo(1_507);
        assertThat(r.total().currentValuePaise()).isEqualTo(1_658);
        assertThat(r.total().monthlyContributionPaise()).isEqualTo(19);
        assertThat(r.total().count()).isEqualTo(6);
        assertThat(r.classes().stream().mapToLong(ClassTotal::currentValuePaise).sum())
                .isEqualTo(r.total().currentValuePaise());
        assertThat(r.classes().get(3)).isEqualTo(new ClassTotal("mf", 1_000, 1_200, 10, 2));
    }

    @Test
    void unknownClassAndOverflowAreRejected() {
        assertThatThrownBy(() -> HoldingTotals.aggregate(List.of(new ClassTotal("crypto", 1, 1, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HoldingTotals.aggregate(List.of(
                new ClassTotal("mf", Long.MAX_VALUE, 0, 0, 1), new ClassTotal("fd", 1, 0, 0, 1)))).isInstanceOf(ArithmeticException.class);
    }
}
