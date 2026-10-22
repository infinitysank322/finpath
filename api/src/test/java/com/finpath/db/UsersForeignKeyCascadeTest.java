package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Account deletion relies on the users row going last: every FK to users must be ON DELETE CASCADE. */
class UsersForeignKeyCascadeTest {

    /** Returns one entry per REFERENCES users(...) clause that lacks ON DELETE CASCADE. */
    static List<String> violations(String code) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("references\\s+(?:public\\.)?users\\s*\\([^)]*\\)([^,;\\n]*)").matcher(code);
        while (m.find()) {
            if (!m.group(1).replaceAll("\\s+", " ").contains("on delete cascade")) {
                out.add(code.substring(Math.max(0, m.start() - 60), m.end()).replaceAll("\\s+", " ").strip());
            }
        }
        return out;
    }

    @Test
    void everyMigratedForeignKeyToUsersCascadesOnDelete() throws IOException {
        assertThat(violations(MigrationScan.allCode().toLowerCase()))
                .as("foreign keys referencing users without ON DELETE CASCADE").isEmpty();
    }

    @Test
    void detectorFlagsAForeignKeyWithoutCascade() {
        assertThat(violations("user_id uuid references users (id),\nx int")).hasSize(1);
        assertThat(violations("user_id uuid references users (id) on delete  cascade,\nx int")).isEmpty();
        assertThat(violations("user_id uuid not null references users(id) on delete restrict")).hasSize(1);
    }

    @Test
    void migrationsDoReferenceUsersSoTheScanIsNotVacuous() throws IOException {
        assertThat(MigrationScan.allCode().toLowerCase()).containsPattern("references\\s+users");
    }

    @Nested
    class AgainstDatabase {

        AgainstDatabase() {
            TestDb.requireAndMigrate();
        }

        @Test
        void everyForeignKeyToUsersInTheCatalogCascades() throws SQLException {
            List<String> bad = new ArrayList<>();
            try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT conrelid::regclass::text || '.' || conname FROM pg_constraint "
                            + "WHERE contype = 'f' AND confrelid = 'public.users'::regclass AND confdeltype <> 'c'")) {
                while (rs.next()) {
                    bad.add(rs.getString(1));
                }
            }
            assertThat(bad).as("foreign keys to users without ON DELETE CASCADE").isEmpty();
        }
    }
}
