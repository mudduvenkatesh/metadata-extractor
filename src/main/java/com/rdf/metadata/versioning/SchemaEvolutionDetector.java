package com.rdf.metadata.versioning;

import com.rdf.metadata.model.ColumnMetadata;
import com.rdf.metadata.model.ForeignKeyMetadata;
import com.rdf.metadata.model.SchemaMetadata;
import com.rdf.metadata.model.TableMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares two {@link SchemaMetadata} snapshots (previous and current) and
 * returns a list of {@link DetectedChange}s representing structural differences.
 *
 * <h3>Detected change types</h3>
 * <ul>
 *   <li>Tables added / dropped</li>
 *   <li>Columns added / dropped (within tables present in both versions)</li>
 *   <li>Column SQL type changed</li>
 *   <li>Column nullability changed</li>
 *   <li>Column maxLength changed</li>
 *   <li>Primary key columns changed</li>
 *   <li>Foreign keys added / dropped</li>
 * </ul>
 */
@Slf4j
@Component
public class SchemaEvolutionDetector {

    /**
     * Diff {@code previous} against {@code current} and return all detected changes.
     * Returns an empty list if the two snapshots are structurally identical.
     *
     * @param previous the older schema snapshot (may be null for the very first version)
     * @param current  the newly extracted snapshot
     * @return ordered list of {@link DetectedChange}s, never null
     */
    public List<DetectedChange> detect(SchemaMetadata previous, SchemaMetadata current) {
        if (previous == null) {
            log.debug("No previous version — no diff to compute");
            return List.of();
        }

        List<DetectedChange> changes = new ArrayList<>();

        Map<String, TableMetadata> prevTables = indexByName(previous.getTables());
        Map<String, TableMetadata> currTables = indexByName(current.getTables());

        // ── Table additions ───────────────────────────────────────────────────
        for (String tableName : currTables.keySet()) {
            if (!prevTables.containsKey(tableName)) {
                changes.add(DetectedChange.tableChange(
                        SchemaChangeType.TABLE_ADDED, tableName,
                        "Table '" + tableName + "' was added"));
            }
        }

        // ── Table drops ───────────────────────────────────────────────────────
        for (String tableName : prevTables.keySet()) {
            if (!currTables.containsKey(tableName)) {
                changes.add(DetectedChange.tableChange(
                        SchemaChangeType.TABLE_DROPPED, tableName,
                        "Table '" + tableName + "' was dropped"));
            }
        }

        // ── Per-table diffs (tables present in both versions) ─────────────────
        for (String tableName : currTables.keySet()) {
            if (!prevTables.containsKey(tableName)) continue;
            diffTable(prevTables.get(tableName), currTables.get(tableName), changes);
        }

        log.info("Schema diff detected {} changes between previous and current version", changes.size());
        return Collections.unmodifiableList(changes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-table diff
    // ─────────────────────────────────────────────────────────────────────────

    private void diffTable(TableMetadata prev, TableMetadata curr,
                            List<DetectedChange> changes) {
        String tableName = curr.getTableName();

        Map<String, ColumnMetadata> prevCols = indexColsByName(prev.getColumns());
        Map<String, ColumnMetadata> currCols = indexColsByName(curr.getColumns());

        // Column additions
        for (String colName : currCols.keySet()) {
            if (!prevCols.containsKey(colName)) {
                ColumnMetadata col = currCols.get(colName);
                changes.add(DetectedChange.columnChange(
                        SchemaChangeType.COLUMN_ADDED, tableName, colName,
                        null, col.getDataType(),
                        "Column '" + tableName + "." + colName
                        + "' was added (" + col.getDataType()
                        + (col.isNullable() ? ", nullable" : ", NOT NULL") + ")"));
            }
        }

        // Column drops
        for (String colName : prevCols.keySet()) {
            if (!currCols.containsKey(colName)) {
                changes.add(DetectedChange.columnChange(
                        SchemaChangeType.COLUMN_DROPPED, tableName, colName,
                        prevCols.get(colName).getDataType(), null,
                        "Column '" + tableName + "." + colName + "' was dropped"));
            }
        }

        // Per-column attribute diffs
        for (String colName : currCols.keySet()) {
            if (!prevCols.containsKey(colName)) continue;
            diffColumn(tableName, prevCols.get(colName), currCols.get(colName), changes);
        }

        // Primary key changes
        List<String> prevPk = sorted(prev.getPrimaryKeyColumns());
        List<String> currPk = sorted(curr.getPrimaryKeyColumns());
        if (!prevPk.equals(currPk)) {
            changes.add(DetectedChange.tableChange(
                    SchemaChangeType.PRIMARY_KEY_CHANGED, tableName,
                    "Primary key changed from " + prevPk + " to " + currPk));
        }

        // Foreign key additions
        Set<String> prevFkNames = fkConstraintNames(prev.getForeignKeys());
        Set<String> currFkNames = fkConstraintNames(curr.getForeignKeys());

        curr.getForeignKeys().stream()
                .filter(fk -> !prevFkNames.contains(fk.getConstraintName()))
                .forEach(fk -> changes.add(DetectedChange.columnChange(
                        SchemaChangeType.FOREIGN_KEY_ADDED, tableName,
                        fk.getFkColumnName(), null,
                        fk.getPkTableName() + "." + fk.getPkColumnName(),
                        "FK '" + fk.getConstraintName() + "' added: "
                        + tableName + "." + fk.getFkColumnName()
                        + " → " + fk.getPkTableName() + "." + fk.getPkColumnName())));

        prev.getForeignKeys().stream()
                .filter(fk -> !currFkNames.contains(fk.getConstraintName()))
                .forEach(fk -> changes.add(DetectedChange.columnChange(
                        SchemaChangeType.FOREIGN_KEY_DROPPED, tableName,
                        fk.getFkColumnName(),
                        fk.getPkTableName() + "." + fk.getPkColumnName(), null,
                        "FK '" + fk.getConstraintName() + "' dropped")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-column attribute diff
    // ─────────────────────────────────────────────────────────────────────────

    private void diffColumn(String tableName, ColumnMetadata prev, ColumnMetadata curr,
                             List<DetectedChange> changes) {
        String colName = curr.getColumnName();

        // Data type change
        if (!Objects.equals(normalise(prev.getDataType()), normalise(curr.getDataType()))) {
            changes.add(DetectedChange.columnChange(
                    SchemaChangeType.COLUMN_TYPE_CHANGED, tableName, colName,
                    prev.getDataType(), curr.getDataType(),
                    "Column '" + tableName + "." + colName + "' type changed from '"
                    + prev.getDataType() + "' to '" + curr.getDataType() + "'"));
        }

        // Nullability change
        if (prev.isNullable() != curr.isNullable()) {
            changes.add(DetectedChange.columnChange(
                    SchemaChangeType.COLUMN_NULLABILITY_CHANGED, tableName, colName,
                    String.valueOf(prev.isNullable()), String.valueOf(curr.isNullable()),
                    "Column '" + tableName + "." + colName + "' nullability changed from "
                    + prev.isNullable() + " to " + curr.isNullable()));
        }

        // Max length change
        if (!Objects.equals(prev.getCharacterMaxLength(), curr.getCharacterMaxLength())
                && (prev.getCharacterMaxLength() != null || curr.getCharacterMaxLength() != null)) {
            changes.add(DetectedChange.columnChange(
                    SchemaChangeType.COLUMN_LENGTH_CHANGED, tableName, colName,
                    String.valueOf(prev.getCharacterMaxLength()),
                    String.valueOf(curr.getCharacterMaxLength()),
                    "Column '" + tableName + "." + colName + "' maxLength changed from "
                    + prev.getCharacterMaxLength() + " to " + curr.getCharacterMaxLength()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Map<String, TableMetadata> indexByName(List<TableMetadata> tables) {
        return tables.stream().collect(Collectors.toMap(
                t -> t.getTableName().toUpperCase(),
                Function.identity(),
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private Map<String, ColumnMetadata> indexColsByName(List<ColumnMetadata> cols) {
        return cols.stream().collect(Collectors.toMap(
                c -> c.getColumnName().toUpperCase(),
                Function.identity(),
                (a, b) -> a,
                LinkedHashMap::new));
    }

    private Set<String> fkConstraintNames(List<ForeignKeyMetadata> fks) {
        return fks.stream()
                .map(ForeignKeyMetadata::getConstraintName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private List<String> sorted(List<String> list) {
        return list == null ? List.of()
                : list.stream().map(String::toUpperCase).sorted().toList();
    }

    private String normalise(String type) {
        return type == null ? "" : type.toUpperCase().replaceAll("\\(.*\\)", "").trim();
    }
}
