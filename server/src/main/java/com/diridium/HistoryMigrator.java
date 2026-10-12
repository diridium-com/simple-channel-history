// SPDX-FileCopyrightText: Copyright 2024 Kiran Ayyagari
// SPDX-FileCopyrightText: Copyright 2025-2026 Diridium Technologies Inc.
// SPDX-License-Identifier: MPL-2.0

package com.diridium;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.mirth.connect.server.migration.Migrator;
import com.mirth.connect.model.util.MigrationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrator for creating history tables used by the simple-channel-history plugin.
 * Creates channel_history, code_template_history, deleted_channel, and
 * deleted_code_template tables on first startup.
 */
public class HistoryMigrator extends Migrator {

    private static final Logger log = LoggerFactory.getLogger(HistoryMigrator.class);

    @Override
    public void migrate() throws MigrationException {
        createIfMissing("/" + getDatabaseType() + "-history-tables.sql", "History tables",
                "channel_history", "code_template_history");
        createIfMissing("/" + getDatabaseType() + "-deleted-tables.sql", "Deleted item tables",
                "deleted_channel", "deleted_code_template");
    }

    /**
     * The engine runs every plugin migrator on every startup, so a script runs only when its
     * tables are missing. Derby has no CREATE TABLE IF NOT EXISTS and Oracle only has it from
     * 23ai, so their scripts can't use it, and letting the create fail logged a warning with a
     * stack trace on every restart (#27). Failures are logged at ERROR because stock OIE logs
     * nothing below it.
     *
     * This only creates tables that are missing. A change to an existing table (a new column,
     * a type change) never reaches an upgraded install this way: it needs a stored schema
     * version and numbered steps, as RbacMigrator in diridium-com/role-based-access-control does.
     */
    private void createIfMissing(String scriptName, String description, String... tables) {
        try {
            boolean allExist = true;
            for (String table : tables) {
                allExist &= tableExists(table);
            }
            if (allExist) {
                return;
            }
            executeScript(scriptName);
            log.info("{} created", description);
        } catch (Exception e) {
            log.error("{} could not be created: {}", description, e.getMessage(), e);
        }
    }

    /**
     * Looks only in the engine's own database and schema. The engine's DatabaseUtil.tableExists
     * passes no catalog, which MySQL Connector/J answers from every database the user can see.
     * Derby and Oracle store unquoted names in upper case, the others in lower case.
     */
    private boolean tableExists(String table) throws SQLException {
        Connection connection = getConnection();
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = currentSchema(connection);
        String escape = meta.getSearchStringEscape();
        for (String name : new String[] { table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT) }) {
            // '_' is a wildcard in metadata patterns
            String pattern = escape == null ? name : name.replace("_", escape + "_");
            try (ResultSet rs = meta.getTables(catalog, schema, pattern, new String[] { "TABLE" })) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    // jTDS 1.3.1 (the engine's default SQL Server driver) implements Connection.getSchema() as a stub
    // that throws AbstractMethodError; a driver that doesn't support it otherwise throws SQLException.
    // A null schema still finds the table, since the catalog narrows the search.
    private static String currentSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (AbstractMethodError | SQLException e) {
            return null;
        }
    }

    @Override
    public List<String> getUninstallStatements() throws MigrationException {
        return Arrays.asList(
                "DROP TABLE channel_history",
                "DROP TABLE code_template_history",
                "DROP TABLE deleted_channel",
                "DROP TABLE deleted_code_template");
    }

    @Override
    public void migrateSerializedData() throws MigrationException {
        // No serialized data migration needed
    }
}
