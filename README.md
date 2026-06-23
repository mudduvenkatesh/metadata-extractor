# RDF Metadata Extractor

A **Spring Boot 4.1.0** application that extracts relational database schema metadata
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
DatabaseConnectorFactory        MetadataExtractorFactory
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
  (RDF4J OWL)         (RDF4J SHACL)
        │                   │
        └─────────┬─────────┘
                  ▼
            RdfSerializer (Rio)
         (Turtle / JSON-LD / RDF/XML / N-Triples)
```

---

## How `DatabaseConnectorFactory` is Initialized

This uses Spring's **collection injection + strategy pattern** — one of the cleanest
extensibility mechanisms in the Spring ecosystem.

### The flow from startup to runtime lookup

**Step 1 — Each connector declares itself as a Spring bean**

Both connectors are annotated `@Component` and implement the `DatabaseConnector` interface:

```java
@Component
public class SnowflakeDatabaseConnector implements DatabaseConnector {
    public DatabaseType supportedType() { return DatabaseType.SNOWFLAKE; }
    public Connection connect(ExtractionRequest request) { ... }
}

@Component
public class PostgreSQLDatabaseConnector implements DatabaseConnector {
    public DatabaseType supportedType() { return DatabaseType.POSTGRESQL; }
    public Connection connect(ExtractionRequest request) { ... }
}
```

**Step 2 — Spring's component scan finds them automatically**

Because both classes live under `com.rdf.metadata` (the `@SpringBootApplication` base package),
the component scan picks them up at startup. No explicit registration needed.

**Step 3 — Spring injects a `List<DatabaseConnector>` into the factory**

When Spring sees a constructor parameter of type `List<SomeInterface>`, it automatically
collects **every bean in the context that implements that interface** and injects them all as a list:

```java
@Component
public class DatabaseConnectorFactory {

    private final Map<DatabaseType, DatabaseConnector> connectors;

    // Spring finds ALL DatabaseConnector beans and passes them here
    public DatabaseConnectorFactory(List<DatabaseConnector> connectorList) {
        this.connectors = connectorList.stream()
                .collect(Collectors.toMap(
                        DatabaseConnector::supportedType,   // key   = SNOWFLAKE / POSTGRESQL
                        Function.identity()                 // value = the connector itself
                ));
    }
}
```

At startup, `connectorList` will contain:
```
[ SnowflakeDatabaseConnector, PostgreSQLDatabaseConnector ]
```

**Step 4 — The list is indexed into a Map by type**

The stream collector turns the list into:
```
{
  SNOWFLAKE   → SnowflakeDatabaseConnector instance,
  POSTGRESQL  → PostgreSQLDatabaseConnector instance
}
```

**Step 5 — Runtime lookup is a simple `Map.get()`**

```java
public DatabaseConnector getConnector(DatabaseType type) {
    DatabaseConnector connector = connectors.get(type);  // O(1) lookup
    if (connector == null) {
        throw new DatabaseConnectionException(
            "No connector registered for database type: " + type
            + ". Available types: " + connectors.keySet());
    }
    return connector;
}
```

### Full dependency graph at startup

```
@SpringBootApplication
    └── Component Scan (com.rdf.metadata.**)
            │
            ├── SnowflakeDatabaseConnector    (@Component)
            │       └── supportedType() → SNOWFLAKE
            │
            ├── PostgreSQLDatabaseConnector   (@Component)
            │       └── supportedType() → POSTGRESQL
            │
            └── DatabaseConnectorFactory      (@Component)
                    │
                    └── List<DatabaseConnector> injected by Spring
                            │
                            └── Streamed into Map<DatabaseType, DatabaseConnector>
                                    SNOWFLAKE  → SnowflakeDatabaseConnector
                                    POSTGRESQL → PostgreSQLDatabaseConnector
```

> The exact same pattern applies to `MetadataExtractorFactory` and `AbstractJdbcMetadataExtractor`.

### Adding a new database — only 3 steps

To add MySQL (for example), you only touch these three things — the factory, service,
and controller are **never modified**:

```java
// 1. Add the new type to the enum
public enum DatabaseType { SNOWFLAKE, POSTGRESQL, MYSQL }

