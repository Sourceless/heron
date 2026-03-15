Feature: EC2 connector ingests instances, security groups, and VPCs into Datomic

  Background:
    Given the dev stack is running

  # Instances

  Scenario: heron-test-instance is ingested into Datomic
    When I run the EC2 instances connector against LocalStack
    And I ingest the EC2 instance results into Datomic
    Then the EC2 instance labeled "heron-test-instance" exists in Datomic

  Scenario: Running the EC2 instances connector twice does not duplicate the instance
    When I run the EC2 instances connector against LocalStack
    And I ingest the EC2 instance results into Datomic
    And I run the EC2 instances connector against LocalStack
    And I ingest the EC2 instance results into Datomic
    Then there is exactly 1 EC2 instance labeled "heron-test-instance"

  Scenario: Ingested EC2 instance has provider and label set
    When I run the EC2 instances connector against LocalStack
    And I ingest the EC2 instance results into Datomic
    Then the EC2 instance "heron-test-instance" has provider :aws

  Scenario: Deleted EC2 instance is retracted from Datomic
    Given a phantom EC2 instance "i-phantom001" is present in Datomic
    When I run the EC2 instances connector against LocalStack
    And I ingest the EC2 instance results into Datomic
    And I retract absent EC2 instance entities from Datomic
    Then the EC2 instance "i-phantom001" does not exist in the current Datomic db

  Scenario: Retracted EC2 instance is still visible as-of before the retraction
    Given a phantom EC2 instance "i-phantom001" is present in Datomic
    When I run the EC2 instances connector against LocalStack
    And I ingest the EC2 instance results into Datomic
    And I retract absent EC2 instance entities from Datomic
    Then the EC2 instance "i-phantom001" is visible in Datomic as-of before the retraction

  # Security Groups

  Scenario: heron-test-sg is ingested into Datomic
    When I run the EC2 security groups connector against LocalStack
    And I ingest the EC2 security group results into Datomic
    Then the security group "heron-test-sg" exists in Datomic

  Scenario: heron-test-sg has ingress rules stored as component entities
    When I run the EC2 security groups connector against LocalStack
    And I ingest the EC2 security group results into Datomic
    Then the security group "heron-test-sg" has an ingress rule for tcp port 22 from "0.0.0.0/0"

  Scenario: Running the EC2 security groups connector twice does not duplicate the group
    When I run the EC2 security groups connector against LocalStack
    And I ingest the EC2 security group results into Datomic
    And I run the EC2 security groups connector against LocalStack
    And I ingest the EC2 security group results into Datomic
    Then there is exactly 1 security group named "heron-test-sg"

  Scenario: Deleted security group is retracted from Datomic
    Given a phantom security group "sg-phantom" is present in Datomic
    When I run the EC2 security groups connector against LocalStack
    And I ingest the EC2 security group results into Datomic
    And I retract absent EC2 security group entities from Datomic
    Then the security group "sg-phantom" does not exist in the current Datomic db

  # VPCs

  Scenario: Default VPC is ingested into Datomic
    When I run the EC2 VPCs connector against LocalStack
    And I ingest the EC2 VPC results into Datomic
    Then at least one VPC exists in Datomic

  Scenario: Running the EC2 VPCs connector twice does not duplicate VPCs
    When I run the EC2 VPCs connector against LocalStack
    And I ingest the EC2 VPC results into Datomic
    And I run the EC2 VPCs connector against LocalStack
    And I ingest the EC2 VPC results into Datomic
    Then the VPC count in Datomic matches the connector result count
