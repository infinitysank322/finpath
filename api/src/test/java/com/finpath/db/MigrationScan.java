package com.finpath.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Reads the Flyway migrations as text so tests can check tables, columns and policies. */
final class MigrationScan {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)\\s*\\((.*?)\\n\\)\\s*;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    record Table(String name, List<String> columns) {
    }

    private MigrationScan() {
    }

    static Path dir() {
        Path p = Path.of("src/main/resources/db/migration");
        return Files.isDirectory(p) ? p : Path.of("api/src/main/resources/db/migration");
    }

    /** All migrations concatenated in version order, comments and strings blanked. */
    static String allCode() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.list(dir())) {
            for (Path f : files.filter(x -> x.toString().endsWith(".sql")).sorted().toList()) {
                sb.append(SchemaLint.blankNonCode(Files.readString(f, StandardCharsets.UTF_8))).append('\n');
            }
        }
        return sb.toString();
    }

    static List<Table> tables(String code) {
        List<Table> out = new ArrayList<>();
        Matcher m = CREATE_TABLE.matcher(code);
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            for (String line : m.group(2).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.matches("(?i)(primary|unique|check|constraint|foreign)\\b.*")) {
                    continue;
                }
                cols.add(t.split("\\s+")[0].toLowerCase());
            }
            out.add(new Table(m.group(1).toLowerCase(), cols));
        }
        return out;
    }
}
