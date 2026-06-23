package com.rdf.metadata.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI rdfMetadataOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RDF Metadata Extractor API")
                        .description("""
                                Extracts relational database schema metadata (tables, columns, PK, FK,
                                constraints) and transforms it into:
                                - OWL ontology (Classes + Data/Object Properties)
                                - SHACL node/property shapes
                                
                                Supported databases: **Snowflake**, **PostgreSQL**
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("Schema2RDF").email("schema2rdf@example.org"))
                        .license(new License().name("Apache 2.0")));
    }
}
