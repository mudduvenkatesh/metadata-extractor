package com.rdf.metadata.rdf;

import com.rdf.metadata.model.RdfFormat;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.WriterConfig;
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings;
import org.eclipse.rdf4j.rio.helpers.JSONLDMode;
import org.eclipse.rdf4j.rio.helpers.JSONLDSettings;
import org.springframework.stereotype.Component;

import java.io.StringWriter;

/**
 * Serializes RDF4J {@link Model} instances to various RDF string formats via Rio.
 *
 * <p>Produces pretty-printed Turtle by default; JSON-LD is emitted in expanded form.
 * Writer settings can be customised per format in {@link #buildWriterConfig}.
 */
@Slf4j
@Component
public class RdfSerializer {

    /**
     * Serialize a RDF4J {@link Model} to a string in the requested format.
     *
     * @param model  the model to serialize (must not be null)
     * @param format the target {@link RdfFormat}
     * @return the serialized RDF as a UTF-8 String
     * @throws RdfSerializationException on any Rio write error
     */
    public String serialize(Model model, RdfFormat format) {
        StringWriter writer = new StringWriter();
        try {
            WriterConfig config = buildWriterConfig(format);
            // Rio.write is thread-safe; no external synchronization needed
            Rio.write(model, writer, format.getRdf4jFormat(), config);
            return writer.toString();
        } catch (Exception e) {
            log.error("Failed to serialize model to {}: {}", format, e.getMessage(), e);
            throw new RdfSerializationException(
                    "Serialization to " + format + " failed: " + e.getMessage(), e);
        }
    }

    /** Convenience: serialize as Turtle. */
    public String toTurtle(Model model) {
        return serialize(model, RdfFormat.TURTLE);
    }

    /** Convenience: serialize as JSON-LD. */
    public String toJsonLd(Model model) {
        return serialize(model, RdfFormat.JSON_LD);
    }

    /** Convenience: serialize as RDF/XML. */
    public String toRdfXml(Model model) {
        return serialize(model, RdfFormat.RDF_XML);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private WriterConfig buildWriterConfig(RdfFormat format) {
        WriterConfig config = new WriterConfig();
        // Pretty-print Turtle with inline blank nodes
        config.set(BasicWriterSettings.PRETTY_PRINT, true);
        config.set(BasicWriterSettings.INLINE_BLANK_NODES, true);

        if (format == RdfFormat.JSON_LD) {
            config.set(JSONLDSettings.JSONLD_MODE, JSONLDMode.COMPACT);
            config.set(JSONLDSettings.USE_NATIVE_TYPES, true);
        }
        return config;
    }
}
