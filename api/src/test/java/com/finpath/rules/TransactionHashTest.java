package com.finpath.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.rules.TransactionHash.Direction;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TransactionHashTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 22);

    private static String base() {
        return TransactionHash.dedupeHash(D, 4000, Direction.OUT, "DINING", "UPI");
    }

    @Test
    void identicalInputsGiveEqualHashes() {
        assertThat(base()).isEqualTo(base());
    }

    @Test
    void knownAnswerVector() {
        // sha256("v1|2026-10-22|4000|OUT|DINING|UPI"), computed independently with sha256sum.
        assertThat(base()).isEqualTo("d597fd8fd86ba760e5cb4640ce891cf57396ddedf3ec65f47d2ca6b808fe0fe8")
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void anyChangedFieldChangesTheHash() {
        assertThat(TransactionHash.dedupeHash(D, 4001, Direction.OUT, "DINING", "UPI")).isNotEqualTo(base());
        assertThat(TransactionHash.dedupeHash(D.plusDays(1), 4000, Direction.OUT, "DINING", "UPI"))
                .isNotEqualTo(base());
        assertThat(TransactionHash.dedupeHash(D, 4000, Direction.IN, "DINING", "UPI")).isNotEqualTo(base());
        assertThat(TransactionHash.dedupeHash(D, 4000, Direction.OUT, "GROCERIES", "UPI")).isNotEqualTo(base());
        assertThat(TransactionHash.dedupeHash(D, 4000, Direction.OUT, "DINING", "CASH")).isNotEqualTo(base());
    }

    @Test
    void caseAndWhitespaceInCodeAndModeNormaliseEqual() {
        assertThat(TransactionHash.dedupeHash(D, 4000, Direction.OUT, "  dining ", " upi\t")).isEqualTo(base());
    }

    @Test
    void invalidInputThrows() {
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, 0, Direction.OUT, "DINING", "UPI"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, -1, Direction.OUT, "DINING", "UPI"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, 1, Direction.OUT, null, "UPI"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, 1, Direction.OUT, "  ", "UPI"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, 1, Direction.OUT, "DINING", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, 1, Direction.OUT, "DINING", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(null, 1, Direction.OUT, "DINING", "UPI"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TransactionHash.dedupeHash(D, 1, null, "DINING", "UPI"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
