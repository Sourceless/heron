Feature: Check engine evaluates Datalog compliance checks against ingested resources

  Background:
    Given the dev stack is running

  Scenario: Check entities are loaded into Datomic via CheckLoader
    When I load the checks into Datomic
    Then the check "aws.s3/public-access-block-enabled" exists in Datomic

  Scenario: heron-test-data fails the S3 public access block check
    When I run the S3 connector against LocalStack
    And I ingest the connector results into Datomic
    And I evaluate the "aws.s3/public-access-block-enabled" check
    Then "heron-test-data" appears in the violations

  Scenario: Compliant S3 bucket does not appear in violations
    Given a compliant S3 bucket "secure-bucket" is present in Datomic
    When I evaluate the "aws.s3/public-access-block-enabled" check
    Then "secure-bucket" does not appear in the violations
