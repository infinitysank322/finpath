package com.finpath.dpdp;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.db.MigrationScanAccess;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * "A consent row is required before the first write": every business table's policy must put
 * finpath_has_consent() in its WITH CHECK, so Postgres refuses the first write before consent. A
 * new table whose policy omits it fails here by name. users (written by sign-in before consent can
 * exist) and consent (it is how consent is given) are the only exceptions.
 */
class ConsentGateMetadataTest {

    static final Set<String> EXEMPT = Set.of("users", "consent");

    static List<String> tablesMissingTheGate(String code) {
        List<String> failures = new ArrayList<>();
        for (String table : MigrationScanAccess.tableNames(code)) {
            if (EXEMPT.contains(table)) {
                continue;
            }
            Matcher m = Pattern.compile("create\\s+policy\\s+\\w+\\s+on\\s+" + Pattern.quote(table) + "\\b[^;]*;").matcher(code);
            boolean gated = false;
            while (m.find()) {
                gated |= m.group().contains("finpath_has_consent()");
            }
            if (!gated) {
                failures.add(table);
            }
        }
        return failures;
    }

    @Test
    void everyBusinessTablePolicyRequiresConsentForWrites() throws IOException {
        assertThat(tablesMissingTheGate(MigrationScanAccess.allCode().toLowerCase()))
                .as("business tables whose policy lacks finpath_has_consent() in WITH CHECK").isEmpty();
    }

    @Test
    void detectorNamesAnUngatedTable() {
        String sql = "create table goal (\n id uuid\n);\n"
                + "create policy goal_self on goal using (true) with check (true);\n"
                + "create table holding (\n id uuid\n);\n"
                + "create policy holding_self on holding using (true) with check (finpath_has_consent());\n";
        assertThat(tablesMissingTheGate(sql)).containsExactly("goal");
    }
}
