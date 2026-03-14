# Heron — Product Requirements Document

## 1. Executive Summary / Problem Statement

Modern infrastructure spans dozens of cloud services, multiple providers, and thousands of resources. Teams struggle with three compounding problems:

**The Visibility Gap.** There is no single place to ask "what is my infrastructure right now?" AWS Console shows EC2. GitHub shows repositories. IAM policies live in a different service from the resources they protect. A platform engineer investigating an incident must context-switch between five consoles, three CLIs, and a pile of Terraform state files to assemble a coherent picture.

**Compliance Drift.** Compliance goals ("all S3 buckets must block public access", "every production repository must require code review") are written in documentation but enforced nowhere. Violations accumulate silently between audits. By the time a security engineer runs a manual check, dozens of resources may be non-compliant—and there is no record of when they drifted or who changed them.

**Shift-Left Failure.** Even teams that have adopted Infrastructure-as-Code struggle to enforce policy at plan time. Terraform plan output is text, not data. Pre-flight checks require bespoke parsing of HCL or JSON, are brittle, and cannot reason about the effect of a change on existing infrastructure state.

**The Heron Thesis.** Heron is a unified, immutable infrastructure database. It continuously ingests the state of your infrastructure from cloud providers and developer platforms, stores every fact with a timestamp, and makes the entire corpus queryable with Datalog. Compliance goals are expressed as queries, not scripts. Historical state is a first-class feature. Pre-flight enforcement is possible because Heron can project a Terraform plan onto existing state and evaluate compliance before anything is applied.

---

## 2. Goals and Non-Goals

### MVP Goals

- **G-1.** Ingest infrastructure state from AWS and GitHub via point-in-time connector runs.
- **G-2.** Expose a Datalog query interface over all ingested state.
- **G-3.** Support Checks: named boolean compliance goals evaluated after every sync.
- **G-4.** Support Reports: named list queries with change detection between runs.
- **G-5.** Maintain full historical state; support as-of queries against any past point in time.
- **G-6.** Operate as a single deployable unit (uberjar + Datomic transactor + PostgreSQL).

### Post-MVP Goals

- **G-7.** Real-time connectors via event streams (EventBridge, GitHub webhooks).
- **G-8.** Strong Checks: pre-flight compliance enforcement against Terraform plans.
- **G-9.** Browser UI for query authoring and dashboard views.
- **G-10.** Connector SDK for third-party and internal connector development.
- **G-11.** Multi-tenancy: isolated namespaces for multiple teams or environments.

### Explicit Non-Goals

- **NG-1.** Heron does not remediate violations. It reports them; humans fix them.
- **NG-2.** No graphical UI in MVP. The interface is API and CLI.
- **NG-3.** No multi-tenancy in MVP. Single-tenant deployment only.
- **NG-4.** Heron does not replace Terraform, Pulumi, or any IaC tool. It observes; it does not provision.
- **NG-5.** Heron does not enforce access control on infrastructure resources. It models them.

---

## 3. User Personas

### Pat — Platform Engineer

Pat runs the internal developer platform. They need to answer questions like "which EC2 instances are not tagged with a cost center?" and "show me every IAM role that has been modified in the last 30 days." Pat writes Checks and Reports and is Heron's primary operator. Pat is comfortable with Clojure and Datalog or is willing to learn.

### Sam — Security Engineer

Sam owns compliance and audit. They need evidence that controls are in place, want alerts when something drifts out of compliance, and need historical records for audit trails. Sam cares about Check Violations and their timestamps. Sam is less technical than Pat but can read query results.

### Dana — Developer

Dana works on application code and occasionally touches infrastructure. Dana wants to understand what resources exist and their configuration without learning the AWS CLI. Dana may use Strong Checks (post-MVP) via a GitHub Action that blocks a Terraform PR if it would create a violation.

### Alex — Heron Admin

Alex deploys and operates Heron itself. Alex configures connector credentials, manages connector schedules, monitors sync health, and upgrades Heron. Alex may be the same person as Pat in smaller teams.

---

## 4. Core Concepts / Glossary

