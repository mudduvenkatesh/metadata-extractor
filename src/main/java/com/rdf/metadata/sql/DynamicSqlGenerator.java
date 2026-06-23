package com.rdf.metadata.sql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates dynamic SQL statements from {@link ShapeTable} objects that were
 * parsed from a SHACL shapes model.
 *
 * <h3>Supported statement types</h3>
 * <ul>
 *   <li>{@link SqlStatementType#CREATE_TABLE} — full DDL with types, NOT NULL, PK, FK constraints</li>
 *   <li>{@link SqlStatementType#SELECT_ALL}   — {@code SELECT col1, col2 ... FROM table}</li>
 *   <li>{@link SqlStatementType#SELECT_BY_PK} — SELECT with {@code WHERE pk1 = :pk1 ...}</li>
 *   <li>{@link SqlStatementType#INSERT}        — {@code INSERT INTO ... VALUES (:col, ...)}</li>
 *   <li>{@link SqlStatementType#UPDATE_BY_PK} — {@code UPDATE ... SET col = :col WHERE pk = :pk}</li>
 *   <li>{@link SqlStatementType#DELETE_BY_PK} — {@code DELETE FROM table WHERE pk = :pk}</li>
 * </ul>
 *
 * <p>All placeholders use the named parameter style {@code :columnName}, compatible with
 * Spring's {@link org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate}.
 *
 * <p>Identifiers are quoted with double-quotes for ANSI/PostgreSQL compatibility,
 * or left unquoted for Snowflake (which is case-insensitive by default).
 */
@Slf4j
@Component
public class DynamicSqlGenerator {

    /**
     * Generate all requested statement types for a single table shape.
     *
     * @param table    the shape table parsed from the SHACL model
     * @param types    which statement types to generate
     * @param dialect  the target SQL dialect
     * @return list of {@link SqlStatement}s in the same order as {@code types}
     */
    public List<SqlStatement> generate(ShapeTable table,
                                        List<SqlStatementType> types,
                                        SqlDialect dialect) {
        List<SqlStatement> results = new ArrayList<>();
        for (SqlStatementType type : types) {
            SqlStatement stmt = switch (type) {
                case CREATE_TABLE  -> createTable(table, dialect);
                case SELECT_ALL    -> selectAll(table, dialect);
                case SELECT_BY_PK  -> selectByPk(table, dialect);
                case INSERT        -> insert(table, dialect);
                case UPDATE_BY_PK  -> updateByPk(table, dialect);
                case DELETE_BY_PK  -> deleteByPk(table, dialect);
            };
            results.add(stmt);
            log.debug("Generated {} for table '{}'", type, table.getTableName());
        }
        return results;
    }

    /**
     * Convenience: generate all statement types for every table in the list.
     */
    public List<SqlStatement> generateAll(List<ShapeTable> tables,
                                           List<SqlStatementType> types,
                                           SqlDialect dialect) {
        List<SqlStatement> all = new ArrayList<>();
        for (ShapeTable table : tables) {
            all.addAll(generate(table, types, dialect));
        }
        return all;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CREATE TABLE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a {@code CREATE TABLE} DDL statement.
     *
     * <p>Example output (PostgreSQL):
     * <pre>{@code
     * CREATE TABLE "Orders" (
     *     "ORDER_ID"    BIGINT         NOT NULL,
     *     "CUSTOMER_ID" BIGINT         NOT NULL,
     *     "ORDER_DATE"  TIMESTAMP      NOT NULL,
     *     "STATUS"      VARCHAR(50)    NOT NULL,
     *     "TOTAL_AMOUNT" NUMERIC,
     *     CONSTRAINT pk_Orders PRIMARY KEY ("ORDER_ID"),
     *     CONSTRAINT fk_Orders_Customers FOREIGN KEY ("CUSTOMER_ID") REFERENCES "Customers" ("CUSTOMER_ID")
     * );
     * }</pre>
     */
    private SqlStatement createTable(ShapeTable table, SqlDialect dialect) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(quote(table.getTableName(), dialect)).append(" (\n");

        List<String> lines = new ArrayList<>();

        // ── Column definitions ────────────────────────────────────────────────
        for (ShapeColumn col : table.getColumns()) {
            lines.add("    " + buildColumnDefinition(col, dialect));
        }

        // ── PRIMARY KEY constraint ────────────────────────────────────────────
        if (!table.getPrimaryKeyColumns().isEmpty()) {
            String pkCols = table.getPrimaryKeyColumns().stream()
                    .map(pk -> quote(pk, dialect))
                    .collect(Collectors.joining(", "));
            lines.add("    CONSTRAINT pk_" + table.getTableName()
                    + " PRIMARY KEY (" + pkCols + ")");
        }

        // ── FOREIGN KEY constraints ───────────────────────────────────────────
        for (ShapeColumn col : table.getColumns()) {
            if (col.isForeignKey() && col.getReferencedTable() != null) {
                // Assume the referenced PK column has the same name as this FK column
                lines.add("    CONSTRAINT fk_" + table.getTableName() + "_" + col.getReferencedTable()
                        + " FOREIGN KEY (" + quote(col.getColumnName(), dialect) + ")"
                        + " REFERENCES " + quote(col.getReferencedTable(), dialect)
                        + " (" + quote(col.getColumnName(), dialect) + ")");
            }
        }

        sb.append(String.join(",\n", lines));
        sb.append("\n);");

        return SqlStatement.builder()
                .type(SqlStatementType.CREATE_TABLE)
                .tableName(table.getTableName())
                .sql(sb.toString())
                .dialect(dialect)
                .bindParameters(List.of())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SELECT ALL
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a {@code SELECT} statement returning all columns.
     *
     * <pre>{@code
     * SELECT "ORDER_ID", "CUSTOMER_ID", "ORDER_DATE", "STATUS", "TOTAL_AMOUNT"
     * FROM "Orders";
     * }</pre>
     */
    private SqlStatement selectAll(ShapeTable table, SqlDialect dialect) {
        String cols = table.getColumns().stream()
                .map(c -> "    " + quote(c.getColumnName(), dialect))
                .collect(Collectors.joining(",\n"));

        String sql = "SELECT\n" + cols + "\nFROM " + quote(table.getTableName(), dialect) + ";";

        return SqlStatement.builder()
                .type(SqlStatementType.SELECT_ALL)
                .tableName(table.getTableName())
                .sql(sql)
                .dialect(dialect)
                .bindParameters(List.of())
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SELECT BY PK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a {@code SELECT} statement filtered by primary key(s).
     *
     * <pre>{@code
     * SELECT "ORDER_ID", "CUSTOMER_ID", "ORDER_DATE", "STATUS", "TOTAL_AMOUNT"
     * FROM "Orders"
     * WHERE "ORDER_ID" = :ORDER_ID;
     * }</pre>
     */
    private SqlStatement selectByPk(ShapeTable table, SqlDialect dialect) {
        List<String> pkCols = table.getPrimaryKeyColumns();

        String cols = table.getColumns().stream()
                .map(c -> "    " + quote(c.getColumnName(), dialect))
                .collect(Collectors.joining(",\n"));

        String where = pkCols.isEmpty()
                ? "/* no primary key defined */"
                : pkCols.stream()
                        .map(pk -> quote(pk, dialect) + " = :" + pk)
                        .collect(Collectors.joining("\n  AND "));

        String sql = "SELECT\n" + cols
                + "\nFROM " + quote(table.getTableName(), dialect)
                + "\nWHERE " + where + ";";

        return SqlStatement.builder()
                .type(SqlStatementType.SELECT_BY_PK)
                .tableName(table.getTableName())
                .sql(sql)
                .dialect(dialect)
                .bindParameters(new ArrayList<>(pkCols))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INSERT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate an {@code INSERT} statement with named parameters for all columns.
     *
     * <pre>{@code
     * INSERT INTO "Orders" (
     *     "ORDER_ID", "CUSTOMER_ID", "ORDER_DATE", "STATUS", "TOTAL_AMOUNT"
     * ) VALUES (
     *     :ORDER_ID, :CUSTOMER_ID, :ORDER_DATE, :STATUS, :TOTAL_AMOUNT
     * );
     * }</pre>
     */
    private SqlStatement insert(ShapeTable table, SqlDialect dialect) {
        List<String> colNames = table.getColumns().stream()
                .map(ShapeColumn::getColumnName)
                .toList();

        String colList = colNames.stream()
                .map(c -> "    " + quote(c, dialect))
                .collect(Collectors.joining(",\n"));

        String paramList = colNames.stream()
                .map(c -> "    :" + c)
                .collect(Collectors.joining(",\n"));

        String sql = "INSERT INTO " + quote(table.getTableName(), dialect) + " (\n"
                + colList + "\n) VALUES (\n"
                + paramList + "\n);";

        return SqlStatement.builder()
                .type(SqlStatementType.INSERT)
                .tableName(table.getTableName())
                .sql(sql)
                .dialect(dialect)
                .bindParameters(new ArrayList<>(colNames))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UPDATE BY PK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate an {@code UPDATE} statement for non-PK columns filtered by PK.
     *
     * <pre>{@code
     * UPDATE "Orders" SET
     *     "ORDER_DATE"   = :ORDER_DATE,
     *     "STATUS"       = :STATUS,
     *     "TOTAL_AMOUNT" = :TOTAL_AMOUNT
     * WHERE "ORDER_ID" = :ORDER_ID;
     * }</pre>
     */
    private SqlStatement updateByPk(ShapeTable table, SqlDialect dialect) {
        List<String> pkCols    = table.getPrimaryKeyColumns();
        Set<String>  pkSet     = new java.util.HashSet<>(pkCols);
        List<String> bindParams = new ArrayList<>();

        // SET clause: non-PK columns
        List<ShapeColumn> updatableCols = table.getColumns().stream()
                .filter(c -> !pkSet.contains(c.getColumnName()))
                .toList();

        String setClauses = updatableCols.stream()
                .peek(c -> bindParams.add(c.getColumnName()))
                .map(c -> "    " + quote(c.getColumnName(), dialect) + " = :" + c.getColumnName())
                .collect(Collectors.joining(",\n"));

        // WHERE clause: PK columns
        String where = pkCols.isEmpty()
                ? "/* no primary key defined */"
                : pkCols.stream()
                        .peek(bindParams::add)
                        .map(pk -> quote(pk, dialect) + " = :" + pk)
                        .collect(Collectors.joining("\n  AND "));

        String sql = updatableCols.isEmpty()
                ? "-- No updatable columns for table " + table.getTableName()
                : "UPDATE " + quote(table.getTableName(), dialect) + " SET\n"
                        + setClauses + "\nWHERE " + where + ";";

        return SqlStatement.builder()
                .type(SqlStatementType.UPDATE_BY_PK)
                .tableName(table.getTableName())
                .sql(sql)
                .dialect(dialect)
                .bindParameters(bindParams)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE BY PK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate a {@code DELETE} statement filtered by primary key(s).
     *
     * <pre>{@code
     * DELETE FROM "Orders"
     * WHERE "ORDER_ID" = :ORDER_ID;
     * }</pre>
     */
    private SqlStatement deleteByPk(ShapeTable table, SqlDialect dialect) {
        List<String> pkCols = table.getPrimaryKeyColumns();

        String where = pkCols.isEmpty()
                ? "/* no primary key defined — DELETE ALL */"
                : pkCols.stream()
                        .map(pk -> quote(pk, dialect) + " = :" + pk)
                        .collect(Collectors.joining("\n  AND "));

        String sql = "DELETE FROM " + quote(table.getTableName(), dialect)
                + "\nWHERE " + where + ";";

        return SqlStatement.builder()
                .type(SqlStatementType.DELETE_BY_PK)
                .tableName(table.getTableName())
                .sql(sql)
                .dialect(dialect)
                .bindParameters(new ArrayList<>(pkCols))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Column definition builder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a single column definition line for CREATE TABLE.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "ORDER_ID" BIGINT NOT NULL}</li>
     *   <li>{@code "STATUS" VARCHAR(50) NOT NULL}</li>
     *   <li>{@code "TOTAL_AMOUNT" NUMERIC}</li>
     *   <li>{@code "CUSTOMER_ID" BIGINT NOT NULL}  (FK — type inferred as BIGINT)</li>
     * </ul>
     */
    private String buildColumnDefinition(ShapeColumn col, SqlDialect dialect) {
        StringBuilder sb = new StringBuilder();
        sb.append(quote(col.getColumnName(), dialect)).append(" ");

        if (col.isForeignKey()) {
            // FK column — default to BIGINT (common PK type); adapt if needed
            sb.append(XsdToSqlTypeMapper.toSqlType(
                    "http://www.w3.org/2001/XMLSchema#long", dialect));
        } else {
            String sqlType = XsdToSqlTypeMapper.toSqlType(col.getXsdDatatype(), dialect);
            sb.append(sqlType);

            // Append length for string types with a known maxLength
            if (col.getMaxLength() != null && col.getMaxLength() > 0
                    && isStringBaseType(sqlType)) {
                sb.append("(").append(col.getMaxLength()).append(")");
            }
        }

        if (!col.isNullable()) sb.append(" NOT NULL");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quoting / utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Quote an SQL identifier.
     * Snowflake identifiers are case-insensitive by default, so quoting is omitted.
     * ANSI/PostgreSQL use double-quotes to preserve case.
     */
    private String quote(String identifier, SqlDialect dialect) {
        return switch (dialect) {
            case SNOWFLAKE -> identifier;          // Snowflake: unquoted = case-insensitive
            default        -> "\"" + identifier + "\"";  // ANSI / PostgreSQL
        };
    }

    private boolean isStringBaseType(String sqlType) {
        if (sqlType == null) return false;
        String u = sqlType.toUpperCase();
        return u.startsWith("VARCHAR") || u.startsWith("CHAR")
            || u.startsWith("NVARCHAR") || u.startsWith("STRING");
    }
}
