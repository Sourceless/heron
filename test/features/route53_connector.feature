Feature: Route53 connector schema and entity format

  Background:
    Given the dev stack is running

  Scenario: Route53 schema is installed and a synthetic hosted zone is queryable
    Given a synthetic Route53 hosted zone "example.com." with id "Z123456" is present in Datomic
    Then the hosted zone "Z123456" exists in Datomic
    And the hosted zone "Z123456" has provider :aws and label "example.com."
    And the hosted zone "Z123456" has heron id "aws:000000000000:route53:hosted-zone:Z123456"

  Scenario: Route53 schema supports record sets
    Given a synthetic Route53 record set "www.example.com." type "A" in zone "Z123456" is present in Datomic
    Then the record set "www.example.com." type "A" exists in Datomic

  Scenario: Deleted hosted zone is retracted from Datomic
    Given a synthetic Route53 hosted zone "gone.example.com." with id "Z999999" is present in Datomic
    When I retract the hosted zone "Z999999" from Datomic
    Then the hosted zone "Z999999" does not exist in the current Datomic db
