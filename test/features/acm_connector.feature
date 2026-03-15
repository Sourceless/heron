Feature: ACM connector schema and entity format

  Background:
    Given the dev stack is running

  Scenario: ACM schema is installed and a synthetic certificate is queryable
    Given a synthetic ACM certificate for "example.com" is present in Datomic
    Then the ACM certificate for "example.com" exists in Datomic
    And the ACM certificate for "example.com" has provider :aws and label "example.com"
    And the ACM certificate for "example.com" has heron id "aws:000000000000:acm:certificate:arn:aws:acm:us-east-1:000000000000:certificate:example-com-id"

  Scenario: Deleted ACM certificate is retracted from Datomic
    Given a synthetic ACM certificate for "gone.example.com" is present in Datomic
    When I retract the ACM certificate for "gone.example.com" from Datomic
    Then the ACM certificate for "gone.example.com" does not exist in the current Datomic db