| Term | Definition |
|------|-----------|
| **Datomic** | The underlying database. An immutable, append-only store where facts are never updated in place; history is always preserved. |
| **Entity** | A collection of attributes describing a single infrastructure resource (e.g., an S3 bucket, a GitHub repository). |
| **Attribute** | A typed, namespaced property of an entity (e.g., `:aws.s3.bucket/name`, `:github.repo/private`). |
| **Fact** | A single assertion: [entity-id, attribute, value, transaction-id]. Facts accumulate; they are not overwritten. |
| **Connector** | A component that fetches state from a provider and transacts it into Datomic. |
| **Connector Run** | A single execution of a connector producing a complete (or incremental) snapshot of a provider's state. |
| **Sync** | The process of a connector run writing new facts into Datomic and issuing native retractions for resources that no longer exist. |
| **Datalog Query** | A declarative logic query executed against the Datomic database. The primary query interface. |
| **Check** | A named boolean compliance goal: a Datalog query that returns violations. If the query returns zero results, the check passes. |
| **Check Violation** | A specific entity that causes a Check to fail, recorded with a timestamp. |
| **Check Transition** | A state change for a Check Violation: `:opened` (newly failing) or `:resolved` (newly passing). |
| **Report** | A named list query whose results are tracked over time. Heron records when items appear or disappear from the result set. |
| **Strong Check** | A post-MVP Check evaluated against a projection of proposed Terraform changes onto current state. |
| **As-of Query** | A Datalog query scoped to database state at a specific past point in time. |
| **Provider** | An external system Heron ingests from (AWS, GitHub). |
| **Schema** | The set of Datomic attributes registered for a provider's resource types. |

---

## 5. Functional Requirements

### 5.1 Connectors

#### MVP Required

| ID | Requirement |
|----|-------------|
| FR-CON-01 | Heron MUST provide an `IConnector` protocol that any connector must implement. |
| FR-CON-02 | A connector run MUST produce a sequence of entity maps conforming to the Heron entity map format. |
| FR-CON-03 | Each entity map MUST include a `:heron/id` — a stable, globally unique string identifier for the resource. |
| FR-CON-04 | The sync process MUST be idempotent: running the same connector twice without underlying changes MUST NOT produce new transactions. |
| FR-CON-05 | Resources absent from a connector run that were present in the previous run MUST be retracted using `[:db/retractEntity eid]` (or targeted `:db/retract` datoms). |
| FR-CON-06 | Connector run metadata (start time, end time, status, entity count, errors) MUST be recorded as Datomic entities. |
| FR-CON-07 | Heron MUST include a connector for AWS covering: S3, EC2, IAM (roles, policies, users), RDS, Lambda, Route53, ACM. |
| FR-CON-08 | Heron MUST include a connector for GitHub covering: organizations, repositories, teams, team memberships. |
| FR-CON-09 | Connector credentials MUST be supplied via environment variables or a config file; never hardcoded. |
| FR-CON-10 | A failed connector run MUST NOT partially update Datomic state; failures must be atomic. |

#### Post-MVP Future

| ID | Requirement |
|----|-------------|
| FR-CON-11 | AWS connector MUST support real-time ingestion via EventBridge events, achieving <60s latency from change to queryable fact. |
| FR-CON-12 | GitHub connector MUST support real-time ingestion via GitHub webhooks. |
| FR-CON-13 | Connector SDK MUST allow third parties to implement and register connectors without modifying Heron core. |
| FR-CON-14 | Heron MUST support GCP and Azure connectors. |
| FR-CON-15 | Multi-account AWS ingestion MUST be supported via IAM role assumption. |

### 5.2 Query Interface

#### MVP Required

| ID | Requirement |
|----|-------------|
| FR-QRY-01 | Heron MUST expose a Datalog query API at `POST /api/v1/query`. |
| FR-QRY-02 | The query API MUST accept a Datalog query string and optional query arguments. |
| FR-QRY-03 | The query API MUST support as-of queries scoped to a specific Datomic `t` or wall-clock time. |
| FR-QRY-04 | Heron MUST provide a CLI command `heron query` that submits a Datalog query and prints results. |
| FR-QRY-05 | Heron MUST expose a schema discovery API at `GET /api/v1/schema` listing all registered attributes with their types and docs. |
| FR-QRY-06 | Query results MUST be returned as EDN and optionally as JSON. |

#### Post-MVP Future

| ID | Requirement |
|----|-------------|
| FR-QRY-07 | Heron SHOULD provide a browser-based query editor with attribute autocomplete. |
| FR-QRY-08 | Heron SHOULD support saved queries with named parameters. |

### 5.3 Checks

#### MVP Required

| ID | Requirement |
|----|-------------|
| FR-CHK-01 | A Check MUST be defined as an EDN map with at minimum: `:check/id`, `:check/name`, `:check/description`, `:check/query`. |
| FR-CHK-02 | The `:check/query` MUST be a Datalog query that returns entity-ids of violating resources. Zero results means passing. |
| FR-CHK-03 | Checks MUST be evaluated after every connector sync. |
| FR-CHK-04 | Each evaluation MUST produce a Check Evaluation record stored in Datomic with: check-id, run timestamp, pass/fail, violating entity count, and a list of violating entity-ids. |
| FR-CHK-05 | When a violating entity appears for the first time, Heron MUST record a `:transition/opened` event. |
| FR-CHK-06 | When a previously violating entity no longer appears, Heron MUST record a `:transition/resolved` event. |
| FR-CHK-07 | Heron MUST ship at least 5 built-in Checks (see §9). |
| FR-CHK-08 | Users MUST be able to define custom Checks by placing EDN files in a configured directory or registering them via API. |

