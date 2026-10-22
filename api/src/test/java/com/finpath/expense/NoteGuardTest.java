package com.finpath.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The note must never carry an account, card or identity number. */
class NoteGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "acct 123456789",                 // exactly 9 digits
        "ref 12345678901234",             // long run
        "card 4111 1111 1111 1111",       // grouped 16
        "aadhaar 1234-5678-9012",         // grouped 12
        "pan ABCDE1234F",
        "pan abcde1234f here",
        "xABCDE1234Fx",
        "acct ١٢٣٤٥٦٧٨٩"   // Arabic-Indic digits
    })
    void rejects(String note) {
        assertThatThrownBy(() -> NoteGuard.check(note)).isInstanceOf(InvalidExpenseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chai with team", "12345678 steps", "Rs 450 for 3 items 2 bags", "ABCD1234E",
        "order 123 456 789"})
    void accepts(String note) {
        assertThat(NoteGuard.check(note)).isEqualTo(note.strip());
    }

    @Test
    void lengthLimitIs140() {
        assertThat(NoteGuard.check("x".repeat(140))).hasSize(140);
        assertThatThrownBy(() -> NoteGuard.check("x".repeat(141))).isInstanceOf(InvalidExpenseException.class);
    }

    @Test
    void nullAndBlankBecomeNullAndTextIsTrimmed() {
        assertThat(NoteGuard.check(null)).isNull();
        assertThat(NoteGuard.check("   ")).isNull();
        assertThat(NoteGuard.check("  lunch ")).isEqualTo("lunch");
    }

    @Test
    void theExceptionCarriesNoText() {
        try {
            NoteGuard.check("acct 123456789012");
        } catch (InvalidExpenseException e) {
            assertThat(e.getMessage()).isNull();
            assertThat(e.getCause()).isNull();
        }
    }
}
