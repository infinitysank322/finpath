package com.finpath.rules.golden;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Loads golden persona figures from a gitignored local file, falling back to a committed
 * anonymised sample. The source in use is always reported; values never are. No exception
 * message or log line produced here contains a fixture value.
 */
public final class GoldenFixture {

    public enum Source { REAL, SAMPLE }

    public static final String ENV_FIXTURE = "FINPATH_GOLDEN_FIXTURE";
    public static final String ENV_REQUIRE_REAL = "FINPATH_GOLDEN_REQUIRE_REAL";
    public static final String SAMPLE_RESOURCE = "/golden/persona.sample.properties";
    public static final String DEFAULT_REAL_RELATIVE = "src/test/resources/golden/persona.local.properties";

    private static GoldenFixture shared;

    private final Source source;
    private final String sourceDescription;
    private final Map<String, String> values;

    private GoldenFixture(Source source, String sourceDescription, Map<String, String> values) {
        this.source = source;
        this.sourceDescription = sourceDescription;
        this.values = Collections.unmodifiableMap(new TreeMap<>(values));
    }

    /** Shared instance resolved from system properties then environment; logs the source once. */
    public static synchronized GoldenFixture shared() {
        if (shared == null) {
            shared = load(System::getProperty, System::getenv, defaultRealPath());
            System.err.println("[golden] " + shared.sourceDescription());
        }
        return shared;
    }

    static synchronized void resetForTest() {
        shared = null;
    }

    /**
     * Resolve the fixture. A system property takes precedence over the environment variable of
     * the same name.
     */
    public static GoldenFixture load(Function<String, String> sysProps, Function<String, String> env,
            Path defaultRealPath) {
        String override = firstNonBlank(sysProps.apply(ENV_FIXTURE), env.apply(ENV_FIXTURE));
        boolean requireReal = Boolean.parseBoolean(
                firstNonBlank(sysProps.apply(ENV_REQUIRE_REAL), env.apply(ENV_REQUIRE_REAL)));
        Path real = override != null ? Path.of(override) : defaultRealPath;

        if (Files.isRegularFile(real)) {
            Map<String, String> v = read(real);
            requireAllKeys(v, "real fixture");
            return new GoldenFixture(Source.REAL, "fixture source=REAL file=" + real, v);
        }
        if (override != null) {
            throw new IllegalStateException(
                    "Golden fixture named by " + ENV_FIXTURE + " does not exist: " + real);
        }
        if (requireReal) {
            throw new IllegalStateException("Real golden fixture required (" + ENV_REQUIRE_REAL
                    + "=true) but not found at " + real);
        }
        Map<String, String> v = readSample();
        requireAllKeys(v, "sample fixture");
        return new GoldenFixture(Source.SAMPLE,
                "fixture source=SAMPLE (real file absent at " + real + "; set " + ENV_REQUIRE_REAL
                        + "=true to make that a failure). Golden assertions run against FAKE figures.",
                v);
    }

    public static Path defaultRealPath() {
        Path p = Path.of(DEFAULT_REAL_RELATIVE);
        if (!Files.isDirectory(Path.of("src/test")) && Files.isDirectory(Path.of("api/src/test"))) {
            p = Path.of("api").resolve(DEFAULT_REAL_RELATIVE);
        }
        return p;
    }

    public Source source() {
        return source;
    }

    public String sourceDescription() {
        return sourceDescription;
    }

    /** Unmodifiable view for scan tests only; never print it. */
    public Map<String, String> rawValues() {
        return values;
    }

    public long paise(String key) {
        String s = raw(key);
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Fixture key is not a long: " + key);
        }
    }

    public BigDecimal decimal(String key) {
        try {
            return new BigDecimal(raw(key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Fixture key is not a decimal: " + key);
        }
    }

    public int integer(String key) {
        try {
            return Integer.parseInt(raw(key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Fixture key is not an int: " + key);
        }
    }

    public String text(String key) {
        return raw(key).trim();
    }

    private String raw(String key) {
        String s = values.get(Objects.requireNonNull(key));
        if (s == null) {
            throw new IllegalStateException("Fixture key missing: " + key);
        }
        return s;
    }

    private static void requireAllKeys(Map<String, String> v, String what) {
        List<String> missing = new ArrayList<>();
        for (String k : GoldenKeys.ALL) {
            if (!v.containsKey(k) || v.get(k).isBlank()) {
                missing.add(k);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(what + " is missing keys: " + missing);
        }
    }

    private static Map<String, String> read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return toMap(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read golden fixture " + file, e);
        }
    }

    private static Map<String, String> readSample() {
        try (InputStream in = GoldenFixture.class.getResourceAsStream(SAMPLE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Committed sample fixture missing: " + SAMPLE_RESOURCE);
            }
            return toMap(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read sample fixture", e);
        }
    }

    private static Map<String, String> toMap(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        Map<String, String> m = new TreeMap<>();
        for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k));
        }
        return m;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
