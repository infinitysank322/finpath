package com.finpath.dpdp;

import java.util.List;
import java.util.Set;

/**
 * Every table holding a user's personal data, listed once for the DPDP export path and once for
 * the account-deletion path. A new table is not done until it appears in both lists
 * (docs/PLAN.md, the [+TABLE] marker); DataSubjectRegistryTest fails by table name when a
 * migrated table is missing. DataSubjectService (T31) builds GET /me/export and DELETE /me over these lists.
 */
public final class DataSubjectRegistry {

    /** A table and the column that ties each row to its owner. */
    static final String PLAIN_ROW = "to_jsonb(t)";
    static final String ROW_WITH_CATEGORY_CODE =
            "to_jsonb(t) || jsonb_build_object('category_code', (SELECT c.code FROM category c WHERE c.id = t.category_id))";

    public record TableRef(String table, String ownerColumn, String exportRow) {

        /** Default export row: the table row as-is, in the table alias {@code t}. */
        public TableRef(String table, String ownerColumn) {
            this(table, ownerColumn, PLAIN_ROW);
        }
    }

    private static final List<TableRef> EXPORT = List.of(new TableRef("users", "id"),
            new TableRef("consent", "user_id"), new TableRef("plan_cycle", "user_id"),
            new TableRef("income_source", "user_id"),
            new TableRef("transaction", "user_id",
                    ROW_WITH_CATEGORY_CODE),
            new TableRef("recurring_template", "user_id", ROW_WITH_CATEGORY_CODE),
            new TableRef("category_budget", "user_id", ROW_WITH_CATEGORY_CODE),
            new TableRef("loan", "user_id"), new TableRef("goal", "user_id"));

    /**
     * Reference tables identical for every user: no user_id, no personal data, hence no RLS, not
     * exported and never deleted by DELETE /me. A table belongs here only after compliance review;
     * DataSubjectRegistryTest fails if a shared table gains a user_id column.
     */
    private static final Set<String> SHARED = Set.of("category");

    /** Deletion lists children before parents; users is the root, so it comes last. */
    private static final List<TableRef> DELETE = List.of(new TableRef("consent", "user_id"),
            new TableRef("plan_cycle", "user_id"), new TableRef("income_source", "user_id"),
            new TableRef("transaction", "user_id"), new TableRef("recurring_template", "user_id"),
            new TableRef("category_budget", "user_id"), new TableRef("loan", "user_id"),
            new TableRef("goal", "user_id"), new TableRef("users", "id"));

    private DataSubjectRegistry() {
    }

    public static List<TableRef> exportTables() {
        return EXPORT;
    }

    public static List<TableRef> deleteTables() {
        return DELETE;
    }

    public static Set<String> sharedTables() {
        return SHARED;
    }
}
