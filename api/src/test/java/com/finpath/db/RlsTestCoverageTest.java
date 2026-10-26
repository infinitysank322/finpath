package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.dpdp.DataSubjectRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The full cross-tenant matrix: every migrated per-user table has a {@link CrossTenantTest}
 * subclass naming it. Static, so it runs without a database and fails by table name.
 */
class RlsTestCoverageTest {

    private static final Pattern TABLE_METHOD = Pattern.compile(
            "protected\\s+String\\s+table\\(\\)\\s*\\{\\s*return\\s+\"([a-z_][a-z0-9_]*)\"", Pattern.DOTALL);

    private static List<String> migratedTables() throws IOException {
        return MigrationScan.tables(MigrationScan.allCode()).stream().map(MigrationScan.Table::name).toList();
    }

    /** Tables named by the table() override of every class extending CrossTenantTest. */
    static List<String> tablesWithCrossTenantTest() throws IOException {
        Path dir = Path.of("src/test/java");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("api/src/test/java");
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(x -> x.toString().endsWith("Test.java")).toList()) {
                String src = Files.readString(f);
                if (!src.contains("extends CrossTenantTest")) {
                    continue;
                }
                Matcher m = TABLE_METHOD.matcher(src);
                if (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    @Test
    void everyPerUserTableHasACrossTenantTest() throws IOException {
        List<String> covered = tablesWithCrossTenantTest();
        for (String table : migratedTables()) {
            if (DataSubjectRegistry.sharedTables().contains(table)) {
                continue;
            }
            assertThat(covered).as("table %s has no CrossTenantTest subclass", table).contains(table);
        }
    }

    @Test
    void theMatrixHasExactlyOneTestPerTable() throws IOException {
        List<String> covered = tablesWithCrossTenantTest();
        assertThat(covered).doesNotHaveDuplicates();
    }

    @Test
    void onlyTheReferenceTaxonomyIsExemptFromTheMatrix() {
        assertThat(DataSubjectRegistry.sharedTables()).containsExactly("category");
    }
}
