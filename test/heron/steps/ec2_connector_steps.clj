(ns heron.steps.ec2-connector-steps
  (:require [lambdaisland.cucumber.dsl    :refer [Given When Then]]
            [heron.connector              :refer [run]]
            [heron.sync                   :as sync]
            [heron.connectors.aws.ec2     :as ec2]
            [datomic.api                  :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn ec2/schema)
    conn))

(def ^:private localstack-opts
  {:region            "us-east-1"
   :account-id        "000000000000"
   :endpoint-override {:protocol :http
                       :hostname (or (System/getenv "LOCALSTACK_HOST") "localhost")
                       :port     4566}})

;; ── Instances ─────────────────────────────────────────────────────────────────

(When "I run the EC2 instances connector against LocalStack" [state]
  (assoc state :ec2-instance-entities (run (ec2/make-instances-connector localstack-opts))))

(When "I ingest the EC2 instance results into Datomic" [state]
  (let [conn (or (:datomic-conn state) (fresh-conn!))]
    (sync/ingest! conn (:ec2-instance-entities state))
    (assoc state :datomic-conn conn)))

(Given "a phantom EC2 instance {string} is present in Datomic" [state instance-id]
  (let [conn    (or (:datomic-conn state) (fresh-conn!))
        phantom {:heron/id               (str "aws:000000000000:ec2:instance:" instance-id)
                 :heron/provider         :aws
                 :heron/label            instance-id
                 :aws.ec2.instance/id    instance-id
                 :aws.ec2.instance/type  "t2.micro"
                 :aws.ec2.instance/state "running"
                 :aws.ec2.instance/ami   "ami-00000000"
                 :aws.ec2.instance/vpc-id ""
                 :aws.ec2.instance/subnet-id ""}]
    @(d/transact conn [phantom])
    (assoc state :datomic-conn conn)))

(When "I retract absent EC2 instance entities from Datomic" [state]
  (let [conn        (:datomic-conn state)
        current-ids (map :heron/id (:ec2-instance-entities state))
        db-before   (d/db conn)]
    (sync/retract-absent! conn :aws.ec2.instance/id current-ids)
    (assoc state :db-before-retraction db-before)))

(Then "the EC2 instance labeled {string} exists in Datomic" [state label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?l :where [?e :heron/label ?l]
                                               [?e :aws.ec2.instance/id _]] db label)]
    (assert eid (str "EC2 instance with label '" label "' not found in Datomic")))
  state)

(Then "there is exactly 1 EC2 instance labeled {string}" [state label]
  (let [db    (d/db (:datomic-conn state))
        count (count (d/q '[:find ?e :in $ ?l :where [?e :heron/label ?l]
                                                      [?e :aws.ec2.instance/id _]] db label))]
    (assert (= 1 count) (str "Expected 1 EC2 instance labeled '" label "', got " count)))
  state)

(Then "the EC2 instance {string} has provider :aws" [state label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?l :where [?e :heron/label ?l]
                                               [?e :aws.ec2.instance/id _]] db label)]
    (assert eid (str "EC2 instance '" label "' not found"))
    (assert (= :aws (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid))
            "Expected :heron/provider :aws"))
  state)

(Then "the EC2 instance {string} does not exist in the current Datomic db" [state instance-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.ec2.instance/id ?id]] db instance-id)]
    (assert (nil? eid) (str "EC2 instance '" instance-id "' should have been retracted")))
  state)

