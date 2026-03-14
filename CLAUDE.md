# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Heron is a unified, immutable infrastructure database built in Clojure. It continuously ingests infrastructure state from cloud providers (AWS, GitHub), stores every fact with a timestamp in Datomic, and exposes the corpus via Datalog queries. Compliance goals are expressed as queries (Checks), not scripts.

**Current status:** PRD complete, implementation not yet started. Phase 0 (Foundation) is next.

## Tech Stack

- **Clojure** — primary language
- **Datomic** (on-prem, PostgreSQL storage backend) — immutable, append-only database
- **PostgreSQL** — Datomic storage
- **Ring + Reitit** — HTTP server and routing
- **Chime** — connector scheduling
- **Cognitect aws-api** — data-oriented AWS SDK
- **tools.build / deps.edn** — build system

## Dev Environment

Toolchain is pinned via Nix. `flake.nix` provides `clojure`, `jdk21`, and `clj-kondo`.

```bash
nix develop          # Enter dev shell (one-off)
direnv allow         # Auto-activate shell on cd (requires direnv + nix-direnv)
```

`.envrc` runs `use flake` and loads `.env` (DB credentials etc.) automatically when direnv is active.

## Build & Dev Commands

*(To be added as implementation begins. Expected commands:)*

```bash
clojure -T:build uberjar     # Build deployable JAR
clojure -M:test              # Run tests
docker compose up            # Start Datomic transactor + PostgreSQL
```

## Architecture

### Core Data Flow

```
Scheduler (chime) → Connector.run() → entity maps
  → Sync Engine (upsert + retract absent entities)
  → Datomic transact
  → Check Engine (Datalog compliance queries)
  → Report Engine (list queries with change detection)
```

### Key Abstractions

| Concept | Description |
|---------|-------------|
| `IConnector` | Protocol all connectors implement; returns a sequence of entity maps |
| Entity map | Map with `:heron/id` (stable string) + namespaced attributes (e.g. `:aws.s3.bucket/name`) |
| ConnectorRun | Metadata entity recording each execution: provider, start/end time, resource counts |
| Check | Named Datalog query returning violating entities; zero results = passing |
| CheckViolation | Entity + timestamp when check failed; transitions: `:opened` / `:resolved` |
| Report | Named list query; additions/removals tracked between runs |

### Entity ID Format

```
<provider>:<account>:<service>:<resource-type>:<resource-id>
# e.g. aws:123456789012:s3:bucket:my-bucket
```

### Key Architectural Decisions

- **ADR-1**: Datomic as sole datastore — full time-travel via as-of queries is a first-class feature
- **ADR-2**: Native Datomic retractions — use `[:db/retractEntity eid]` or targeted `:db/retract` datoms for absent resources; Datomic records retractions in the immutable log, so `as-of` queries before the retraction still show the entity
- **ADR-3**: Stable `:heron/id` for idempotency — upsert semantics; running same connector twice must not create new transactions
- **ADR-4**: Polling as MVP sync model — full fetches per run for consistency; events are post-MVP
- **ADR-5**: `datomic.api/with` for Strong Checks — speculative DB evaluation of Terraform plans without committing

### Planned Source Structure

```
src/heron/
  schema.clj        # Datomic attribute definitions
  connector.clj     # IConnector protocol
  sync.clj          # Sync engine (upsert + retract)
  check.clj         # Check evaluation engine
  report.clj        # Report evaluation engine
  api.clj           # REST API (Ring + Reitit)
  connectors/
    aws/            # Per-service AWS connectors (s3, ec2, iam, rds, lambda, route53, acm)
    github/         # GitHub connector (orgs, repos, teams, members)
```

## Deployment

Single deployable unit: Heron uberjar + Datomic transactor + PostgreSQL, all wired via Docker Compose. Configuration via environment variables (AWS credentials, GitHub tokens, Datomic URI, connector schedules).
