(ns heron.steps.localstack-seed-steps
  (:require [lambdaisland.cucumber.dsl :refer [When Then]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; All LocalStack calls go through the AWS CLI so we don't need to hand-craft
;; signed HTTP requests. awscli2 is available in the Nix dev shell.

(def ^:private aws-env
  {"AWS_ACCESS_KEY_ID"     "test"
   "AWS_SECRET_ACCESS_KEY" "test"
   "AWS_DEFAULT_REGION"    "us-east-1"})

(defn- aws [& args]
  (let [result (apply sh "aws" "--endpoint-url" "http://localhost:4566"
                      "--output" "json"
                      (concat args [:env (merge {} (System/getenv) aws-env)]))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "AWS CLI error: " (:err result))
                      {:args args :result result})))
    (json/parse-string (:out result) true)))

;; ─── S3 ──────────────────────────────────────────────────────────────────────

(When "I get the public access block for bucket {string}" [state bucket]
  (let [result (aws "s3api" "get-public-access-block" "--bucket" bucket)]
    (assoc state :public-access-block result)))

(Then "all public access block settings are false" [state]
  (let [config (:PublicAccessBlockConfiguration (:public-access-block state))]
    (doseq [[k v] config]
      (assert (false? v)
              (str "Expected " (name k) " to be false, got: " v))))
  state)

;; ─── IAM ─────────────────────────────────────────────────────────────────────

(When "I get the inline policy {string} for role {string}" [state policy-name role-name]
  (let [result (aws "iam" "get-role-policy"
                    "--role-name" role-name
                    "--policy-name" policy-name)]
    (assoc state :role-policy result)))

(Then "the policy allows all actions on all resources" [state]
  (let [raw-doc (get-in state [:role-policy :PolicyDocument])
        ;; PolicyDocument may arrive as a pre-parsed map or a URL-encoded string
        doc     (if (map? raw-doc)
                  raw-doc
                  (-> raw-doc
                      java.net.URLDecoder/decode
                      (json/parse-string true)))
        stmts   (:Statement doc)]
    (assert (seq stmts) "Expected at least one policy statement")
    (let [wildcard? (fn [stmt]
                      (let [action   (:Action stmt)
                            resource (:Resource stmt)]
                        (and (= "*" (if (sequential? action) (first action) action))
                             (= "*" (if (sequential? resource) (first resource) resource)))))]
      (assert (some wildcard? stmts)
              (str "Expected a wildcard Action:*/Resource:* statement, got: " stmts))))
  state)

;; ─── Security Group ───────────────────────────────────────────────────────────

(When "I describe the security group {string}" [state sg-name]
  (let [result (aws "ec2" "describe-security-groups"
                    "--filters" (str "Name=group-name,Values=" sg-name))]
    (assoc state :security-group (first (:SecurityGroups result)))))

(defn- find-ingress-rule [perms pred]
  (some pred perms))

(defn- cidr-ranges [perm]
  (map :CidrIp (:IpRanges perm)))

(Then "it has a TCP ingress rule on port {int} from {string}" [state port cidr]
  (let [perms (:IpPermissions (:security-group state))]
    (assert (find-ingress-rule perms
              (fn [p]
                (and (= "tcp" (:IpProtocol p))
                     (= port (:FromPort p))
                     (some #{cidr} (cidr-ranges p)))))
            (str "No TCP rule for port " port " from " cidr " in: "
                 (map #(select-keys % [:IpProtocol :FromPort :ToPort :IpRanges]) perms))))
  state)

(Then "it has an all-traffic ingress rule from {string}" [state cidr]
  (let [perms (:IpPermissions (:security-group state))]
    (assert (find-ingress-rule perms
              (fn [p]
                (and (= "-1" (:IpProtocol p))
                     (some #{cidr} (cidr-ranges p)))))
            (str "No all-traffic rule from " cidr " in: "
                 (map #(select-keys % [:IpProtocol :IpRanges]) perms))))
  state)

;; ─── EC2 Instance ────────────────────────────────────────────────────────────

(When "I describe the EC2 instance tagged {string}" [state tag-value]
  (let [result (aws "ec2" "describe-instances"
                    "--filters"
                    (str "Name=tag:Name,Values=" tag-value)
                    "Name=instance-state-name,Values=running,pending,stopped")
        instance (get-in result [:Reservations 0 :Instances 0])]
    (assoc state :ec2-instance instance)))

(Then "the instance metadata HttpTokens is not {string}" [state not-expected]
  (let [tokens (get-in state [:ec2-instance :MetadataOptions :HttpTokens])]
    (assert (not= not-expected tokens)
            (str "Expected HttpTokens to not be '" not-expected "', but it was")))
  state)
