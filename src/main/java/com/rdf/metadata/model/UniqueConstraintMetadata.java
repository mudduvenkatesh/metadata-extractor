package com.rdf.metadata.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UniqueConstraintMetadata {
    private String constraintName;
    private List<String> columns;
}
