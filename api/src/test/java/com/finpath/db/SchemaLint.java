package com.finpath.db;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static lint for Flyway migrations. Money is integer paise in {@code bigint}, so no migration
 * may declare a floating-point or {@code money} type anywhere: column definitions, ALTER ... TYPE
 * or casts. Comments, string literals and quoted identifiers are blanked first so prose such as
 * "never double precision" does not trip it.
 */
final class SchemaLint {

    /** double precision (any whitespace between), float, float(n), float4, float8, real, money. */
    static final Pattern FORBIDDEN_TYPE = Pattern.compile(
            "\\b(double\\s+precision|float[48]?|real|money)\\b", Pattern.CASE_INSENSITIVE);

    private SchemaLint() {
    }

    /** Returns one "line N: snippet" entry per forbidden type; empty when the SQL is clean. */
    static List<String> violations(String sql) {
        String code = blankNonCode(sql);
        List<String> hits = new ArrayList<>();
        Matcher m = FORBIDDEN_TYPE.matcher(code);
        while (m.find()) {
            int line = 1;
            for (int i = 0; i < m.start(); i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            hits.add("line " + line + ": " + m.group().replaceAll("\\s+", " "));
        }
        return hits;
    }

    /**
     * Replaces comments, single-quoted strings and double-quoted identifiers with spaces, keeping
     * newlines so line numbers survive. Dollar-quoted bodies stay visible: they hold code.
     */
    static String blankNonCode(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int depth = 0;
                do {
                    if (i + 1 < n && sql.charAt(i) == '/' && sql.charAt(i + 1) == '*') {
                        depth++;
                        out.append("  ");
                        i += 2;
                    } else if (i + 1 < n && sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                        depth--;
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(sql.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                } while (depth > 0 && i < n);
            } else if (c == '\'' || c == '"') {
                // E'...' strings treat a backslash as escaping the next character.
                boolean escapeString = c == '\'' && i > 0
                        && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !Character.isLetterOrDigit(sql.charAt(i - 2)) && sql.charAt(i - 2) != '_');
                out.append(' ');
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (escapeString && d == '\\' && i + 1 < n) {
                        out.append(' ').append(sql.charAt(i + 1) == '\n' ? '\n' : ' ');
                        i += 2;
                    } else if (d == c && i + 1 < n && sql.charAt(i + 1) == c) {
                        out.append("  ");
                        i += 2;
                    } else if (d == c) {
                        out.append(' ');
                        i++;
                        break;
                    } else {
                        out.append(d == '\n' ? '\n' : ' ');
                        i++;
                    }
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
