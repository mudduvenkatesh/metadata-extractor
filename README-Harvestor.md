# SDF Harvestor Service

A **Spring Boot 4.1.0** application that extracts relational database schema metadata from all 
registered data repos and data sources
## Data Connector and Data Platform Ontology

1. External Secret Store (most common)
   Store the sensitive value in a vault (HashiCorp Vault, AWS Secrets Manager, etc.) and put only a reference URI in the RDF:
   turtleex:subject schema:additionalProperty [
   a schema:PropertyValue ;
   schema:name  "apiKey" ;
   schema:value <vault://secret/my-service/apiKey>   # reference, not the value
   ] .

```
DataRepository
    |
    ▼
    RelationalDataRepo - and others
        |
        ▼    
    SnowflakeDataRepo
    
    PostgreDataRepo
    
    
    
```
(tables, columns, primary keys, foreign keys, constraints) and transforms it into:

- **OWL Ontology** — `owl:Class`, `owl:DatatypeProperty`, `owl:ObjectProperty`, cardinality restrictions, `owl:hasKey`
- **SHACL Shapes Graph** — `sh:NodeShape`, `sh:PropertyShape`, datatype/class constraints, cardinality, SPARQL constraint stubs for CHECK constraints

Supported databases: **Snowflake** and **PostgreSQL** (extensible to others).

---

## Architecture

```
ExtractionRequest (REST)
        │
        ▼
MetadataExtractionService
    │           │
    ▼           ▼
DatabaseConnectorFactory    MetadataExtractorFactory
    │                               │
    ├── SnowflakeDatabaseConnector  ├── SnowflakeMetadataExtractor
    └── PostgreSQLDatabaseConnector └── PostgreSQLMetadataExtractor
                                        (extends AbstractJdbcMetadataExtractor)
        │
        ▼
    SchemaMetadata
    ┌──────────────────────────────────┐
    │ Tables → Columns                 │
    │        → PrimaryKeyColumns       │
    │        → ForeignKeys             │
    │        → UniqueConstraints       │
    │        → CheckConstraints        │
    └──────────────────────────────────┘
        │                   │
        ▼                   ▼
  OntologyBuilder     ShaclShapesBuilder
  (Apache Jena OWL)   (Apache Jena SHACL)
        │                   │
        └─────────┬─────────┘
                  ▼
            RdfSerializer
         (Turtle / JSON-LD / RDF/XML / N-Triples)
```

## Mapping Rules

### SQL → OWL

| Relational Concept   | OWL Construct                                         |
|----------------------|-------------------------------------------------------|
| TABLE                | `owl:Class`                                           |
| VIEW                 | `owl:Class` (annotated with `tableType=VIEW`)         |
| Non-FK COLUMN        | `owl:DatatypeProperty` + `rdfs:range` (XSD type)      |
| FK COLUMN            | `owl:ObjectProperty` + `rdfs:range` (referenced class)|
| NOT NULL column      | `owl:minCardinality 1` restriction on domain class    |
| PRIMARY KEY          | `owl:hasKey`                                          |
| UNIQUE constraint    | `owl:hasKey`                                          |
| CHECK constraint     | `rdfs:comment` annotation                             |
| Remarks/comments     | `rdfs:comment`                                        |

### SQL → SHACL

| Relational Concept   | SHACL Construct                                       |
|----------------------|-------------------------------------------------------|
| TABLE                | `sh:NodeShape` with `sh:targetClass`                  |
| Non-FK COLUMN        | `sh:PropertyShape` with `sh:datatype`, `sh:maxCount 1`|
| FK COLUMN            | `sh:PropertyShape` with `sh:class`, `sh:nodeKind IRI` |
| NOT NULL column      | `sh:minCount 1`                                       |
| VARCHAR(n) column    | `sh:maxLength n`                                      |
| CHECK constraint     | `sh:SPARQLConstraint` (SQL expression preserved)      |
| UNIQUE constraint    | `sh:SPARQLConstraint` (noted as comment)              |

---

## Running the Application

### Prerequisites

- Java 21+
- Maven 3.9+

### Build

```bash
mvn clean package -DskipTests
java -jar target/metadata-extractor-1.0.0-SNAPSHOT.jar
```

### Run Tests

```bash
mvn test
```

Tests use an **H2 in-memory database** — no external DB required.

