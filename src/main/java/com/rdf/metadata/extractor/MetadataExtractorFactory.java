package com.rdf.metadata.extractor;

import com.rdf.metadata.model.DatabaseType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the correct {@link AbstractJdbcMetadataExtractor} for a given {@link DatabaseType}.
 */
@Component
public class MetadataExtractorFactory {

    private final Map<DatabaseType, AbstractJdbcMetadataExtractor> extractors;

    public MetadataExtractorFactory(List<AbstractJdbcMetadataExtractor> extractorList) {
        this.extractors = extractorList.stream()
                .collect(Collectors.toMap(
                        AbstractJdbcMetadataExtractor::supportedType,
                        Function.identity()
                ));
    }

    public AbstractJdbcMetadataExtractor getExtractor(DatabaseType type) {
        AbstractJdbcMetadataExtractor extractor = extractors.get(type);
        if (extractor == null) {
            throw new MetadataExtractionException(
                    "No extractor registered for database type: " + type);
        }
        return extractor;
    }
}
