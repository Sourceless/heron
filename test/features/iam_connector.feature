Feature: IAM connector ingests roles and users into Datomic

  Background:
    Given the dev stack is running

  # Roles

  Scenario: heron-ec2-role is ingested into Datomic
    When I run the IAM roles connector against LocalStack
    And I ingest the IAM roles into Datomic
    Then the IAM role "heron-ec2-role" exists in Datomic

  Scenario: Running the IAM roles connector twice does not duplicate the role
    When I run the IAM roles connector against LocalStack
    And I ingest the IAM roles into Datomic
    And I run the IAM roles connector against LocalStack
    And I ingest the IAM roles into Datomic
    Then there is exactly 1 entity with heron id "aws:000000000000:iam:role:heron-ec2-role"

  Scenario: Ingested IAM role has provider and label set
    When I run the IAM roles connector against LocalStack
    And I ingest the IAM roles into Datomic
    Then the IAM role "heron-ec2-role" has provider :aws and label "heron-ec2-role"

  Scenario: Deleted IAM role is retracted from Datomic
    Given a phantom IAM role "ghost-role" is present in Datomic
    When I run the IAM roles connector against LocalStack
    And I ingest the IAM roles into Datomic
    And I retract absent IAM role entities from Datomic
    Then the IAM role "ghost-role" does not exist in the current Datomic db

  Scenario: Retracted IAM role is still visible as-of before the retraction
    Given a phantom IAM role "ghost-role" is present in Datomic
    When I run the IAM roles connector against LocalStack
    And I ingest the IAM roles into Datomic
    And I retract absent IAM role entities from Datomic
    Then the IAM role "ghost-role" is visible in Datomic as-of before the retraction

  # Users

  Scenario: heron-svc-user is ingested into Datomic
    When I run the IAM users connector against LocalStack
    And I ingest the IAM users into Datomic
    Then the IAM user "heron-svc-user" exists in Datomic

  Scenario: Running the IAM users connector twice does not duplicate the user
    When I run the IAM users connector against LocalStack
    And I ingest the IAM users into Datomic
    And I run the IAM users connector against LocalStack
    And I ingest the IAM users into Datomic
    Then there is exactly 1 entity with heron id "aws:000000000000:iam:user:heron-svc-user"

  Scenario: Ingested IAM user has provider and label set
    When I run the IAM users connector against LocalStack
    And I ingest the IAM users into Datomic
    Then the IAM user "heron-svc-user" has provider :aws and label "heron-svc-user"

  Scenario: Deleted IAM user is retracted from Datomic
    Given a phantom IAM user "ghost-user" is present in Datomic
    When I run the IAM users connector against LocalStack
    And I ingest the IAM users into Datomic
    And I retract absent IAM user entities from Datomic
    Then the IAM user "ghost-user" does not exist in the current Datomic db

  Scenario: Retracted IAM user is still visible as-of before the retraction
    Given a phantom IAM user "ghost-user" is present in Datomic
    When I run the IAM users connector against LocalStack
    And I ingest the IAM users into Datomic
    And I retract absent IAM user entities from Datomic
    Then the IAM user "ghost-user" is visible in Datomic as-of before the retraction
