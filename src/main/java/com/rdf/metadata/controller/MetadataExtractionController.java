package com.rdf.metadata.controller;

import com.rdf.metadata.model.*;
import com.rdf.metadata.service.MetadataExtractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for schema metadata extraction and RDF transformation.
 *
 * <h3>Separated ontology graph endpoints</h3>
 * <table border="1">
 *   <tr><th>Endpoint</th><th>Graph returned</th></tr>
 *   <tr><td>/ontology/vocabulary</td><td>dr: vocabulary (class/property declarations)</td></tr>
 *   <tr><td>/ontology/repository</td><td>DataRepository instance graph</td></tr>
 *   <tr><td>/ontology/schema</td><td>OWL domain ontology (classes + properties)</td></tr>
 *   <tr><td>/ontology/linking</td><td>Linking ontology (owl:imports + dr:sourceTable bridges)</td></tr>
 *   <tr><td>/ontology</td><td>Merged union of all four graphs</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
@Tag(name = "Metadata Extraction", description = "Extract DB schema metadata and convert to RDF/SHACL")
public class MetadataExtractionController {

    private final MetadataExtractionService extractionService;

    // ─────────────────────────────────────────────────────────────────────────
    // Full extraction
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/extract")
    @Operation(
        summary = "Full extraction — returns all four ontology graphs + SHACL",
        description = """
            Connects, extracts, and returns four separated RDF graphs:
            - repoVocabularyRdf  — dr: vocabulary declarations
            - repoInstanceRdf    — dr:DataRepository instance data
            - schemaOntologyRdf  — OWL domain ontology
            - linkingOntologyRdf — cross-graph dr:sourceTable bridges
            - ontologyRdf        — merged union of all four (backward compatible)
            - shaclRdf           — SHACL shapes graph
            """
    )
    public ResponseEntity<ExtractionResult> extract(@Valid @RequestBody ExtractionRequest request) {
        log.info("POST /extract → db={}, schema={}, format={}",
                request.getDatabaseType(), request.getSchemaName(), request.getRdfFormat());
        return ResponseEntity.ok(extractionService.extract(request));
    }

    @PostMapping("/preview")
    @Operation(summary = "Preview schema metadata (no RDF)")
    public ResponseEntity<SchemaMetadata> preview(@Valid @RequestBody ExtractionRequest request) {
        return ResponseEntity.ok(extractionService.previewMetadata(request));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Individual graph endpoints — each returns one Turtle document
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/ontology/vocabulary", produces = "text/turtle")
    @Operation(
        summary = "dr: Vocabulary ontology",
        description = "Returns the dr: vocabulary graph (owl:Class and property declarations only). " +
                      "Reusable across any number of database connections."
    )
    public ResponseEntity<String> vocabularyOntology(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(false);
        request.setIncludeOwlAxioms(true);
        ExtractionResult result = extractionService.extract(request);
        return turtle(result.getRepoVocabularyRdf());
    }

    @PostMapping(value = "/ontology/repository", produces = "text/turtle")
    @Operation(
        summary = "Repository instance ontology",
        description = "Returns the dr:DataRepository instance graph. Contains one DataRepository " +
                      "individual per connection with all connection properties as RDF literals, " +
                      "plus DatabaseSchema, DatabaseTable and TableColumn individuals."
    )
    public ResponseEntity<String> repositoryOntology(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(false);
        request.setIncludeOwlAxioms(true);
        ExtractionResult result = extractionService.extract(request);
        return turtle(result.getRepoInstanceRdf());
    }

    @PostMapping(value = "/ontology/schema", produces = "text/turtle")
    @Operation(
        summary = "Schema (domain) ontology",
        description = "Returns the OWL domain ontology: owl:Class per table, owl:DatatypeProperty " +
                      "per column, owl:ObjectProperty per FK, cardinality restrictions, owl:hasKey. " +
                      "Does not contain any instance data — use /ontology/repository for that."
    )
    public ResponseEntity<String> schemaOntology(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(false);
        request.setIncludeOwlAxioms(true);
        ExtractionResult result = extractionService.extract(request);
        return turtle(result.getSchemaOntologyRdf());
    }

    @PostMapping(value = "/ontology/linking", produces = "text/turtle")
    @Operation(
        summary = "Linking ontology",
        description = "Returns the linking graph that bridges the schema ontology and the " +
                      "repository instance ontology via owl:imports and dr:sourceTable assertions."
    )
    public ResponseEntity<String> linkingOntology(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(false);
        request.setIncludeOwlAxioms(true);
        ExtractionResult result = extractionService.extract(request);
        return turtle(result.getLinkingOntologyRdf());
    }

    @PostMapping(value = "/ontology", produces = "text/turtle")
    @Operation(
        summary = "Merged ontology (all four graphs)",
        description = "Returns the union of all four ontology graphs as a single Turtle document. " +
                      "Use this for tools that expect a single file; prefer the individual " +
                      "graph endpoints for triplestore loading."
    )
    public ResponseEntity<String> mergedOntology(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(false);
        request.setIncludeOwlAxioms(true);
        ExtractionResult result = extractionService.extract(request);
        return turtle(result.getOntologyRdf());
    }

    @PostMapping(value = "/shacl", produces = "text/turtle")
    @Operation(summary = "SHACL shapes graph as Turtle")
    public ResponseEntity<String> shaclAsTurtle(@Valid @RequestBody ExtractionRequest request) {
        request.setRdfFormat(RdfFormat.TURTLE);
        request.setIncludeShaclShapes(true);
        request.setIncludeOwlAxioms(false);
        ExtractionResult result = extractionService.extract(request);
        return turtle(result.getShaclRdf());
    }

    @PostMapping("/ping")
    @Operation(summary = "Test database connectivity")
    public ResponseEntity<PingResponse> ping(@Valid @RequestBody ExtractionRequest request) {
        request.setIncludeOwlAxioms(false);
        request.setIncludeShaclShapes(false);
        SchemaMetadata meta = extractionService.previewMetadata(request);
        return ResponseEntity.ok(new PingResponse("OK", meta.getDatabaseName(),
                meta.getSchemaName(), meta.getTables().size()));
    }

    public record PingResponse(String status, String database, String schema, int tableCount) {}

    private ResponseEntity<String> turtle(String body) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/turtle"))
                .body(body);
    }
}