#### Post-MVP Future

| ID | Requirement |
|----|-------------|
| FR-CHK-09 | Check Violations SHOULD trigger webhook notifications (Slack, PagerDuty) on transition. |
| FR-CHK-10 | Checks SHOULD support severity levels: `:critical`, `:high`, `:medium`, `:low`. |

### 5.4 Reports

#### MVP Required

| ID | Requirement |
|----|-------------|
| FR-RPT-01 | A Report MUST be defined as an EDN map with: `:report/id`, `:report/name`, `:report/description`, `:report/query`. |
| FR-RPT-02 | The `:report/query` MUST be a Datalog query returning a sequence of maps (pull results or projected tuples). |
| FR-RPT-03 | Reports MUST be evaluated after every connector sync. |
| FR-RPT-04 | Each evaluation MUST produce a Report Run stored in Datomic recording the full result set and run timestamp. |
| FR-RPT-05 | Heron MUST compare each Report Run to the previous run and record item-level additions and removals. |
| FR-RPT-06 | Heron MUST ship at least 3 built-in Reports (see §9). |
| FR-RPT-07 | Report results MUST be retrievable via `GET /api/v1/reports/:id/latest` and `GET /api/v1/reports/:id/runs/:run-id`. |

#### Post-MVP Future

| ID | Requirement |
|----|-------------|
| FR-RPT-08 | Report changes SHOULD trigger webhook notifications. |

### 5.5 Strong Checks (Post-MVP)

| ID | Requirement |
|----|-------------|
| FR-SC-01 | Heron MUST expose `POST /api/v1/strong-check` accepting a Terraform JSON plan and a list of Check IDs. |
| FR-SC-02 | Heron MUST parse the Terraform plan into Heron entity maps representing the post-apply state. |
| FR-SC-03 | Heron MUST use `datomic.api/with` to project the proposed changes onto the current database without persisting them. |
| FR-SC-04 | Heron MUST evaluate the specified Checks against the projected database and return pass/fail results. |
| FR-SC-05 | The API response MUST include for each Check: pass/fail, and for failures, the violating entity-ids with human-readable descriptions. |
| FR-SC-06 | Heron MUST provide a GitHub Action that invokes the Strong Check API from a Terraform PR workflow. |

---

## 6. Technical Architecture Overview

### Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Heron Process (JVM)                        │
│                                                                     │
│  ┌─────────────┐   ┌──────────────────┐   ┌─────────────────────┐  │
│  │  Scheduler  │──▶│  Connector       │──▶│  Sync Engine        │  │
│  │  (chime)    │   │  (IConnector)    │   │  (transact/upsert)  │  │
│  └─────────────┘   └──────────────────┘   └──────────┬──────────┘  │
│                                                       │             │
│                    ┌──────────────────┐               ▼             │
│  ┌─────────────┐   │  Check Engine   │◀──── Datomic Peer API ────▶ │
│  │  Ring/      │──▶│  (eval-checks)  │               ▲             │
│  │  Reitit API │   └──────────────────┘               │             │
│  │  Server     │   ┌──────────────────┐               │             │
│  │             │──▶│  Report Engine  │───────────────-┘             │
│  │             │   │  (eval-reports) │                              │
│  │             │   └──────────────────┘                             │
│  └─────────────┘                                                    │
└─────────────────────────────────────────────────────────────────────┘
          │                                          │
          ▼                                          ▼
   ┌─────────────┐                         ┌──────────────────┐
   │  CLI Client │                         │ Datomic          │
   │  (heron)    │                         │ Transactor       │
   └─────────────┘                         └────────┬─────────┘
                                                    │
                                                    ▼
                                           ┌──────────────────┐
                                           │   PostgreSQL     │
                                           │   (storage)      │
                                           └──────────────────┘
