---
description: Stop the Heron dev stack
argument-hint: [--volumes | -v  to also delete persistent volumes]
allowed-tools: Agent
---

Launch a general-purpose subagent to tear down the Heron dev stack.

User arguments: $ARGUMENTS

The subagent should:
1. Check whether `--volumes` or `-v` was passed in the user arguments
2. If volumes flag present: run `docker compose down -v` in `/home/laurence/src/heron` and note that persistent data (postgres_data, localstack_data) has been deleted
3. Otherwise: run `docker compose down` (containers and networks only; volumes preserved)
4. Confirm the outcome — list any containers or volumes that were removed

Report clearly what was removed and what was retained.
