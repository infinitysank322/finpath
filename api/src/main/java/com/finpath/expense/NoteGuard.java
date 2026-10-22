package com.finpath.expense;

import java.util.regex.Pattern;

/**
 * Keeps account, card and identity numbers out of the free-text note. Rejects 9 or more
 * consecutive digits, digit groups separated by single spaces or hyphens that add up to 12 or more
 * digits (card and Aadhaar layouts), and PAN-shaped text (five letters, four digits, one letter).
 * Pure; it never logs or returns the text.
 */
final class NoteGuard {

    static final int MAX_LENGTH = 140;

    private static final Pattern LONG_RUN = Pattern.compile("\\p{Nd}{9,}");
    private static final Pattern GROUPED = Pattern.compile("\\p{Nd}+(?:[ -]\\p{Nd}+)+");
    private static final Pattern PAN = Pattern.compile("\\p{L}{5}\\p{Nd}{4}\\p{L}");

    private NoteGuard() {
    }

    /** Returns the trimmed note, or null for a null or blank one; throws when it is not acceptable. */
    static String check(String note) {
        if (note == null) {
            return null;
        }
        String n = note.strip();
        if (n.isEmpty()) {
            return null;
        }
        if (n.length() > MAX_LENGTH || LONG_RUN.matcher(n).find() || PAN.matcher(n).find()
                || groupedDigits(n)) {
            throw new InvalidExpenseException();
        }
        return n;
    }

    private static boolean groupedDigits(String n) {
        var m = GROUPED.matcher(n);
        while (m.find()) {
            if (m.group().codePoints().filter(Character::isDigit).count() >= 12) {
                return true;
            }
        }
        return false;
    }
}
