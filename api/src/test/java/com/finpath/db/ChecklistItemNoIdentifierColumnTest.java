package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T71: checklist_item has no account, folio, PAN or other identifier column (and no float money column). */
class ChecklistItemNoIdentifierColumnTest {

    @Test
    void columnsAreExactlyTheKeysAndPaiseAmount() throws Exception {
        TestDb.requireAndMigrate();
        List<String> cols = new ArrayList<>();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'checklist_item' "
                        + "AND table_schema = current_schema()")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        assertThat(cols).containsExactlyInAnyOrder("id", "user_id", "plan_cycle_id", "type", "source_ref",
                "amount_paise", "done_at", "created_at");
        assertThat(cols).noneMatch(n -> n.matches(".*(folio|demat|account|acct|pan|uan|aadhaar|isin|number|"
                + "card|dp_id|client_id).*"));
    }
}