```

### Technology Choices

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Language | Clojure | Natural fit for Datomic; immutable data model matches Heron's semantics; strong data transformation story |
| Database | Datomic (on-prem/cloud) | Immutable facts, built-in history, Datalog query engine, `db/with` for projections |
| Storage backend | PostgreSQL | Datomic-supported, operationally familiar, easy to run locally |
| HTTP server | Ring + Reitit | Standard Clojure HTTP stack; data-driven routing |
| Scheduler | Chime | Simple Clojure scheduling library |
| AWS SDK | aws-api (Cognitect) | Data-oriented, no reflection, pure Clojure |
| Build | tools.build (deps.edn) | Standard Clojure toolchain |
| Packaging | Uberjar + Docker | Simple deployment; no Kubernetes required for MVP |

### Data Flow: Connector Run

```
1. Scheduler triggers connector run at configured interval
2. Connector fetches all resources from provider API
3. Connector emits sequence of entity maps [{:heron/id "..." :aws.s3.bucket/name "..." ...}]
4. Sync Engine:
   a. Loads previous connector run's entity-id set from Datomic
   b. Diffs current entity maps against prior state
   c. Transacts new/changed entities as upserts (using :heron/id as identity)
   d. For entities absent in current run: transacts [:db/retractEntity eid]
   e. Records ConnectorRun entity with metadata
5. Check Engine evaluates all enabled Checks against new db value
6. Report Engine evaluates all enabled Reports, diffs against last run
```

### Data Flow: Check Evaluation

```
1. Receive db value post-sync
2. For each enabled Check:
   a. Execute check's Datalog query against db
   b. Collect result set of violating entity-ids
   c. Load previous evaluation's violating set
   d. Compute transitions: (added = opened, removed = resolved)
   e. Transact CheckEvaluation entity + transition entities
