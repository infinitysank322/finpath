package com.finpath.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** T74: the dashboard package orchestrates; all money maths lives in the rules package. */
class DashboardNoArithmeticTest {

    private static final Pattern ARITHMETIC = Pattern.compile(
            "\\.(add|subtract|multiply|divide|plus|minus|times|addExact|multiplyExact|subtractExact)\\(|\\bdouble\\b|\\bfloat\\b");

    @Test
    void noMoneyArithmeticInTheDashboardPackage() throws IOException {
        Path dir = Path.of("src/main/java/com/finpath/dashboard");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path f : files) {
            assertThat(ARITHMETIC.matcher(stripComments(Files.readString(f))).find())
                    .as("%s must not do money arithmetic", f.getFileName()).isFalse();
        }
    }

    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
