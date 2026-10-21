package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Thresholds live in RuleConfig only. */
class NoMagicNumbersScanTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?<![\\w.])(0?\\.40|0?\\.12|5_?000)(?![\\d_])(?!\\.\\d)");

    private static Path mainRoot() {
        Path p = Path.of("src/main");
        if (!Files.isDirectory(p)) {
            p = Path.of("api/src/main");
        }
        return p;
    }

    @Test
    void noThresholdLiteralsOutsideRuleConfig() throws IOException {
        Path root = mainRoot();
        assertTrue(Files.isDirectory(root), "cannot locate src/main from " + Path.of("").toAbsolutePath());
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (f.getFileName().toString().equals("RuleConfig.java")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    if (FORBIDDEN.matcher(lines.get(i)).find()) {
                        hits.add(f + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "Forbidden literals outside RuleConfig: " + hits);
    }

    @Test
    void patternCatchesTheLiterals() {
        for (String s : new String[] {"x = 0.40;", "new BigDecimal(\"0.12\")", ".12", "5000", "5_000", "= 5000L"}) {
            assertTrue(FORBIDDEN.matcher(s).find(), s);
        }
        for (String s : new String[] {"0.125", "50000", "500_000L", "10.40", "0.401"}) {
            assertTrue(!FORBIDDEN.matcher(s).find(), s);
        }
    }
}
