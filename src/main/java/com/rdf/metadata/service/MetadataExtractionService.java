package com.rdf.metadata.service;

import com.rdf.metadata.connector.DatabaseConnector;
import com.rdf.metadata.connector.DatabaseConnectorFactory;
import com.rdf.metadata.extractor.AbstractJdbcMetadataExtractor;
import com.rdf.metadata.extractor.MetadataExtractorFactory;
import com.rdf.metadata.model.*;
import com.rdf.metadata.rdf.*;
import com.rdf.metadata.shacl.ShaclShapesBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full extraction → transformation → serialization pipeline.
 *
 * <p>When {@code includeOwlAxioms} is requested, builds four separated ontology
 * graphs via {@link OntologyOrchestrator} and returns them individually plus
 * a merged union in {@link ExtractionResult#getOntologyRdf()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataExtractionService {

    private final DatabaseConnectorFactory connectorFactory;
    private final MetadataExtractorFactory  extractorFactory;
    private final OntologyOrchestrator      ontologyOrchestrator;
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

        // ── Separated ontology graphs ─────────────────────────────────────────
        String vocabRdf   = null;
        String repoRdf    = null;
        String schemaRdf  = null;
        String linkingRdf = null;
        String mergedRdf  = null;
        int    rdfTriples = 0;

        if (request.isIncludeOwlAxioms()) {
            SeparatedOntologyResult ontologies = ontologyOrchestrator.buildAll(schemaMetadata);

            vocabRdf   = rdfSerializer.serialize(ontologies.getRepoVocabularyModel(),  request.getRdfFormat());
            repoRdf    = rdfSerializer.serialize(ontologies.getRepoInstanceModel(),    request.getRdfFormat());
            schemaRdf  = rdfSerializer.serialize(ontologies.getSchemaOntologyModel(), request.getRdfFormat());
            linkingRdf = rdfSerializer.serialize(ontologies.getLinkingOntologyModel(),request.getRdfFormat());

            // Merged union for backward-compatible consumers
            Model merged = new LinkedHashModel();
            merged.addAll(ontologies.getRepoVocabularyModel());
            merged.addAll(ontologies.getRepoInstanceModel());
            merged.addAll(ontologies.getSchemaOntologyModel());
            merged.addAll(ontologies.getLinkingOntologyModel());
            mergedRdf  = rdfSerializer.serialize(merged, request.getRdfFormat());
            rdfTriples = ontologies.totalTriples();

            log.info("Ontology graphs: vocab={}, repo={}, schema={}, linking={}, total={}",
                    ontologies.getRepoVocabularyModel().size(),
                    ontologies.getRepoInstanceModel().size(),
                    ontologies.getSchemaOntologyModel().size(),
                    ontologies.getLinkingOntologyModel().size(),
                    rdfTriples);
        }

        // ── SHACL shapes ──────────────────────────────────────────────────────
        String shaclRdf     = null;
        int shaclShapeCount = 0;

        if (request.isIncludeShaclShapes()) {
            Model shaclModel = shaclShapesBuilder.build(schemaMetadata);
            shaclRdf         = rdfSerializer.serialize(shaclModel, request.getRdfFormat());
            shaclShapeCount  = tableCount;
            log.info("SHACL shapes: {} NodeShapes", shaclShapeCount);
        }

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
                .repoVocabularyRdf(vocabRdf)
                .repoInstanceRdf(repoRdf)
                .schemaOntologyRdf(schemaRdf)
                .linkingOntologyRdf(linkingRdf)
                .ontologyRdf(mergedRdf)
                .shaclRdf(shaclRdf)
                .format(request.getRdfFormat())
                .warnings(warnings)
                .build();
    }

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
