Feature: Lambda connector schema and entity format

  Background:
    Given the dev stack is running

  Scenario: Lambda schema is installed and a synthetic function is queryable
    Given a synthetic Lambda function "my-handler" is present in Datomic
    Then the Lambda function "my-handler" exists in Datomic
    And the Lambda function "my-handler" has provider :aws and label "my-handler"
    And the Lambda function "my-handler" has heron id "aws:000000000000:lambda:function:my-handler"

  Scenario: Deleted Lambda function is retracted from Datomic
    Given a synthetic Lambda function "ghost-fn" is present in Datomic
    When I retract the Lambda function "ghost-fn" from Datomic
    Then the Lambda function "ghost-fn" does not exist in the current Datomic db
