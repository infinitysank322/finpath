package com.finpath.dpdp;

import java.util.List;

/**
 * Every table holding a user's personal data, listed once for the DPDP export path and once for
 * the account-deletion path. A new table is not done until it appears in both lists
 * (docs/PLAN.md, the [+TABLE] marker); DataSubjectRegistryTest fails by table name when a
 * migrated table is missing. DataSubjectService (T31) builds GET /me/export and DELETE /me over these lists.
 */
public final class DataSubjectRegistry {

    /** A table and the column that ties each row to its owner. */
    public record TableRef(String table, String ownerColumn) {
    }

    private static final List<TableRef> EXPORT = List.of(new TableRef("users", "id"),
            new TableRef("consent", "user_id"));

    /** Deletion lists children before parents; users is the root, so it comes last. */
    private static final List<TableRef> DELETE = List.of(new TableRef("consent", "user_id"),
            new TableRef("users", "id"));

    private DataSubjectRegistry() {
    }

    public static List<TableRef> exportTables() {
        return EXPORT;
    }

    public static List<TableRef> deleteTables() {
        return DELETE;
    }
}
