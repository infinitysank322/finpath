package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Money is paise in bigint: no migration may introduce a floating-point (or money) type. */
class SchemaLintTest {

    private static Path migrationDir() {
        Path p = Path.of("src/main/resources/db/migration");
        if (!Files.isDirectory(p)) {
            p = Path.of("api/src/main/resources/db/migration");
        }
        return p;
    }

    private static List<Path> migrations() throws IOException {
        Path dir = migrationDir();
        assertThat(dir).as("migration dir from %s", Path.of("").toAbsolutePath()).isDirectory();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    @Test
    void noMigrationDeclaresAFloatingPointType() throws IOException {
        List<Path> files = migrations();
        assertThat(files).isNotEmpty();
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            for (String v : SchemaLint.violations(Files.readString(f, StandardCharsets.UTF_8))) {
                hits.add(f.getFileName() + " " + v);
            }
        }
        assertThat(hits).as("floating-point or money types in migrations; store paise as bigint").isEmpty();
    }

    @Test
    void baselineRecordsThePaiseConventionAndAppRole() throws IOException {
        Path v1 = migrationDir().resolve("V1__baseline.sql");
        String sql = Files.readString(v1, StandardCharsets.UTF_8);
        assertThat(sql).contains("COMMENT ON SCHEMA public").contains("paise").containsIgnoringCase("bigint");
        assertThat(sql).contains("CREATE ROLE app_user NOLOGIN").doesNotContainIgnoringCase("PASSWORD '");
    }

    @Test
    void flagsADoublePrecisionMoneyColumn() {
        String sql = """
                CREATE TABLE loan (
                    id bigint PRIMARY KEY,
                    outstanding_amount double precision NOT NULL
                );
                """;
        assertThat(SchemaLint.violations(sql)).containsExactly("line 3: double precision");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE TABLE t (amount DOUBLE PRECISION);",
        "CREATE TABLE t (amount double\n    precision);",
        "CREATE TABLE t (amount float);",
        "CREATE TABLE t (amount FLOAT(53));",
        "CREATE TABLE t (amount float4);",
        "CREATE TABLE t (amount float8);",
        "CREATE TABLE t (amount real);",
        "CREATE TABLE t (amount money);",
        "ALTER TABLE t ADD COLUMN emi real;",
        "ALTER TABLE t ALTER COLUMN emi TYPE float8;",
        "SELECT amount_paise::double precision FROM t;",
        "DO $$ BEGIN EXECUTE 'x'; CREATE TABLE t (a float8); END $$;",
        "COMMENT ON COLUMN t.a IS E'don\\'t'; ALTER TABLE t ADD b float8; COMMENT ON COLUMN t.b IS 'x';",
    })
    void flagsEveryFloatingPointSpelling(String sql) {
        assertThat(SchemaLint.violations(sql)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE TABLE t (amount_paise bigint NOT NULL);",
        "-- never use double precision or float for money\nCREATE TABLE t (a bigint);",
        "/* real money is paise; /* nested float */ still a comment */ CREATE TABLE t (a bigint);",
        "COMMENT ON SCHEMA public IS 'no float, real or double precision; it''s paise';",
        "CREATE TABLE t (\"real\" bigint, \"float\" bigint);",
        "CREATE TABLE t (realised_paise bigint, floating_rate_bps integer, money_paise bigint);",
    })
    void ignoresCommentsStringsIdentifiersAndLookalikes(String sql) {
        assertThat(SchemaLint.violations(sql)).isEmpty();
    }
}
