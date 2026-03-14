#!/usr/bin/env bash
# LocalStack init hook — runs inside the container on every start.
# awslocal is pre-installed in localstack/localstack:3.
# Must be idempotent: every create is guarded by an existence check.
set -euo pipefail

REGION="us-east-1"
ACCOUNT="000000000000"

echo "[seed] Starting LocalStack seed..."

# ─── S3 ──────────────────────────────────────────────────────────────────────

BUCKET="heron-test-data"

if ! awslocal s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "[seed] Creating S3 bucket: $BUCKET"
  awslocal s3api create-bucket --bucket "$BUCKET" --region "$REGION"
else
  echo "[seed] S3 bucket $BUCKET already exists, skipping"
fi

# Disable all public access block settings (misconfiguration: bucket is wide open)
awslocal s3api put-public-access-block \
  --bucket "$BUCKET" \
  --public-access-block-configuration \
    "BlockPublicAcls=false,IgnorePublicAcls=false,BlockPublicPolicy=false,RestrictPublicBuckets=false"

# Set ACL to public-read (misconfiguration)
awslocal s3api put-bucket-acl --bucket "$BUCKET" --acl public-read

# Versioning intentionally not enabled (misconfiguration)
# Encryption intentionally not set (misconfiguration)
# Logging intentionally not set (misconfiguration)

# Seed objects (AWS CLI v2 requires a real file for --body, not a device node)
touch /tmp/seed-empty
awslocal s3api put-object \
  --bucket "$BUCKET" \
  --key "data/records.csv" \
  --body /tmp/seed-empty \
  --content-type "text/csv"

awslocal s3api put-object \
  --bucket "$BUCKET" \
  --key "logs/app.log" \
  --body /tmp/seed-empty \
  --content-type "text/plain"

echo "[seed] S3 done"

# ─── IAM ─────────────────────────────────────────────────────────────────────

ROLE_NAME="heron-ec2-role"
PROFILE_NAME="heron-ec2-profile"
USER_NAME="heron-svc-user"

if ! awslocal iam get-role --role-name "$ROLE_NAME" 2>/dev/null; then
  echo "[seed] Creating IAM role: $ROLE_NAME"
  awslocal iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document '{
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": {"Service": "ec2.amazonaws.com"},
        "Action": "sts:AssumeRole"
      }]
    }'
else
  echo "[seed] IAM role $ROLE_NAME already exists, skipping create"
fi

# Wildcard inline policy (misconfiguration: Action:* Resource:*)
awslocal iam put-role-policy \
  --role-name "$ROLE_NAME" \
  --policy-name "heron-wildcard-access" \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": "*",
      "Resource": "*"
    }]
  }'

if ! awslocal iam get-instance-profile --instance-profile-name "$PROFILE_NAME" 2>/dev/null; then
  echo "[seed] Creating instance profile: $PROFILE_NAME"
  awslocal iam create-instance-profile --instance-profile-name "$PROFILE_NAME"
  awslocal iam add-role-to-instance-profile \
    --instance-profile-name "$PROFILE_NAME" \
    --role-name "$ROLE_NAME"
else
  echo "[seed] Instance profile $PROFILE_NAME already exists, skipping create"
fi

if ! awslocal iam get-user --user-name "$USER_NAME" 2>/dev/null; then
  echo "[seed] Creating IAM user: $USER_NAME"
  awslocal iam create-user --user-name "$USER_NAME"
else
  echo "[seed] IAM user $USER_NAME already exists, skipping create"
fi

# Inline wildcard policy on user (misconfiguration: inline vs managed + wildcard)
awslocal iam put-user-policy \
  --user-name "$USER_NAME" \
  --policy-name "heron-wildcard-access" \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Action": "*",
      "Resource": "*"
    }]
  }'

# Create access key for user (programmatic access, misconfiguration)
EXISTING_KEYS=$(awslocal iam list-access-keys --user-name "$USER_NAME" \
  --query 'AccessKeyMetadata[].AccessKeyId' --output text)
if [ -z "$EXISTING_KEYS" ]; then
  awslocal iam create-access-key --user-name "$USER_NAME" > /dev/null
  echo "[seed] Created access key for $USER_NAME"
else
  echo "[seed] Access key for $USER_NAME already exists, skipping"
fi

echo "[seed] IAM done"

# ─── Security Group ───────────────────────────────────────────────────────────

SG_NAME="heron-test-sg"

EXISTING_SG=$(awslocal ec2 describe-security-groups \
  --filters "Name=group-name,Values=$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' \
  --output text 2>/dev/null || true)

if [ "$EXISTING_SG" = "None" ] || [ -z "$EXISTING_SG" ]; then
  echo "[seed] Creating security group: $SG_NAME"
  SG_ID=$(awslocal ec2 create-security-group \
    --group-name "$SG_NAME" \
    --description "Heron test security group with intentional misconfigurations" \
    --query 'GroupId' --output text)

  # SSH from anywhere (misconfiguration)
  awslocal ec2 authorize-security-group-ingress \
    --group-id "$SG_ID" \
    --protocol tcp \
    --port 22 \
    --cidr 0.0.0.0/0

  # RDP from anywhere (misconfiguration)
  awslocal ec2 authorize-security-group-ingress \
    --group-id "$SG_ID" \
    --protocol tcp \
    --port 3389 \
    --cidr 0.0.0.0/0

  # All traffic from anywhere (misconfiguration)
  awslocal ec2 authorize-security-group-ingress \
    --group-id "$SG_ID" \
    --protocol -1 \
    --cidr 0.0.0.0/0

  echo "[seed] Security group $SG_NAME created with id $SG_ID"
else
  SG_ID="$EXISTING_SG"
  echo "[seed] Security group $SG_NAME already exists ($SG_ID), skipping"
fi

echo "[seed] Security group done"

# ─── EC2 Instance ────────────────────────────────────────────────────────────

INSTANCE_TAG="heron-test-instance"

EXISTING_INSTANCE=$(awslocal ec2 describe-instances \
  --filters "Name=tag:Name,Values=$INSTANCE_TAG" "Name=instance-state-name,Values=running,pending,stopped" \
  --query 'Reservations[0].Instances[0].InstanceId' \
  --output text 2>/dev/null || true)

if [ "$EXISTING_INSTANCE" = "None" ] || [ -z "$EXISTING_INSTANCE" ]; then
  echo "[seed] Launching EC2 instance: $INSTANCE_TAG"
  awslocal ec2 run-instances \
    --image-id "ami-00000000" \
    --instance-type "t3.micro" \
    --security-group-ids "$SG_ID" \
    --iam-instance-profile "Name=$PROFILE_NAME" \
    --metadata-options "HttpTokens=optional,HttpEndpoint=enabled" \
    --associate-public-ip-address \
    --count 1 \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_TAG}]" \
    > /dev/null
  echo "[seed] EC2 instance launched"
else
  echo "[seed] EC2 instance $INSTANCE_TAG already exists ($EXISTING_INSTANCE), skipping"
fi

echo "[seed] EC2 done"
echo "[seed] LocalStack seed complete."