(Then "the EC2 instance {string} is visible in Datomic as-of before the retraction" [state instance-id]
  (let [db-before (:db-before-retraction state)
        eid       (d/q '[:find ?e . :in $ ?id :where [?e :aws.ec2.instance/id ?id]] db-before instance-id)]
    (assert eid (str "EC2 instance '" instance-id "' should be visible as-of before retraction")))
  state)

;; ── Security Groups ───────────────────────────────────────────────────────────

(When "I run the EC2 security groups connector against LocalStack" [state]
  (assoc state :ec2-sg-entities (run (ec2/make-security-groups-connector localstack-opts))))

(When "I ingest the EC2 security group results into Datomic" [state]
  (let [conn (or (:datomic-conn state) (fresh-conn!))]
    (sync/ingest! conn (:ec2-sg-entities state))
    (assoc state :datomic-conn conn)))

(Given "a phantom security group {string} is present in Datomic" [state sg-id]
  (let [conn    (or (:datomic-conn state) (fresh-conn!))
        phantom {:heron/id                          (str "aws:000000000000:ec2:security-group:" sg-id)
                 :heron/provider                    :aws
                 :heron/label                       sg-id
                 :aws.ec2.security-group/group-id   sg-id
                 :aws.ec2.security-group/name        sg-id
                 :aws.ec2.security-group/vpc-id      ""}]
    @(d/transact conn [phantom])
    (assoc state :datomic-conn conn)))

(When "I retract absent EC2 security group entities from Datomic" [state]
  (let [conn        (:datomic-conn state)
        current-ids (map :heron/id (:ec2-sg-entities state))]
    (sync/retract-absent! conn :aws.ec2.security-group/group-id current-ids)
    state))

(Then "the security group {string} exists in Datomic" [state sg-name]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.ec2.security-group/name ?n]] db sg-name)]
    (assert eid (str "Security group '" sg-name "' not found in Datomic")))
  state)

(Then "the security group {string} has an ingress rule for tcp port {int} from {string}" [state sg-name port cidr]
  (let [db    (d/db (:datomic-conn state))
        rules (d/q '[:find ?from ?to ?proto ?cidr
                     :in $ ?sg-name
                     :where [?sg :aws.ec2.security-group/name ?sg-name]
                            [?sg :aws.ec2.security-group/ingress-rules ?rule]
                            [?rule :aws.ec2.sg-rule/from-port ?from]
                            [?rule :aws.ec2.sg-rule/to-port ?to]
                            [?rule :aws.ec2.sg-rule/protocol ?proto]
                            [?rule :aws.ec2.sg-rule/cidr ?cidr]]
                   db sg-name)
        match? (fn [[from to proto rule-cidr]]
                 (and (= "tcp" proto)
                      (<= from port)
                      (>= to port)
                      (= cidr rule-cidr)))]
    (assert (some match? rules)
            (str "No tcp/port-" port "/" cidr " ingress rule on '" sg-name "'. Rules: " rules)))
  state)

(Then "there is exactly 1 security group named {string}" [state sg-name]
  (let [db    (d/db (:datomic-conn state))
        count (count (d/q '[:find ?e :in $ ?n :where [?e :aws.ec2.security-group/name ?n]] db sg-name))]
    (assert (= 1 count) (str "Expected 1 security group named '" sg-name "', got " count)))
  state)

(Then "the security group {string} does not exist in the current Datomic db" [state sg-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.ec2.security-group/group-id ?id]] db sg-id)]
    (assert (nil? eid) (str "Security group '" sg-id "' should have been retracted")))
  state)

;; ── VPCs ─────────────────────────────────────────────────────────────────────

(When "I run the EC2 VPCs connector against LocalStack" [state]
  (assoc state :ec2-vpc-entities (run (ec2/make-vpcs-connector localstack-opts))))

(When "I ingest the EC2 VPC results into Datomic" [state]
  (let [conn (or (:datomic-conn state) (fresh-conn!))]
    (sync/ingest! conn (:ec2-vpc-entities state))
    (assoc state :datomic-conn conn)))

(Then "at least one VPC exists in Datomic" [state]
  (let [db    (d/db (:datomic-conn state))
        count (count (d/q '[:find ?e :where [?e :aws.ec2.vpc/vpc-id _]] db))]
    (assert (pos? count) "Expected at least one VPC in Datomic, found none"))
  state)

(Then "the VPC count in Datomic matches the connector result count" [state]
  (let [db        (d/db (:datomic-conn state))
        db-count  (count (d/q '[:find ?e :where [?e :aws.ec2.vpc/vpc-id _]] db))
        run-count (count (:ec2-vpc-entities state))]
    (assert (= db-count run-count)
            (str "Expected " run-count " VPC(s) in Datomic, got " db-count)))
  state)