```

### Deployment Model (MVP)

Single Docker Compose stack:
- `heron` service: JVM process running the uberjar
- `datomic-transactor` service: Datomic transactor process
- `postgres` service: PostgreSQL as Datomic storage backend

Configuration via environment variables. Credentials (AWS, GitHub tokens) injected at runtime.

---

## 7. Connector Specification

### `IConnector` Protocol

```clojure
(defprotocol IConnector
  (connector-id [this]
    "Returns a keyword identifying this connector, e.g. :aws/s3")

  (fetch-entities [this config]
    "Fetches all entities from the provider. Returns a sequence of
     entity maps, each containing :heron/id and provider attributes.")

  (schema [this]
    "Returns the Datomic schema (vector of attribute maps) for this
     connector's entity types. Called once at startup to install schema."))
```

### Entity Map Format

Every entity map emitted by a connector must conform to:

```clojure
{;; Required: stable globally unique identifier for this resource
 :heron/id "aws:123456789012:s3:bucket:my-bucket-name"

 ;; Optional: human-readable label for display
 :heron/label "my-bucket-name"

 ;; Optional: provider tag
 :heron/provider :aws

 ;; Provider-specific attributes (namespaced)
 :aws.s3.bucket/name "my-bucket-name"
 :aws.s3.bucket/region "us-east-1"
 :aws.s3.bucket/versioning-enabled true
 :aws.s3.bucket/public-access-blocked true
 ;; ...
}
```

### `:heron/id` Convention

The `:heron/id` is a string that must be:

- **Stable**: the same logical resource always produces the same `:heron/id` across connector runs.
- **Globally unique**: no two distinct resources in any provider can share an id.
- **Human-readable**: structured to aid debugging.

Recommended format: `<provider>:<account-or-org>:<service>:<resource-type>:<resource-id>`

Examples:
- `aws:123456789012:s3:bucket:my-bucket`
- `aws:123456789012:iam:role:AWSServiceRoleForEC2`
- `github:my-org:repo:my-org/my-repo`
- `github:my-org:team:my-org/platform-team`

Datomic schema: `:heron/id` is a `:db.type/string` attribute with `:db.unique/identity` ensuring upsert semantics.

### Deletion: Native Datomic Retractions

When a resource is absent from a connector run, Heron retracts its entity using `[:db/retractEntity eid]` (or targeted `:db/retract` datoms for individual attributes). Datomic records each retraction as an immutable datom in the transaction log — no history is lost. An `as-of` query scoped to any point before the retraction will return the entity exactly as it existed at that time.

Queries naturally return only live (non-retracted) entities; no filter clause is required. To inspect historical state, scope the query with `datomic.api/as-of`:

```clojure
;; State of the database 30 days ago — retracted entities reappear
(d/as-of db (java.util.Date. (- (System/currentTimeMillis) (* 30 86400000))))
```

### Connector Run Metadata Schema

```clojure
[{:db/ident       :connector-run/id
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/unique      :db.unique/identity}

 {:db/ident       :connector-run/connector-id
  :db/valueType   :db.type/keyword
  :db/cardinality :db.cardinality/one}

 {:db/ident       :connector-run/started-at
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}

 {:db/ident       :connector-run/finished-at
  :db/valueType   :db.type/instant
  :db/cardinality :db.cardinality/one}

 {:db/ident       :connector-run/status
  :db/valueType   :db.type/keyword   ;; :success | :failure | :partial
  :db/cardinality :db.cardinality/one}

 {:db/ident       :connector-run/entity-count
  :db/valueType   :db.type/long
  :db/cardinality :db.cardinality/one}

 {:db/ident       :connector-run/error-message
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one}]
```

### AWS Connector: Resource Coverage

| Service | Resource Types | Key Attributes |
|---------|---------------|----------------|
| S3 | Bucket | name, region, versioning, public-access-block config, encryption, lifecycle rules |
| EC2 | Instance | instance-id, type, state, AMI, VPC, subnet, security groups, tags |
| EC2 | SecurityGroup | group-id, name, VPC, ingress rules, egress rules |
| EC2 | VPC | vpc-id, CIDR, default? |
| IAM | Role | role-name, ARN, trust policy, attached policy ARNs, tags |
| IAM | Policy | policy-name, ARN, path, default-version-id, attachment count |
| IAM | User | username, ARN, MFA-enabled?, password-last-used, access key count |
| RDS | DBInstance | identifier, engine, version, instance-class, multi-AZ?, publicly-accessible?, encrypted? |
| Lambda | Function | name, runtime, handler, role ARN, timeout, memory, environment variable names |
| Route53 | HostedZone | zone-id, name, private? |
| Route53 | RecordSet | name, type, TTL, values |
| ACM | Certificate | domain, status, renewal eligibility, expiry date, SANs |

### GitHub Connector: Resource Coverage

| Resource | Key Attributes |
|----------|---------------|
| Organization | login, name, billing email, two-factor-required? |
| Repository | full-name, visibility, default-branch, archived?, branch protection rules, topics |
| Team | slug, name, description, permission, parent team |
| TeamMembership | team + user + role (member/maintainer) |
| OrganizationMember | user login + role (member/owner) |

### Cross-Provider References

Connectors may emit reference attributes pointing to entities from other providers. These are stored as `:db.type/ref` when the referenced entity is already in Datomic, or as a string `:heron/id` lookup ref otherwise.

Example: Lambda function's execution role links to an IAM Role entity:
```clojure
{:heron/id           "aws:123456789012:lambda:function:my-fn"
 :aws.lambda/name    "my-fn"
 :aws.lambda/role    [:heron/id "aws:123456789012:iam:role:my-lambda-role"]}
```

---

## 8. Query / Datalog Interface

### Example: Single-Provider Query

Find all S3 buckets where public access blocking is not enabled:

```clojure
[:find ?name ?region
 :where
 [?e :aws.s3.bucket/name ?name]
 [?e :aws.s3.bucket/region ?region]
 [?e :aws.s3.bucket/public-access-blocked false]]
```

### Example: Cross-Provider Query

Find GitHub repositories whose name matches an AWS Lambda function name (useful for tracing ownership):

```clojure
[:find ?repo-name ?fn-name
 :where
 [?r :github.repo/name ?repo-name]
 [?f :aws.lambda/name ?fn-name]
 [(= ?repo-name ?fn-name)]]
```

### Example: As-of Query

Find the state of a specific bucket 30 days ago:

```clojure
;; Using datomic.api/as-of to scope the database
(d/q '[:find (pull ?e [*])
       :where
       [?e :heron/id "aws:123456789012:s3:bucket:my-bucket"]]
     (d/as-of db thirty-days-ago))
```

### Query API Spec

**Endpoint:** `POST /api/v1/query`

**Request body (EDN or JSON):**
```clojure
{:query  "[:find ?name :where [?e :aws.s3.bucket/name ?name]]"
 :args   []           ;; optional positional args to the query
 :as-of  nil          ;; optional: ISO-8601 timestamp or Datomic t
 :format :edn}        ;; :edn (default) | :json
```

**Response:**
```clojure
{:status  :ok
 :results [["my-bucket-1"] ["my-bucket-2"]]
 :count   2
 :elapsed-ms 14}
```

**Error response:**
```clojure
{:status  :error
 :message "Parse error at position 12: unexpected token"
 :type    :query-parse-error}
```

### CLI Interface

```bash
# Inline query
heron query '[:find ?name :where [?e :aws.s3.bucket/name ?name]]'

# Query from file
heron query --file checks/s3-public-buckets.edn

# As-of query
heron query --as-of 2025-01-01T00:00:00Z '[:find ?name ...]'

# Output format
heron query --format json '[:find ?name ...]'

# List checks
heron checks list

# Run a check manually
heron checks run s3-public-access-blocked

# Show check history
heron checks history s3-public-access-blocked --limit 10

# List reports
heron reports list

# Show latest report run
heron reports show untagged-ec2-instances
```

### Schema Discovery API

**Endpoint:** `GET /api/v1/schema`

Returns all registered Datomic attributes grouped by namespace:

```clojure
{:namespaces
 {"aws.s3.bucket"
  [{:ident :aws.s3.bucket/name
    :value-type :db.type/string
    :cardinality :db.cardinality/one
    :doc "The globally unique name of the S3 bucket."}
   ...]
  "aws.ec2.instance"
  [...]}}
```

### Query Writing Guidelines

For Check and Report authors:

1. **Queries naturally return live entities:** Because absent resources are natively retracted, no filter clause is needed. To query historical state, use `datomic.api/as-of`.
2. **Use pull syntax for Reports:** `(pull ?e [:aws.s3.bucket/name :aws.s3.bucket/region])` returns maps instead of tuples, making results self-documenting.
3. **Check queries must return entity-ids:** The first `?find` variable in a Check query must be the entity-id `?e` — this is how Heron tracks which specific resources are violating.
4. **Use Datomic rules for reusable predicates:** Common filters (e.g., "is production-tagged") can be expressed as named Datalog rules and reused across queries.
5. **Parameterize where possible:** Queries can accept arguments (`?account-id`, `?environment`) to make checks reusable across environments.

---

## 9. Checks and Reports Specification

### Check Definition Schema

```clojure
{;; Required
 :check/id          :s3/public-access-blocked       ;; namespaced keyword, unique
 :check/name        "S3 Buckets Block Public Access"
 :check/description "All S3 buckets must have the public access block fully enabled."
 :check/query       "[:find ?e :where
                       [?e :aws.s3.bucket/public-access-blocked false]]"

 ;; Optional
 :check/severity    :high                           ;; post-MVP
 :check/enabled     true
 :check/tags        ["s3" "security" "cis-benchmark"]}
```

### Check Evaluation Record Schema

```clojure
{:check-eval/id           "uuid"
 :check-eval/check-id     :s3/public-access-blocked
 :check-eval/run-at       #inst "2025-03-14T12:00:00"
 :check-eval/passed?      false
 :check-eval/violation-count 3
 :check-eval/violating-ids   ["aws:...:bucket:foo"
                               "aws:...:bucket:bar"
                               "aws:...:bucket:baz"]}
```

### Check Transition Schema

```clojure
{:check-transition/id         "uuid"
 :check-transition/check-id   :s3/public-access-blocked
 :check-transition/entity-id  "aws:...:bucket:foo"
 :check-transition/type       :opened   ;; | :resolved
 :check-transition/at         #inst "2025-03-14T12:00:00"}
```

### Built-in Checks (MVP)

| ID | Name | Description |
|----|------|-------------|
| `:s3/public-access-blocked` | S3 Buckets Block Public Access | All S3 buckets must have the S3 Block Public Access setting fully enabled. |
| `:iam/no-root-access-keys` | No IAM Root Access Keys | The AWS account root user must not have active access keys. |
| `:ec2/no-public-ssh` | EC2 No Public SSH | No EC2 security group should permit inbound SSH (port 22) from 0.0.0.0/0. |
| `:rds/no-public-rds` | RDS Not Publicly Accessible | No RDS instance should have `publicly-accessible` set to true. |
| `:github/require-pr-reviews` | GitHub Repos Require PR Reviews | All non-archived GitHub repositories must have branch protection requiring at least one pull request review on the default branch. |

### Report Definition Schema

```clojure
{:report/id          :aws/untagged-ec2-instances
 :report/name        "Untagged EC2 Instances"
 :report/description "EC2 instances missing a required 'cost-center' tag."
 :report/query       "[:find (pull ?e [:heron/id :aws.ec2.instance/id
                                       :aws.ec2.instance/type
                                       :aws.ec2.instance/region])
                        :where
                        [?e :aws.ec2.instance/id _]
                        (not [?e :aws.ec2.instance/tag-cost-center _])]"
 :report/enabled     true}
