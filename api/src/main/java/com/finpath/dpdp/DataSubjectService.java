package com.finpath.dpdp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finpath.dpdp.DataSubjectRegistry.TableRef;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DPDP data-principal rights over the registry: a full export and a real deletion, both generic
 * over {@link DataSubjectRegistry}, so a table added to the registry is exported and erased with
 * no change here.
 *
 * <p>Both run in one transaction on the RLS-bound connection (T30): the database, not the WHERE
 * clause, is what confines them to the caller's rows; the WHERE clause is the second line of
 * defence. Table and column names come only from the registry and are checked against a strict
 * identifier pattern before being put in SQL. Nothing here logs row content.
 */
@Service
public class DataSubjectService {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    static final int EXPORT_FORMAT_VERSION = 1;

    private final DbAccess db;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final List<TableRef> exportTables;
    private final List<TableRef> deleteTables;

    @Autowired
    public DataSubjectService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx,
            ObjectMapper mapper) {
        this(new DbAccess(jdbc, tx), mapper, Clock.systemUTC(), DataSubjectRegistry.exportTables(),
                DataSubjectRegistry.deleteTables());
    }

    DataSubjectService(DbAccess db, ObjectMapper mapper, Clock clock, List<TableRef> exportTables,
            List<TableRef> deleteTables) {
        exportTables.forEach(DataSubjectService::checkIdentifiers);
        deleteTables.forEach(DataSubjectService::checkIdentifiers);
        this.db = db;
        this.mapper = mapper;
        this.clock = clock;
        this.exportTables = exportTables;
        this.deleteTables = deleteTables;
    }

    private static void checkIdentifiers(TableRef ref) {
        if (!IDENTIFIER.matcher(ref.table()).matches() || !IDENTIFIER.matcher(ref.ownerColumn()).matches()) {
            throw new IllegalStateException("registry entry is not a plain SQL identifier");
        }
        if (!DataSubjectRegistry.PLAIN_ROW.equals(ref.exportRow())
                && !DataSubjectRegistry.ROW_WITH_CATEGORY_CODE.equals(ref.exportRow())) {
            throw new IllegalStateException("registry export fragment is not on the allowlist");
        }
    }

    /**
     * {@code {"format":1,"exportedAt":…,"tables":{"<table>":[row,…],…}}}: every registered table,
     * an empty array when the user has no rows in it. Money columns stay integer paise.
     */
    public JsonNode export(UUID userId) {
        JdbcTemplate jdbc = db.jdbc();
        return db.tx().execute(status -> {
            ObjectNode root = mapper.createObjectNode();
            root.put("format", EXPORT_FORMAT_VERSION);
            root.put("exportedAt", clock.instant().toString());
            ObjectNode tables = root.putObject("tables");
            for (TableRef ref : exportTables) {
                String json = jdbc.queryForObject("SELECT coalesce(jsonb_agg(" + ref.exportRow() + "), '[]'::jsonb)::text FROM "
                        + ref.table() + " t WHERE t." + ref.ownerColumn() + " = ?", String.class, userId);
                try {
                    tables.set(ref.table(), mapper.readTree(json));
                } catch (java.io.IOException e) {
                    throw new UncheckedIOException("export row set was not valid JSON", e);
                }
            }
            return root;
        });
    }

    /** Deletes the user's rows from every registered table, children before parents, in one transaction. */
    public void delete(UUID userId) {
        JdbcTemplate jdbc = db.jdbc();
        db.tx().executeWithoutResult(status -> {
            for (TableRef ref : deleteTables) {
                jdbc.update("DELETE FROM " + ref.table() + " WHERE " + ref.ownerColumn() + " = ?", userId);
            }
        });
    }
}