// 2. Implement the connector — Spring auto-discovers it via @Component
@Component
public class MySQLDatabaseConnector implements DatabaseConnector {
    public DatabaseType supportedType() { return DatabaseType.MYSQL; }
    public Connection connect(ExtractionRequest request) { ... }
}

// 3. Extend the extractor
@Component
public class MySQLMetadataExtractor extends AbstractJdbcMetadataExtractor {
    protected DatabaseType supportedType() { return DatabaseType.MYSQL; }
    // override extractCheckConstraints() if needed
}
```

Spring automatically includes both new beans in the injected lists at next startup.
`getConnector(DatabaseType.MYSQL)` and `getExtractor(DatabaseType.MYSQL)` start working
with zero changes to any existing class. This is the **Open/Closed Principle** in practice.

---

## Mapping Rules

### SQL → OWL

| Relational Concept   | OWL Construct                                          |
|----------------------|--------------------------------------------------------|
| TABLE                | `owl:Class`                                            |
| VIEW                 | `owl:Class` (annotated with `tableType=VIEW`)          |
| Non-FK COLUMN        | `owl:DatatypeProperty` + `rdfs:range` (XSD type)       |
| FK COLUMN            | `owl:ObjectProperty` + `rdfs:range` (referenced class) |
| NOT NULL column      | `owl:minCardinality 1` restriction on domain class     |
| PRIMARY KEY          | `owl:hasKey`                                           |
| UNIQUE constraint    | `owl:hasKey`                                           |
| CHECK constraint     | `rdfs:comment` annotation                              |
| Remarks/comments     | `rdfs:comment`                                         |

### SQL → SHACL

| Relational Concept   | SHACL Construct                                        |
|----------------------|--------------------------------------------------------|
| TABLE                | `sh:NodeShape` with `sh:targetClass`                   |
| Non-FK COLUMN        | `sh:PropertyShape` with `sh:datatype`, `sh:maxCount 1` |
| FK COLUMN            | `sh:PropertyShape` with `sh:class`, `sh:nodeKind IRI`  |
| NOT NULL column      | `sh:minCount 1`                                        |
| VARCHAR(n) column    | `sh:maxLength n`                                       |
| CHECK constraint     | `sh:SPARQLConstraint` (SQL expression preserved)       |
| UNIQUE constraint    | `sh:SPARQLConstraint` (noted as comment)               |

---

## Running the Application

### Prerequisites

- Java 21+
- Maven 3.9+
- HashiCorp Vault (optional — only required when using `vaultSecretPath` in requests)

### Build

```bash
mvn clean package -DskipTests
java -jar target/metadata-extractor-1.0.0-SNAPSHOT.jar
```

### Run Tests

```bash
mvn test
```

Tests use an **H2 in-memory database** and a **mocked Vault** — no external services required.

---

## REST API

Swagger UI: **http://localhost:8080/swagger-ui.html**

### `POST /api/v1/metadata/extract`

Full pipeline: connect → extract → OWL + SHACL → serialized RDF.

**Snowflake — Password auth:**
```json
{
  "databaseType": "SNOWFLAKE",
  "authMode": "PASSWORD",
  "account": "xy12345.us-east-1",
  "username": "my_user",
  "password": "my_password",
  "databaseName": "MY_DATABASE",
  "schemaName": "PUBLIC",
  "warehouse": "COMPUTE_WH",
  "role": "SYSADMIN",
  "rdfFormat": "TURTLE",
  "includeShaclShapes": true,
  "includeOwlAxioms": true
}
```

**Snowflake — Key Pair auth (inline PEM):**
```json
{
  "databaseType": "SNOWFLAKE",
  "authMode": "KEY_PAIR",
  "account": "xy12345.us-east-1",
  "username": "my_user",
  "schemaName": "PUBLIC",
  "warehouse": "COMPUTE_WH",
  "privateKeyPem": "-----BEGIN PRIVATE KEY-----\nMIIE...\n-----END PRIVATE KEY-----",
  "rdfFormat": "TURTLE"
}
```

**Snowflake — Key Pair auth from Vault (recommended for production):**
```json
{
  "databaseType": "SNOWFLAKE",
  "authMode": "KEY_PAIR",
  "account": "xy12345.us-east-1",
  "username": "svc_rdf_extractor",
  "schemaName": "PUBLIC",
  "warehouse": "COMPUTE_WH",
  "vaultSecretPath": "prod/snowflake-keypair",
  "rdfFormat": "TURTLE"
}
```

**Snowflake — Password auth from Vault:**
```json
{
  "databaseType": "SNOWFLAKE",
  "authMode": "PASSWORD",
  "account": "xy12345.us-east-1",
  "username": "my_user",
  "schemaName": "PUBLIC",
  "vaultSecretPath": "prod/snowflake-password"
}
```

**PostgreSQL:**
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

## Snowflake Key Pair Authentication Setup

```bash
# 1. Generate PKCS#8 private key (unencrypted)
openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out rsa_key.p8 -nocrypt

