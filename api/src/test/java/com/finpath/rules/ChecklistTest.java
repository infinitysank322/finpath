package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChecklistTest {

    @Test
    void emptyCycleIsNotComplete() {
        assertFalse(Checklist.cycleComplete(List.of()));
    }

    @Test
    void allDoneIsComplete() {
        assertTrue(Checklist.cycleComplete(List.of(true)));
        assertTrue(Checklist.cycleComplete(List.of(true, true, true)));
    }

    @Test
    void oneOpenItemIsNotComplete() {
        assertFalse(Checklist.cycleComplete(List.of(true, false, true)));
        assertFalse(Checklist.cycleComplete(List.of(false)));
    }

    @Test
    void nullInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> Checklist.cycleComplete(null));
        assertThrows(NullPointerException.class, () -> Checklist.cycleComplete(Arrays.asList(true, null)));
    }
}