```

### Change Detection Algorithm

After each Report evaluation:
1. Load previous run's result set (keyed on `:heron/id`).
2. Load current run's result set.
3. **Additions** = items in current not in previous.
4. **Removals** = items in previous not in current.
5. Record additions and removals as line items on the Report Run entity.

### Built-in Reports (MVP)

| ID | Name | Description |
|----|------|-------------|
| `:aws/untagged-ec2-instances` | Untagged EC2 Instances | EC2 instances missing required cost-center or environment tags. |
| `:aws/expiring-acm-certificates` | Expiring ACM Certificates | TLS certificates expiring within 30 days. |
| `:github/public-repositories` | Public GitHub Repositories | All public repositories in the organization. |

---

## 10. Strong Checks Specification (Post-MVP)

### Evaluation Algorithm

Strong Checks use `datomic.api/with` to apply a hypothetical set of transactions to the current database without persisting them. The resulting "speculative" database is passed to the normal check evaluation engine.

```
1. Receive Terraform JSON plan + list of check-ids
2. Parse Terraform plan:
   - For each resource in planned_values:
     * Map Terraform resource type → Heron entity map format
     * Assign :heron/id using Terraform address as seed
   - For each resource being destroyed:
     * Emit [:db/retractEntity eid] (looked up by :heron/id)