# 1b. Or encrypted (recommended for production)
openssl genrsa 2048 | openssl pkcs8 -topk8 -inform PEM -out rsa_key.p8 -v2 aes-256-cbc

# 2. Derive the public key
openssl rsa -in rsa_key.p8 -pubout -out rsa_key.pub

# 3. Register in Snowflake (strip headers before pasting)
# ALTER USER my_user SET RSA_PUBLIC_KEY='MIIBIjANBgkq...';

# 4. Store the key in Vault
vault kv put secret/rdf-extractor/prod/snowflake-keypair \
    privateKey=@rsa_key.p8 \
    privateKeyPassphrase="my_passphrase"   # omit if unencrypted
```

### Credential resolution priority

For **KEY_PAIR** auth, the private key is resolved in this order:

| Priority | Source | Field |
|----------|--------|-------|
| 1 (highest) | HashiCorp Vault | `vaultSecretPath` |
| 2 | Inline PEM string | `privateKeyPem` |
| 3 | Filesystem path | `privateKeyPath` |
| 4 | Base64 DER bytes | `privateKeyBase64` |

For **PASSWORD** auth:

| Priority | Source | Field |
|----------|--------|-------|
| 1 (highest) | HashiCorp Vault | `vaultSecretPath` |
| 2 | Inline string | `password` |

---

## HashiCorp Vault Configuration

### `bootstrap.yml` — Spring Cloud Vault connection

```yaml
spring:
  cloud:
    vault:
      host: ${VAULT_HOST:localhost}
      port: ${VAULT_PORT:8200}
      scheme: https
      authentication: ${VAULT_AUTH_METHOD:TOKEN}
      token: ${VAULT_TOKEN:}           # for token auth
      app-role:                        # for AppRole auth (recommended for prod)
        role-id: ${VAULT_APP_ROLE_ID:}
        secret-id: ${VAULT_APP_ROLE_SECRET_ID:}
      kubernetes:                      # for Kubernetes auth
        role: rdf-metadata-extractor
      fail-fast: false                 # don't crash if Vault is unreachable at startup
```

### `application.yml` — application-level Vault settings

```yaml
vault:
  kv-engine: secret          # KV mount point
  kv-version: 2              # 1 = KV v1, 2 = KV v2
  secret-path-prefix: rdf-extractor   # prepended to every vaultSecretPath in a request
  enabled: true
```

### Expected Vault secret structure

```
# Key pair auth secret
secret/data/rdf-extractor/<vaultSecretPath>
  privateKey            = "-----BEGIN PRIVATE KEY-----\n..."
  privateKeyPassphrase  = "optional_passphrase"

# Password auth secret
secret/data/rdf-extractor/<vaultSecretPath>
  password              = "snowflake_password"
