---
description: Start the Heron dev stack (postgres, datomic, localstack)
allowed-tools: Agent
---

Launch a general-purpose subagent to bring up the Heron dev stack.

The subagent should:
1. Run `docker compose up -d` in the project root (`/home/laurence/src/heron`)
2. Wait for all services to be healthy by running `docker compose ps` and checking the Status column — retry up to 12 times with 5-second pauses if any service is not yet healthy
3. Print a final summary table of service names and their health status
4. If any service fails to become healthy within the retry window, print its recent logs using `docker compose logs --tail=30 <service>` and report failure

Report the outcome clearly. Do not proceed past a failed healthcheck without reporting it.
