package com.rdf.metadata.rdf;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;

/**
 * A named RDF graph — pairs an ontology {@link IRI} with its {@link Model}.
 *
 * @param ontologyIri the {@code owl:Ontology} IRI that identifies this graph
 * @param model       the RDF4J model containing the graph's triples
 */
public record OntologyGraph(IRI ontologyIri, Model model) {}