```

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

| Library                   | Version   | Purpose                           |
|---------------------------|-----------|-----------------------------------|
| Spring Boot               | 4.1.0     | Application framework             |
| Spring Cloud Vault        | 2025.0.0  | HashiCorp Vault integration       |
| Eclipse RDF4J             | 5.3.3     | RDF / OWL / SHACL modelling (Rio) |
| Snowflake JDBC            | 3.16.1    | Snowflake connectivity            |
| PostgreSQL JDBC           | 42.7.3    | PostgreSQL connectivity           |
| H2                        | (managed) | In-memory DB for tests            |
| springdoc-openapi         | 2.6.0     | Swagger UI                        |
| Lombok                    | (managed) | Boilerplate reduction             |

---

## Schema Versioning — Storing Extractions in GraphDB

Each time a schema is extracted it is saved as a permanent, independently addressable snapshot in GraphDB. All versions are kept — nothing is overwritten — so the full history of schema evolution is always available.

### Storage strategy — timestamp-scoped IRIs (no named graphs)

Every snapshot uses **timestamp-scoped IRIs** rather than named graphs or RDF reification. The extraction timestamp is baked directly into the IRI of every individual belonging to that snapshot. All triples land in the default graph. This keeps SPARQL queries simple and makes every snapshot natively addressable.

```
extraction/<db>/<schema>/<yyyy-MM-dd_HH-mm-ss-SSS>                ← ExtractionRecord
extraction/<db>/<schema>/<timestamp>/table/<TableName>             ← DatabaseTable
extraction/<db>/<schema>/<timestamp>/column/<TableName>_<ColName>  ← TableColumn
repo/<db>                                                           ← stable DataRepository (no timestamp)
repo/<db>/<schema>                                                  ← stable DatabaseSchema (no timestamp)
```

The **stable** `DataRepository` and `DatabaseSchema` IRIs contain no timestamp — they are shared across all extractions for the same database and schema, acting as the anchor for history queries.

### Why not named graphs?

Two alternatives were considered:

**RDF Reification** wraps every triple in a `rdf:Statement` blank node with `rdf:subject`, `rdf:predicate`, `rdf:object`, plus a provenance link. It is RDF-standard but produces ~4× the triple count (an 80-column schema generates ~1,200 overhead triples), and SPARQL queries become deeply nested and hard to maintain.

**Named graphs** scope each snapshot's triples to a dedicated graph IRI. This is clean but requires SPARQL queries to specify or iterate over graph names, adds complexity to the storage layer, and couples the query logic to the graph naming scheme.

**Timestamp-scoped IRIs** give the same isolation benefit — every snapshot's nodes are distinguishable by IRI prefix — without the overhead of reification or the query complexity of named graphs. The tradeoff is slightly longer IRIs, which is negligible in practice.

### IRI examples

```
# Unique ExtractionRecord — timestamp in the IRI
http://example.org/schema#extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123

# Table node scoped to that extraction
http://example.org/schema#extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/table/Orders

# Column node scoped to that extraction
http://example.org/schema#extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/column/Orders_OrderId

# Stable repository node — shared across all extractions
http://example.org/schema#repo/MY_DB

# Stable schema node — shared across all extractions
http://example.org/schema#repo/MY_DB/PUBLIC
```

### RDF structure of a snapshot

```turtle
@prefix dr:     <http://example.org/datarepository#> .
@prefix schema: <http://example.org/schema#> .
@prefix xsd:    <http://www.w3.org/2001/XMLSchema#> .

# ── ExtractionRecord (root of this snapshot) ─────────────────────────────────
schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123
    a                  dr:ExtractionRecord ;
    dr:forRepository   schema:repo/MY_DB ;
    dr:forSchema       schema:repo/MY_DB/PUBLIC ;
    dr:databaseType    "SNOWFLAKE" ;
    dr:databaseName    "MY_DB" ;
    dr:warehouse       "COMPUTE_WH" ;
    dr:authMode        "KEY_PAIR" ;
    dr:extractedAt     "2026-06-23T10:00:00.123Z"^^xsd:dateTime ;
    dr:tableCount      3 ;
    dr:columnCount     12 ;
    dr:hasTable        schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/table/Orders ,
                       schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/table/Customers .

