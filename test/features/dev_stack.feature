Feature: Dev stack services are healthy

  Background:
    Given the dev stack is running

  Scenario: PostgreSQL has the Datomic KV store table
    When I query PostgreSQL for existing tables
    Then the "datomic_kvs" table exists

  Scenario: Datomic transactor is accepting connections
    When I connect to the Datomic transactor on port 4334
    Then the connection succeeds

  Scenario: LocalStack is healthy and S3 is running
    When I call the LocalStack health endpoint
    Then the response status is 200
    And the "s3" service status is "running"