---

## REST API

Swagger UI: **http://localhost:8080/swagger-ui.html**

### `POST /api/v1/metadata/extract`

Full pipeline: connect → extract → OWL + SHACL → serialized RDF.

**Snowflake Example:**
```json
{
  "databaseType": "SNOWFLAKE",
  "account": "xy12345.us-east-1",
  "username": "my_user",
  "password": "my_password",
  "databaseName": "MY_DATABASE",
  "schemaName": "PUBLIC",
  "warehouse": "COMPUTE_WH",
  "role": "SYSADMIN",
  "rdfFormat": "TURTLE",
  "includeShaclShapes": true,
  "includeOwlAxioms": true,
  "includeTables": []
}
```

**PostgreSQL Example:**
```json
{
  "databaseType": "POSTGRESQL",
  "host": "localhost",
  "port": 5432,
  "databaseName": "mydb",
  "username": "postgres",
  "password": "secret",
  "schemaName": "public",
  "rdfFormat": "TURTLE",
  "includeShaclShapes": true,
  "includeOwlAxioms": true
}
```

### `POST /api/v1/metadata/preview`

Returns raw JSON metadata (no RDF transformation).

### `POST /api/v1/metadata/ontology`

Returns OWL ontology as `text/turtle`.

### `POST /api/v1/metadata/shacl`

Returns SHACL shapes graph as `text/turtle`.

### `POST /api/v1/metadata/ping`

Tests connectivity, returns table count.

---

## Supported RDF Formats

| Value       | MIME Type                  |
|-------------|----------------------------|
| `TURTLE`    | `text/turtle`              |
| `JSON_LD`   | `application/ld+json`      |
| `RDF_XML`   | `application/rdf+xml`      |
| `N_TRIPLES` | `application/n-triples`    |
| `TRIG`      | `application/trig`         |

---

## Configuration (`application.yml`)

```yaml
rdf:
  base-namespace: "http://example.org/schema#"   # Base IRI for all generated resources
  output-directory: "./output"                    # Where to write files (if used)
```

---

## Adding a New Database

1. Implement `DatabaseConnector` → annotate `@Component`
2. Extend `AbstractJdbcMetadataExtractor` → override `supportedType()` and optionally `extractCheckConstraints()`
3. Add the new value to `DatabaseType` enum

No other changes needed — both factories auto-discover via Spring's `List<T>` injection.

---

## Sample Output (Turtle)

**OWL Ontology:**
```turtle
@prefix schema: <http://example.org/schema#> .
@prefix owl:    <http://www.w3.org/2002/07/owl#> .
@prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .

schema:Orders a owl:Class ;
    rdfs:label "ORDERS" ;
    owl:hasKey ( schema:Orders_orderId ) .

schema:Orders_orderDate a owl:DatatypeProperty ;
    rdfs:domain schema:Orders ;
    rdfs:range  xsd:dateTime ;
    rdfs:label  "ORDER_DATE" .

schema:Orders_hasCustomers a owl:ObjectProperty ;
    rdfs:domain schema:Orders ;
    rdfs:range  schema:Customers ;
    rdfs:label  "referencesCustomers" .
```

**SHACL Shapes:**
```turtle
@prefix sh:     <http://www.w3.org/ns/shacl#> .
@prefix schema: <http://example.org/schema#> .

schema:OrdersShape a sh:NodeShape ;
    sh:targetClass schema:Orders ;
    sh:property [
        sh:path      schema:Orders_orderDate ;
        sh:datatype  xsd:dateTime ;
        sh:minCount  1 ;
        sh:maxCount  1 ;
        sh:name      "ORDER_DATE"
    ] .
```

---

## Dependencies

| Library                | Version  | Purpose                          |
|------------------------|----------|----------------------------------|
| Spring Boot            | 4.1.0    | Application framework            |
| Apache Jena            | 5.2.0    | RDF / OWL / SHACL modelling      |
| Snowflake JDBC         | 3.16.1   | Snowflake connectivity           |
| PostgreSQL JDBC        | 42.7.3   | PostgreSQL connectivity          |
| H2                     | (managed) | In-memory DB for tests          |
| springdoc-openapi      | 2.6.0    | Swagger UI                       |
| Lombok                 | (managed) | Boilerplate reduction           |