# ── Stable repository + schema nodes ─────────────────────────────────────────
schema:repo/MY_DB
    a               dr:DataRepository ;
    dr:databaseName "MY_DB" ;
    dr:databaseType "SNOWFLAKE" .

schema:repo/MY_DB/PUBLIC
    a                dr:DatabaseSchema ;
    dr:schemaName    "PUBLIC" ;
    dr:hasExtraction schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123 .

# ── Per-extraction table node ─────────────────────────────────────────────────
schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/table/Orders
    a            dr:DatabaseTable ;
    dr:tableName "ORDERS" ;
    dr:tableType "TABLE" ;
    dr:hasColumn schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/column/Orders_OrderId ,
                 schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/column/Orders_Status .

# ── Per-extraction column node ────────────────────────────────────────────────
schema:extraction/MY_DB/PUBLIC/2026-06-23_10-00-00-123/column/Orders_OrderId
    a                  dr:TableColumn ;
    dr:columnName      "ORDER_ID" ;
    dr:sqlDataType     "BIGINT" ;
    dr:ordinalPosition 1 ;
    dr:isNullable      false ;
    dr:isPrimaryKey    true ;
    dr:isForeignKey    false .
```

### Querying across versions

**Get the latest extraction for a schema:**

```sparql
PREFIX dr: <http://example.org/datarepository#>

SELECT ?record ?extractedAt ?tableCount WHERE {
    ?schema  a                dr:DatabaseSchema ;
             dr:schemaName    "PUBLIC" ;
             dr:hasExtraction ?record .
    ?record  dr:extractedAt   ?extractedAt ;
             dr:tableCount    ?tableCount .
}
ORDER BY DESC(?extractedAt)
LIMIT 1
```

**Get all tables in the latest extraction:**

```sparql
PREFIX dr: <http://example.org/datarepository#>

SELECT ?tableName ?tableType WHERE {
    {
        SELECT ?record WHERE {
            ?schema dr:schemaName    "PUBLIC" ;
                    dr:hasExtraction ?record .
            ?record dr:extractedAt   ?at .
        }
        ORDER BY DESC(?at)
        LIMIT 1
    }
    ?record dr:hasTable ?table .
    ?table  dr:tableName ?tableName ;
            dr:tableType ?tableType .
}
ORDER BY ?tableName
```

**List all extraction dates for a schema (full history):**

```sparql
PREFIX dr: <http://example.org/datarepository#>

SELECT ?record ?extractedAt ?tableCount ?columnCount WHERE {
    ?schema  dr:schemaName    "PUBLIC" ;
             dr:hasExtraction ?record .
    ?record  dr:extractedAt   ?extractedAt ;
             dr:tableCount    ?tableCount ;
             dr:columnCount   ?columnCount .
}
ORDER BY DESC(?extractedAt)
```

**Find all columns in a specific extraction:**

```sparql
PREFIX dr: <http://example.org/datarepository#>

SELECT ?tableName ?columnName ?sqlDataType ?isNullable WHERE {
    ?record  dr:extractedAt  "2026-06-23T10:00:00.123Z"^^xsd:dateTime ;
             dr:hasTable     ?table .
    ?table   dr:tableName    ?tableName ;
             dr:hasColumn    ?col .
    ?col     dr:columnName   ?columnName ;
             dr:sqlDataType  ?sqlDataType ;
             dr:isNullable   ?isNullable .
}
ORDER BY ?tableName ?columnName
```

### GraphDB configuration

```yaml
# application.yml
graphdb:
  server-url:                  http://localhost:7200
  repository-id:               schema-versions
  username:                    ${GRAPHDB_USER:}
  password:                    ${GRAPHDB_PASSWORD:}
  connection-timeout-ms:       5000
  read-timeout-ms:             30000
  create-repository-if-absent: true
  enabled:                     true
```

The repository is created automatically on first startup (using GraphDB's management REST API) with an `owl-horst-optimized` ruleset, enabling OWL2-RL inference over the `dr:` vocabulary.