3. Transact entity maps into speculative db using datomic.api/with
4. Evaluate requested Checks against speculative db value
5. Return per-check results
```

### API Spec

**Endpoint:** `POST /api/v1/strong-check`

**Request:**
```clojure
{:plan       {...}                              ;; Terraform JSON plan (parsed)
 :check-ids  [:s3/public-access-blocked
              :ec2/no-public-ssh]
 :format     :edn}
```

**Response:**
```clojure
{:results
 [{:check-id :s3/public-access-blocked
   :passed?  false
   :violations [{:heron/id "aws:...:s3:bucket:new-bucket"
                 :reason   "public-access-blocked is false"}]}
  {:check-id :ec2/no-public-ssh
   :passed?  true}]}
```

### GitHub Action Integration

```yaml
- name: Heron Strong Check
  uses: heron-infra/strong-check-action@v1
  with:
    heron-url: ${{ secrets.HERON_URL }}
    plan-file: tfplan.json
    checks: "s3/public-access-blocked,ec2/no-public-ssh"
```

The action fails the workflow if any Strong Check produces violations.

### Limitations

- Strong Checks can only reason about resource types with a defined Terraform-to-Heron mapping.
- Cross-provider effects (e.g., a new Lambda role referencing a GitHub secret) are not modeled in Phase 4.
- `datomic.api/with` operates in-memory; very large plans may be slow.

---

## 11. Success Metrics

### Connector Health

| Metric | Target |
|--------|--------|
| Sync success rate | ≥99% of scheduled runs complete without error |
| Data freshness (polling) | Connector run completes within 15 minutes of schedule |
| Data freshness (real-time, post-MVP) | <60 seconds from cloud event to queryable fact |
| Entity count accuracy | Connector entity count within 1% of provider API count |

### Query Performance

| Metric | Target |
|--------|--------|
| Simple attribute lookup (p99) | <100ms |
| Cross-provider join query (p99) | <2s |
| Full check evaluation across all checks | <60s after sync completes |
| Strong Check evaluation (post-MVP) | <30s per request |

### User Adoption

| Metric | Target |
|--------|--------|
| Time to first successful query | <30 minutes from `docker compose up` |
| Time to first custom Check | <1 hour for a Datalog-familiar user |
| Built-in check coverage | ≥80% of CIS AWS Benchmark Level 1 controls |

### Reliability

| Metric | Target |
|--------|--------|
| Uptime | ≥99.5% during business hours |
| Data loss | Zero: no transacted fact is ever lost |
| Connector run failures surface | Within 5 minutes via health API |

---

## 12. Phased Roadmap

No time estimates are provided. This is a solo project; phases are sequenced by dependency and risk.

### Phase 0: Foundation

**Goal:** Prove the core data model works. Queryable data in Datomic.

- [ ] Datomic schema: `heron/id`, `heron/provider`, `heron/label`
- [ ] `IConnector` protocol defined
- [ ] AWS S3 connector (buckets only)
- [ ] AWS IAM connector (roles only)
- [ ] Sync engine: upsert + retract
- [ ] ConnectorRun metadata
- [ ] REPL-based Datalog queries working
- [ ] `docker-compose.yml` for Datomic transactor + PostgreSQL

### Phase 1: MVP Core

**Goal:** All MVP features working. Ready for internal use.

- [ ] Full AWS connector (EC2, RDS, Lambda, Route53, ACM)
- [ ] Full GitHub connector (orgs, repos, teams, members)
- [ ] Check engine + built-in checks
- [ ] Report engine + built-in reports
- [ ] Ring/Reitit HTTP API
- [ ] `POST /api/v1/query`
- [ ] `GET /api/v1/schema`
- [ ] `GET /api/v1/checks` and `GET /api/v1/reports`
- [ ] CLI: `heron query`, `heron checks`, `heron reports`
- [ ] Chime-based scheduler for connector runs
- [ ] Full `docker-compose.yml` for production-like stack

### Phase 2: Operationalization

**Goal:** Reliable enough for daily use; observable; shareable.

- [ ] Multi-account AWS support via IAM role assumption
- [ ] `GET /api/v1/health` API with per-connector status
- [ ] Schema discovery API
- [ ] Custom check/report loading from EDN files
- [ ] Check transition history queryable via API
- [ ] Helm chart for Kubernetes deployment
- [ ] Beta release to first external users

### Phase 3: Real-Time Connectors

**Goal:** Reduce data latency from ~15 minutes to <60 seconds.

- [ ] AWS EventBridge integration for real-time S3, EC2, IAM events
- [ ] GitHub webhook receiver for repo/team events
- [ ] <60s latency SLO for supported resource types
- [ ] Polling remains as fallback/reconciliation

### Phase 4: Strong Checks

**Goal:** Pre-flight compliance enforcement in CI/CD.

- [ ] `datomic.api/with` projection API (internal)
- [ ] Terraform JSON plan parser → Heron entity maps
- [ ] `POST /api/v1/strong-check` endpoint
- [ ] GitHub Action: `heron-infra/strong-check-action`
- [ ] Documentation for writing Strong Check-compatible checks

### Phase 5: Platform Expansion

**Goal:** Extensible platform for other teams and providers.

- [ ] Connector SDK (documented protocol + example connector)
- [ ] GCP connector (Cloud Storage, Compute, IAM)
- [ ] Azure connector (Blob Storage, VMs, IAM)
- [ ] Browser UI: query editor, check dashboard, report viewer
- [ ] Multi-tenancy: namespace isolation per team/environment
- [ ] RBAC: query-level and check-level access control

---

## 13. Key Architectural Decisions

### ADR-1: Datomic as Sole Datastore

**Decision:** Use Datomic (on-prem) as the only datastore for all Heron data including entity state, check evaluations, report runs, and connector metadata.

**Rationale:** Heron's core value proposition is historical queryability. Datomic provides this natively: every fact carries a transaction timestamp, and `as-of` queries require no additional infrastructure. Alternative approaches (PostgreSQL with audit tables, event sourcing onto Kafka) would require building what Datomic provides out of the box. The Datalog query language is the right abstraction for infrastructure queries.

**Consequences:** Operators must run a Datomic transactor. Datomic licensing applies. Query language is Datalog, not SQL — acceptable given the target user (platform engineers).

---

### ADR-2: Native Datomic Retractions for Absent Resources

**Decision:** When a resource is absent from a connector run, retract its entity using `[:db/retractEntity eid]` (or targeted `:db/retract` datoms for individual attributes) rather than asserting a soft-delete timestamp.

**Rationale:** Datomic retractions are themselves immutable datoms recorded in the transaction log. An `as-of` query scoped to any point before the retraction will return the entity exactly as it existed — full audit history is preserved. The earlier soft-delete approach (`:heron/retracted-at`) was based on a misconception that retractions destroy history; they do not. Native retractions are strictly simpler: no extra schema attribute, no boilerplate filter clause in every query, and no risk of authors forgetting the filter.

**Consequences:** Queries naturally return only live entities — no filter required. Historical state is accessed via `datomic.api/as-of`. The `:heron/retracted-at` attribute is not part of the schema.

---

### ADR-3: Stable `:heron/id` for Idempotency

**Decision:** Use `:heron/id` as a Datomic `:db.unique/identity` attribute, enabling upsert semantics: transacting an entity map with a known `:heron/id` updates the existing entity rather than creating a new one.

**Rationale:** Connector runs are inherently idempotent — the same resource fetched twice should not create two entities. Datomic's lookup refs and `:db.unique/identity` provide this without any application-level deduplication logic.

**Consequences:** Connector implementors must construct stable `:heron/id` values. The format convention (§7) must be followed. An unstable `:heron/id` would cause entity proliferation.

---

### ADR-4: Polling as MVP Sync Model

**Decision:** All connectors in MVP use scheduled polling (periodic full fetch) rather than event-driven ingestion.

**Rationale:** Event-driven ingestion requires per-provider event infrastructure (EventBridge rules, webhook receivers, dead-letter queues) and introduces partial-update semantics. Polling is simpler to implement correctly, easier to reason about, and a full fetch guarantees consistency with the provider's current state. The tradeoff is latency (~15 minute freshness window), which is acceptable for compliance use cases in MVP.

**Consequences:** Data freshness SLO is ~15 minutes. Real-time use cases (e.g., incident response) are not served by MVP. Phase 3 adds event-driven connectors as an overlay on polling.

---

### ADR-5: `datomic.api/with` for Strong Checks

**Decision:** Strong Checks use Datomic's `db/with` API to apply proposed changes speculatively, producing an in-memory database value that is never persisted.

**Rationale:** `db/with` is purpose-built for this use case: it applies a transaction to a database value and returns the resulting database without committing anything. This means Strong Check evaluation is a pure function of (current-db, proposed-changes) with no risk of accidentally persisting Terraform plan data.

**Consequences:** Strong Check performance is bounded by JVM heap — very large plans may require tuning. The in-memory database is a full Datomic `db` value, so all standard Datalog queries work without modification. Terraform resource types must have defined mappings to Heron entity attributes.
