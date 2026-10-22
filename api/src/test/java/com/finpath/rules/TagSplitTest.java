package com.finpath.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.rules.TagSplit.Entry;
import com.finpath.rules.TagSplit.Split;
import com.finpath.rules.TagSplit.Tag;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagSplitTest {

    @Test
    void emptyIsAllZero() {
        assertThat(TagSplit.split(List.of())).isEqualTo(new Split(0, 0, 0, 0, 0));
    }

    @Test
    void bucketsSumToTotalAndUntaggedIsItsOwnBucket() {
        Split s = TagSplit.split(List.of(new Entry(1000, Tag.NEED), new Entry(250, Tag.NEED),
                new Entry(300, Tag.WANT), new Entry(75, Tag.WASTE), new Entry(400, null)));
        assertThat(s).isEqualTo(new Split(1250, 300, 75, 400, 2025));
        assertThat(s.needPaise() + s.wantPaise() + s.wastePaise() + s.untaggedPaise()).isEqualTo(s.totalPaise());
    }

    @Test
    void allUntaggedNeverCountsAsNeed() {
        Split s = TagSplit.split(List.of(new Entry(500, null), new Entry(700, null)));
        assertThat(s.needPaise()).isZero();
        assertThat(s.untaggedPaise()).isEqualTo(1200);
        assertThat(s.totalPaise()).isEqualTo(1200);
    }

    @Test
    void rejectsNonPositiveAmountAndOverflow() {
        assertThatThrownBy(() -> new Entry(0, Tag.NEED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagSplit.split(List.of(new Entry(Long.MAX_VALUE, Tag.NEED),
                new Entry(1, Tag.NEED)))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void singleTagInputsAndNegativeAndTotalOverflow() {
        assertThat(TagSplit.split(List.of(new Entry(5, Tag.WANT)))).isEqualTo(new Split(0, 5, 0, 0, 5));
        assertThat(TagSplit.split(List.of(new Entry(6, Tag.WASTE)))).isEqualTo(new Split(0, 0, 6, 0, 6));
        assertThatThrownBy(() -> new Entry(-1, Tag.NEED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TagSplit.split(List.of(new Entry(Long.MAX_VALUE, Tag.WANT),
                new Entry(1, Tag.WASTE)))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> TagSplit.split(List.of(new Entry(Long.MAX_VALUE, null), new Entry(1, null))))
                .isInstanceOf(ArithmeticException.class);
    }
}
