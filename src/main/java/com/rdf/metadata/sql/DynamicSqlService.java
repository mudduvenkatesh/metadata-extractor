package com.rdf.metadata.sql;

import com.rdf.metadata.connector.DatabaseConnector;
import com.rdf.metadata.connector.DatabaseConnectorFactory;
import com.rdf.metadata.extractor.AbstractJdbcMetadataExtractor;
import com.rdf.metadata.extractor.MetadataExtractorFactory;
import com.rdf.metadata.model.ExtractionRequest;
import com.rdf.metadata.model.SchemaMetadata;
import com.rdf.metadata.service.ExtractionServiceException;
import com.rdf.metadata.shacl.ShaclShapesBuilder;
import com.rdf.metadata.sql.filter.FilterValidator;
import com.rdf.metadata.sql.filter.SelectFilterRequest;
import com.rdf.metadata.sql.filter.SelectFilterSqlBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates the full pipeline from database connection to dynamic SQL generation:
 *
 * <ol>
 *   <li>Connect to the source database and extract {@link SchemaMetadata}</li>
 *   <li>Build a SHACL shapes {@link Model} from the metadata</li>
 *   <li>Parse the SHACL model into {@link ShapeTable} objects via {@link ShaclModelReader}</li>
 *   <li>Generate SQL statements via {@link DynamicSqlGenerator} or
 *       {@link SelectFilterSqlBuilder}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicSqlService {

    private final DatabaseConnectorFactory connectorFactory;
    private final MetadataExtractorFactory  extractorFactory;
    private final ShaclShapesBuilder        shaclShapesBuilder;
    private final ShaclModelReader          shaclModelReader;
    private final DynamicSqlGenerator       sqlGenerator;
    private final FilterValidator           filterValidator;
    private final SelectFilterSqlBuilder    filterSqlBuilder;

    // ─────────────────────────────────────────────────────────────────────────
    // Full pipeline: DB → SHACL model → SQL (existing)
    // ─────────────────────────────────────────────────────────────────────────

    public SqlGenerationResult generateFromDatabase(SqlGenerationRequest request) {
        ExtractionRequest extractionReq = request.getExtractionRequest();
        log.info("Starting SQL generation: db={}, schema={}, dialect={}, types={}",
                extractionReq.getDatabaseType(), extractionReq.getSchemaName(),
                request.getDialect(), request.getStatementTypes());

        SchemaMetadata schemaMetadata = extractMetadata(extractionReq);
        Model shaclModel = shaclShapesBuilder.build(schemaMetadata);
        log.info("SHACL model built: {} triples", shaclModel.size());

        return generateFromModel(shaclModel, schemaMetadata.getSchemaName(), request);
    }

    public SqlGenerationResult generateFromModel(Model shaclModel,
                                                  String schemaName,
                                                  SqlGenerationRequest request) {
        List<ShapeTable> allTables = shaclModelReader.readTables(shaclModel);
        List<String> includeFilter = request.getIncludeTables();
        List<ShapeTable> tables = includeFilter.isEmpty()
                ? allTables
                : allTables.stream()
                        .filter(t -> includeFilter.stream()
                                .anyMatch(f -> f.equalsIgnoreCase(t.getTableName())))
                        .toList();

        log.info("Generating SQL for {} tables", tables.size());

        Map<String, List<SqlStatement>> byTable = new LinkedHashMap<>();
        for (ShapeTable table : tables) {
            List<SqlStatement> stmts = sqlGenerator.generate(
                    table, request.getStatementTypes(), request.getDialect());
            byTable.put(table.getTableName(), stmts);
        }

        int totalStmts = byTable.values().stream().mapToInt(List::size).sum();

        return SqlGenerationResult.builder()
                .schemaName(schemaName)
                .dialect(request.getDialect())
                .tablesProcessed(tables.size())
                .statementsGenerated(totalStmts)
                .generatedAt(Instant.now())
                .statementsByTable(byTable)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Filter-based SELECT — DB → SHACL model → validated filter SELECT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Connect to the database, extract schema metadata, build the SHACL model,
     * then generate a filter-based SELECT statement for the requested table.
     *
     * @param extractionRequest connection + schema details
     * @param filterRequest     table name, projection, filter tree, ORDER BY, LIMIT/OFFSET
     * @return validated and rendered {@link SqlStatement}
     */
    public SqlStatement generateFilterSelect(ExtractionRequest extractionRequest,
                                              SelectFilterRequest filterRequest) {
        log.info("Generating filter SELECT: table={}, dialect={}",
                filterRequest.getTableName(), filterRequest.getDialect());

        SchemaMetadata schemaMetadata = extractMetadata(extractionRequest);
        Model shaclModel = shaclShapesBuilder.build(schemaMetadata);

        return generateFilterSelectFromModel(shaclModel, filterRequest);
    }

    /**
     * Generate a filter-based SELECT from an existing in-memory SHACL model.
     * Skips database connection — use when the model is already cached.
     *
     * @param shaclModel    the RDF4J SHACL model (from a prior extraction)
     * @param filterRequest the filter specification
     * @return validated and rendered {@link SqlStatement}
     */
    public SqlStatement generateFilterSelectFromModel(Model shaclModel,
                                                       SelectFilterRequest filterRequest) {
        // Find the matching ShapeTable by name (case-insensitive)
        List<ShapeTable> tables = shaclModelReader.readTables(shaclModel);
        ShapeTable shape = tables.stream()
                .filter(t -> t.getTableName().equalsIgnoreCase(filterRequest.getTableName()))
                .findFirst()
                .orElseThrow(() -> new ExtractionServiceException(
                        "No SHACL shape found for table '" + filterRequest.getTableName()
                        + "'. Available tables: "
                        + tables.stream().map(ShapeTable::getTableName).toList()));

        // Validate filter against the shape — checks column existence, type compatibility
        filterValidator.validate(filterRequest, shape);

        // Build the SQL
        SqlStatement stmt = filterSqlBuilder.build(filterRequest, shape);

        log.info("Filter SELECT generated for '{}': {} bind params",
                shape.getTableName(), stmt.getBindParameters().size());
        return stmt;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private SchemaMetadata extractMetadata(ExtractionRequest request) {
        DatabaseConnector             connector = connectorFactory.getConnector(request.getDatabaseType());
        AbstractJdbcMetadataExtractor extractor = extractorFactory.getExtractor(request.getDatabaseType());

        try (Connection connection = connector.connect(request)) {
            return extractor.extract(request, connection);
        } catch (Exception e) {
            throw new ExtractionServiceException(
                    "Failed to extract metadata for SQL generation: " + e.getMessage(), e);
        }
    }
}
