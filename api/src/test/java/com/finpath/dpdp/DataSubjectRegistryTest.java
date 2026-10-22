package com.finpath.dpdp;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.dpdp.DataSubjectRegistry.TableRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The [+TABLE] trio, parts (a) and (b): every migrated table is in both DPDP registries. */
class DataSubjectRegistryTest {

    private static List<String> migratedTables() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("api/src/main/resources/db/migration");
        }
        Pattern p = Pattern.compile("(?im)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)");
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".sql")).sorted().flatMap(f -> {
                try {
                    Matcher m = p.matcher(Files.readString(f));
                    List<String> names = new java.util.ArrayList<>();
                    while (m.find()) {
                        names.add(m.group(1).toLowerCase());
                    }
                    return names.stream();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
    }

    @Test
    void usersIsInTheExportRegistry() {
        assertThat(DataSubjectRegistry.exportTables()).extracting(TableRef::table).contains("users");
    }

    @Test
    void usersIsInTheDeleteRegistry() {
        assertThat(DataSubjectRegistry.deleteTables()).extracting(TableRef::table).contains("users");
    }

    @Test
    void usersIsLastInTheDeleteRegistry() {
        assertThat(DataSubjectRegistry.deleteTables()).last().extracting(TableRef::table).isEqualTo("users");
    }

    /** Fails with a message naming each migrated table that the registry lacks. */
    static void assertRegistryCovers(String label, List<TableRef> registry, List<String> migrated) {
        for (String table : migrated) {
            assertThat(registry).extracting(TableRef::table)
                    .as("%s registry must list table %s", label, table).contains(table);
        }
    }

    @Test
    void everyMigratedTableIsInBothRegistries() throws IOException {
        assertRegistryCovers("export", DataSubjectRegistry.exportTables(), migratedTables());
        assertRegistryCovers("delete", DataSubjectRegistry.deleteTables(), migratedTables());
    }

    @Test
    void removingATableFromEitherRegistryFailsTheCheckAndNamesTheTable() throws IOException {
        for (String victim : migratedTables()) {
            List<TableRef> export = DataSubjectRegistry.exportTables().stream()
                    .filter(t -> !t.table().equals(victim)).toList();
            List<TableRef> delete = DataSubjectRegistry.deleteTables().stream()
                    .filter(t -> !t.table().equals(victim)).toList();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertRegistryCovers("export", export, migratedTables()))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("export registry must list table " + victim);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertRegistryCovers("delete", delete, migratedTables()))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("delete registry must list table " + victim);
        }
    }

    @Test
    void consentIsRegisteredAndDeletedBeforeUsers() {
        assertThat(DataSubjectRegistry.exportTables()).extracting(TableRef::table).contains("consent");
        assertThat(DataSubjectRegistry.deleteTables()).extracting(TableRef::table).containsExactly("consent", "plan_cycle", "income_source", "users");
    }

    @Test
    void everyDeleteEntryComesBeforeTheTablesItReferences() throws IOException {
        // Children before parents: any table declared REFERENCES users must precede users.
        List<String> order = DataSubjectRegistry.deleteTables().stream().map(TableRef::table).toList();
        for (String child : migratedTables()) {
            if (!child.equals("users")) {
                assertThat(order.indexOf(child)).as("delete order of %s", child).isLessThan(order.indexOf("users"));
            }
        }
    }

    @Test
    void registriesListNoUnknownTable() throws IOException {
        List<String> migrated = migratedTables();
        assertThat(DataSubjectRegistry.exportTables()).extracting(TableRef::table).isSubsetOf(migrated);
        assertThat(DataSubjectRegistry.deleteTables()).extracting(TableRef::table).isSubsetOf(migrated);
    }
}
