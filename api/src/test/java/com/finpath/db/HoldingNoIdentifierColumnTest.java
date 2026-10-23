package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T65: holding has no folio, demat, account, PAN or other identifier column (and no float money column). */
class HoldingNoIdentifierColumnTest {

    @Test
    void holdingColumnsAreExactlyTheLabelAndPaiseAmounts() throws Exception {
        TestDb.requireAndMigrate();
        List<String> cols = new ArrayList<>();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'holding' "
                        + "AND table_schema = current_schema()")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        assertThat(cols).containsExactlyInAnyOrder("id", "user_id", "asset_class", "name", "invested_paise",
                "current_value_paise", "monthly_contribution_paise", "created_at", "updated_at");
        assertThat(cols).noneMatch(n -> n.matches(".*(folio|demat|account|acct|pan|uan|aadhaar|isin|number|"
                + "card|dp_id|client_id).*"));
    }
}
