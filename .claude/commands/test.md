---
description: Run the Heron Cucumber test suite against the live dev stack
argument-hint: [optional: kaocha focus filter e.g. --focus dev-stack]
allowed-tools: Agent
---

Launch a general-purpose subagent to run the Heron test suite.

User arguments: $ARGUMENTS

The subagent should:
1. Verify the dev stack is up by running `docker compose ps` in `/home/laurence/src/heron` — if any required service (postgres, datomic, localstack) is not running or healthy, report the problem and suggest running `/up` first; do not attempt to run tests against a stopped stack
2. Run the Cucumber tests inside the Nix dev shell: `nix develop --command clojure -M:test $ARGUMENTS` from the project root `/home/laurence/src/heron`
3. Stream / capture the full test output
4. Report a clear pass/fail summary: total scenarios, passed, failed, pending
5. On failure, show the failing scenario names and their error messages

The `.env` file in the project root must exist (copy of `.env.example`). If it is absent, report that too.
