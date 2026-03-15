Feature: Connector runs are recorded as metadata in Datomic

  Background:
    Given the dev stack is running

  Scenario: Running a connector records a ConnectorRun entity
    When I run the S3 connector and record the run
    Then a ConnectorRun entity exists for connector "s3"

  Scenario: ConnectorRun captures provider and connector name
    When I run the S3 connector and record the run
    Then the ConnectorRun for "s3" has provider :aws

  Scenario: ConnectorRun captures resource count
    When I run the S3 connector and record the run
    Then the ConnectorRun for "s3" has a resource-count greater than 0

  Scenario: ConnectorRun has started-at before finished-at
    When I run the S3 connector and record the run
    Then the ConnectorRun for "s3" has started-at before finished-at

  Scenario: Running a connector twice creates two separate ConnectorRun entities
    When I run the S3 connector and record the run
    And I run the S3 connector and record the run
    Then there are 2 ConnectorRun entities for connector "s3"
