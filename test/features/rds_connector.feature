Feature: RDS connector schema and entity format

  Background:
    Given the dev stack is running

  Scenario: RDS schema is installed and a synthetic DB instance is queryable
    Given a synthetic RDS instance "prod-mysql" is present in Datomic
    Then the RDS instance "prod-mysql" exists in Datomic
    And the RDS instance "prod-mysql" has provider :aws and label "prod-mysql"
    And the RDS instance "prod-mysql" has heron id "aws:000000000000:rds:db-instance:prod-mysql"

  Scenario: Deleted RDS instance is retracted from Datomic
    Given a synthetic RDS instance "ghost-db" is present in Datomic
    When I retract the RDS instance "ghost-db" from Datomic
    Then the RDS instance "ghost-db" does not exist in the current Datomic db
