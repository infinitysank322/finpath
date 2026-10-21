package com.finpath.rules.golden;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No fixture amount may appear in api/ or web/ sources other than the fixture file itself.
 * With the real fixture loaded this protects the persona; with the sample it checks the sample
 * values stay confined to the sample file. Hits are reported as file and key, never value.
 */
class GoldenFixtureScanTest {

    private static final Set<String> SKIP_DIRS = Set.of("target", "node_modules", "dist", ".angular", ".git",
            ".gradle", "build", ".mvn");
    private static final Set<String> TEXT_EXT = Set.of("java", "ts", "html", "scss", "css", "json", "yml",
            "yaml", "properties", "xml", "sql", "md", "js", "kts", "gradle", "txt", "mjs", "cjs");
    private static final int MIN_DIGITS = 5;

    @Test
    void fixtureAmountsAppearInNoOtherApiOrWebFile() throws IOException {
        GoldenFixture f = GoldenFixture.load(System::getProperty, System::getenv, GoldenFixture.defaultRealPath());
        List<Needle> needles = needles(f);
        assertTrue(!needles.isEmpty(), "no amount-like keys to scan for");

        Path apiRoot = Files.isDirectory(Path.of("src/test")) ? Path.of("").toAbsolutePath()
                : Path.of("api").toAbsolutePath();
        Path repo = apiRoot.getParent();
        List<String> hits = new ArrayList<>();
        for (String top : new String[] {"api", "web"}) {
            Path root = repo.resolve(top);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    if (skip(root, p)) {
                        continue;
                    }
                    String text;
                    try {
                        text = Files.readString(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        continue;
                    }
                    for (Needle n : needles) {
                        if (n.pattern.matcher(text).find()) {
                            hits.add(repo.relativize(p) + " <- key " + n.key);
                        }
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "Fixture amounts found outside the fixture file: " + hits);
    }

    private static boolean skip(Path root, Path p) {
        String name = p.getFileName().toString();
        if (name.contains(".local.") || name.equals("persona.sample.properties")) {
            return true;
        }
        for (Path part : root.relativize(p)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 || !TEXT_EXT.contains(name.substring(dot + 1));
    }

    private record Needle(String key, Pattern pattern) {
    }

    private static List<Needle> needles(GoldenFixture f) {
        List<Needle> out = new ArrayList<>();
        for (Map.Entry<String, String> e : f.rawValues().entrySet()) {
            if (!e.getKey().endsWith("_paise")) {
                continue;
            }
            BigDecimal paise;
            try {
                paise = new BigDecimal(e.getValue().trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            List<String> forms = new ArrayList<>();
            List<String> abbreviated = new ArrayList<>();
            forms.add(paise.toPlainString());
            BigDecimal rupees = paise.movePointLeft(2);
            if (rupees.stripTrailingZeros().scale() <= 0) {
                long r = rupees.longValueExact();
                forms.add(Long.toString(r));
                forms.add(NumberFormat.getIntegerInstance(Locale.US).format(r));
                forms.add(NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(r));
            }
            for (long unit : new long[] {100_000L, 10_000_000L}) {
                String suffix = unit == 100_000L ? " L" : " Cr";
                BigDecimal scaled = rupees.divide(BigDecimal.valueOf(unit), 4, java.math.RoundingMode.HALF_UP);
                for (int dp = 1; dp <= 2; dp++) {
                    String num = scaled.setScale(dp, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                    if (num.contains(".")) {
                        abbreviated.add(num + suffix);
                    }
                }
            }
            for (String form : abbreviated) {
                out.add(new Needle(e.getKey(), Pattern.compile("(?<![\\d,.])" + Pattern.quote(form) + "\\b")));
            }
            for (String form : forms) {
                if (form.replaceAll("\\D", "").length() >= MIN_DIGITS) {
                    out.add(new Needle(e.getKey(), Pattern.compile("(?<![\\d,.])" + Pattern.quote(form) + "(?![\\d])")));
                }
            }
        }
        return out;
    }
}
