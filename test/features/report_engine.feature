Feature: Report engine evaluates Datalog list queries against ingested resources

  Background:
    Given the dev stack is running

  Scenario: Report entities are loaded into Datomic via ReportLoader
    When I load the reports into Datomic
    Then the report "aws/untagged-ec2-instances" exists in Datomic

  Scenario: A report with matching data returns results
    Given an S3 bucket "report-test-bucket" is present in Datomic
    When I evaluate the "all-s3-buckets" report
    Then the report has 1 results
    And "report-test-bucket" appears in the report results

  Scenario: Change detection records additions and removals between runs
    Given an S3 bucket "bucket-alpha" is present in Datomic
    And an S3 bucket "bucket-beta" is present in Datomic
    When I evaluate the "all-s3-buckets" report
    And I record the report run
    And "bucket-alpha" is retracted from Datomic
    And an S3 bucket "bucket-gamma" is present in Datomic
    And I evaluate the "all-s3-buckets" report again
    Then "bucket-gamma" appears in the added items
    And "bucket-alpha" appears in the removed items
