package com.rdf.metadata.service;

import com.rdf.metadata.connector.DatabaseConnector;
import com.rdf.metadata.connector.DatabaseConnectorFactory;
import com.rdf.metadata.extractor.AbstractJdbcMetadataExtractor;
import com.rdf.metadata.extractor.MetadataExtractorFactory;
import com.rdf.metadata.model.*;
import com.rdf.metadata.rdf.OntologyBuilder;
import com.rdf.metadata.rdf.RdfSerializer;
import com.rdf.metadata.shacl.ShaclShapesBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full pipeline:
 * <ol>
 *   <li>Resolve the {@link DatabaseConnector} for the requested database type</li>
 *   <li>Open a JDBC connection</li>
 *   <li>Extract relational schema metadata</li>
 *   <li>Build an OWL ontology (RDF4J {@link Model})</li>
 *   <li>Build a SHACL shapes graph (RDF4J {@link Model})</li>
 *   <li>Serialize both models via Rio</li>
 *   <li>Return a populated {@link ExtractionResult}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataExtractionService {

    private final DatabaseConnectorFactory connectorFactory;
    private final MetadataExtractorFactory  extractorFactory;
    private final OntologyBuilder           ontologyBuilder;
    private final ShaclShapesBuilder        shaclShapesBuilder;
    private final RdfSerializer             rdfSerializer;

    public ExtractionResult extract(ExtractionRequest request) {
        List<String> warnings = new ArrayList<>();
        log.info("Starting extraction: db={}, schema={}",
                request.getDatabaseType(), request.getSchemaName());

        DatabaseConnector             connector = connectorFactory.getConnector(request.getDatabaseType());
        AbstractJdbcMetadataExtractor extractor = extractorFactory.getExtractor(request.getDatabaseType());

        SchemaMetadata schemaMetadata;
        try (Connection connection = connector.connect(request)) {
            log.info("Connection established, beginning metadata extraction...");
            schemaMetadata = extractor.extract(request, connection);
        } catch (Exception e) {
            throw new ExtractionServiceException("Extraction failed: " + e.getMessage(), e);
        }

        int tableCount  = schemaMetadata.getTables().size();
        int columnCount = schemaMetadata.getTables().stream().mapToInt(t -> t.getColumns().size()).sum();
        int fkCount     = schemaMetadata.getTables().stream().mapToInt(t -> t.getForeignKeys().size()).sum();
        log.info("Extracted {} tables, {} columns, {} FKs", tableCount, columnCount, fkCount);

        // ── OWL ontology ─────────────────────────────────────────────────────
        String ontologyRdf = null;
        int rdfTriples     = 0;

        if (request.isIncludeOwlAxioms()) {
            Model ontModel = ontologyBuilder.build(schemaMetadata);
            ontologyRdf    = rdfSerializer.serialize(ontModel, request.getRdfFormat());
            rdfTriples     = ontModel.size();
            log.info("OWL ontology: {} triples", rdfTriples);
        }

        // ── SHACL shapes ──────────────────────────────────────────────────────
        String shaclRdf    = null;
        int shaclShapeCount = 0;

        if (request.isIncludeShaclShapes()) {
            Model shaclModel = shaclShapesBuilder.build(schemaMetadata);
            shaclRdf         = rdfSerializer.serialize(shaclModel, request.getRdfFormat());
            shaclShapeCount  = tableCount;
            log.info("SHACL shapes: {} NodeShapes", shaclShapeCount);
        }

        // Warn on empty tables (likely restricted views)
        schemaMetadata.getTables().stream()
                .filter(t -> t.getColumns().isEmpty())
                .forEach(t -> warnings.add(
                        "Table '" + t.getTableName() + "' had no columns — may be a restricted view"));

        return ExtractionResult.builder()
                .databaseType(request.getDatabaseType().name())
                .databaseName(schemaMetadata.getDatabaseName())
                .schemaName(schemaMetadata.getSchemaName())
                .tablesProcessed(tableCount)
                .columnsProcessed(columnCount)
                .foreignKeysProcessed(fkCount)
                .rdfTriplesGenerated(rdfTriples)
                .shaclShapesGenerated(shaclShapeCount)
                .extractedAt(Instant.now())
                .ontologyRdf(ontologyRdf)
                .shaclRdf(shaclRdf)
                .format(request.getRdfFormat())
                .warnings(warnings)
                .build();
    }

    /**
     * Extract schema metadata only (no RDF transformation).
     */
    public SchemaMetadata previewMetadata(ExtractionRequest request) {
        DatabaseConnector             connector = connectorFactory.getConnector(request.getDatabaseType());
        AbstractJdbcMetadataExtractor extractor = extractorFactory.getExtractor(request.getDatabaseType());

        try (Connection connection = connector.connect(request)) {
            return extractor.extract(request, connection);
        } catch (Exception e) {
            throw new ExtractionServiceException("Metadata preview failed: " + e.getMessage(), e);
        }
    }
}
