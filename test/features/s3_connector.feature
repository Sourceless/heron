Feature: S3 connector ingests buckets into Datomic

  Background:
    Given the dev stack is running

  Scenario: heron-test-data bucket is ingested into Datomic
    When I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    Then the bucket "heron-test-data" exists in Datomic

  Scenario: Running the S3 connector twice does not duplicate the bucket
    When I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    And I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    Then there is exactly 1 entity with heron id "aws:000000000000:s3:bucket:heron-test-data"

  Scenario: Ingested bucket has provider and label set
    When I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    Then the bucket "heron-test-data" has provider :aws and label "heron-test-data"

  Scenario: Deleted bucket is retracted from Datomic
    Given a phantom bucket "ghost-bucket" is present in Datomic
    When I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    And I retract absent S3 entities from Datomic
    Then the bucket "ghost-bucket" does not exist in the current Datomic db

  Scenario: Retracted bucket is still visible as-of before the retraction
    Given a phantom bucket "ghost-bucket" is present in Datomic
    When I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    And I retract absent S3 entities from Datomic
    Then the bucket "ghost-bucket" is visible in Datomic as-of before the retraction
