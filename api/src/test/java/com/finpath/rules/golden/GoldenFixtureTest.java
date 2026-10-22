package com.finpath.rules.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GoldenFixtureTest {

    private static final Function<String, String> NONE = k -> null;

    private static Function<String, String> of(Map<String, String> m) {
        return m::get;
    }

    @Test
    void cleanCloneReadsCommittedSample(@TempDir Path dir) {
        GoldenFixture f = GoldenFixture.load(NONE, NONE, dir.resolve("absent.local.properties"));
        assertEquals(GoldenFixture.Source.SAMPLE, f.source());
        assertTrue(f.sourceDescription().contains("SAMPLE"));
        for (String k : GoldenKeys.ALL) {
            assertTrue(f.rawValues().containsKey(k), "sample lacks key " + k);
        }
        assertTrue(f.paise(GoldenKeys.HOME_PRINCIPAL) > 0);
        assertTrue(f.decimal(GoldenKeys.HOME_RATE_PCT).signum() > 0);
        assertTrue(f.integer(GoldenKeys.HOME_TENURE_MONTHS) > 0);
    }

    @Test
    void missingRealWithRequireRealFailsExplicitly(@TempDir Path dir) {
        Function<String, String> sys = of(Map.of(GoldenFixture.ENV_REQUIRE_REAL, "true"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GoldenFixture.load(sys, NONE, dir.resolve("absent.local.properties")));
        assertTrue(e.getMessage().contains(GoldenFixture.ENV_REQUIRE_REAL));
    }

    @Test
    void requireRealViaEnvAlsoFails(@TempDir Path dir) {
        Function<String, String> env = of(Map.of(GoldenFixture.ENV_REQUIRE_REAL, "true"));
        assertThrows(IllegalStateException.class,
                () -> GoldenFixture.load(NONE, env, dir.resolve("absent.local.properties")));
    }

    @Test
    void explicitOverridePathThatIsMissingFailsRatherThanFallingBack(@TempDir Path dir) {
        Function<String, String> sys = of(Map.of(GoldenFixture.ENV_FIXTURE, dir.resolve("nope").toString()));
        assertThrows(IllegalStateException.class, () -> GoldenFixture.load(sys, NONE, dir.resolve("x")));
    }

    @Test
    void realFileIsPreferredAndOverrideHonoured(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("custom.local.properties");
        StringBuilder sb = new StringBuilder();
        for (String k : GoldenKeys.ALL) {
            sb.append(k).append('=').append(k.endsWith("_cycle_id") ? "2031-01" : "7").append('\n');
        }
        Files.writeString(real, sb.toString());
        GoldenFixture f = GoldenFixture.load(of(Map.of(GoldenFixture.ENV_FIXTURE, real.toString())), NONE,
                dir.resolve("absent"));
        assertEquals(GoldenFixture.Source.REAL, f.source());
        assertEquals(7L, f.paise(GoldenKeys.HOME_PRINCIPAL));
    }

    @Test
    void realFileMissingKeysFailsNamingKeysOnly(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("partial.local.properties");
        Files.writeString(real, GoldenKeys.HOME_PRINCIPAL + "=987654321\n");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GoldenFixture.load(NONE, NONE, real));
        assertTrue(e.getMessage().contains(GoldenKeys.HOME_RATE_PCT));
        assertFalse(e.getMessage().contains("987654321"));
    }

    @Test
    void parseErrorsDoNotEchoValues(@TempDir Path dir) throws Exception {
        Path real = dir.resolve("bad.local.properties");
        StringBuilder sb = new StringBuilder();
        for (String k : GoldenKeys.ALL) {
            sb.append(k).append("=12345x67\n");
        }
        Files.writeString(real, sb.toString());
        GoldenFixture f = GoldenFixture.load(NONE, NONE, real);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> f.paise(GoldenKeys.HOME_PRINCIPAL));
        assertFalse(e.getMessage().contains("12345x67"));
        assertThrows(IllegalStateException.class, () -> f.paise("no.such.key"));
    }

    @Test
    void sourceIsReportedExplicitlyAndValuesAreNot() {
        GoldenFixture.resetForTest();
        PrintStream orig = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        GoldenFixture f;
        try {
            System.setErr(new PrintStream(buf, true));
            f = GoldenFixture.shared();
        } finally {
            System.setErr(orig);
            GoldenFixture.resetForTest();
        }
        String out = buf.toString();
        assertTrue(out.contains("[golden] fixture source=" + f.source()), "source not reported");
        for (Map.Entry<String, String> e : f.rawValues().entrySet()) {
            String v = e.getValue().trim();
            if (v.length() >= 6) {
                assertFalse(out.contains(v), "output contains a fixture value for " + e.getKey());
            }
        }
    }
}
