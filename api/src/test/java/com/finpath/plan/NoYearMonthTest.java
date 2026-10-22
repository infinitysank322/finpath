package com.finpath.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** T34: all cycle maths goes through CycleCalendar; no main source may use java.time.YearMonth. */
class NoYearMonthTest {

    private static final Pattern USE = Pattern.compile("\\bYearMonth\\b");

    static Path mainRoot() {
        Path p = Path.of("src/main");
        return Files.isDirectory(p) ? p : Path.of("api/src/main");
    }

    static List<String> offenders(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return USE.matcher(Files.readString(f)).find();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(Path::toString).toList();
        }
    }

    @Test
    void noMainSourceUsesYearMonth() throws IOException {
        assertThat(Files.isDirectory(mainRoot())).isTrue();
        assertThat(offenders(mainRoot())).as("main sources mentioning YearMonth").isEmpty();
    }

    @Test
    void detectorFindsAUse(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("A.java"), "import java.time.YearMonth;\nclass A {}\n");
        Files.writeString(dir.resolve("B.java"), "class B {}\n");
        assertThat(offenders(dir)).hasSize(1).first().asString().endsWith("A.java");
    }
}
