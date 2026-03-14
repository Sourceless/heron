Feature: LocalStack seed workload contains intentional misconfigurations

  Background:
    Given the dev stack is running

  Scenario: S3 bucket heron-test-data exists with public access block disabled
    When I get the public access block for bucket "heron-test-data"
    Then all public access block settings are false

  Scenario: IAM role heron-ec2-role has an inline wildcard policy
    When I get the inline policy "heron-wildcard-access" for role "heron-ec2-role"
    Then the policy allows all actions on all resources

  Scenario: Security group heron-test-sg has SSH ingress from 0.0.0.0/0
    When I describe the security group "heron-test-sg"
    Then it has a TCP ingress rule on port 22 from "0.0.0.0/0"

  Scenario: Security group heron-test-sg has RDP ingress from 0.0.0.0/0
    When I describe the security group "heron-test-sg"
    Then it has a TCP ingress rule on port 3389 from "0.0.0.0/0"

  Scenario: Security group heron-test-sg has all-traffic ingress from 0.0.0.0/0
    When I describe the security group "heron-test-sg"
    Then it has an all-traffic ingress rule from "0.0.0.0/0"

  Scenario: EC2 instance heron-test-instance exists with IMDSv2 not enforced
    When I describe the EC2 instance tagged "heron-test-instance"
    Then the instance metadata HttpTokens is not "required"
