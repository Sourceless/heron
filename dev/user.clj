(ns user
  (:require [datomic.api                    :as d]
            [heron.connector                :as connector :refer [run]]
            [heron.sync                     :as sync]
            [heron.connectors.aws.s3        :as s3]
            [heron.connectors.aws.iam       :as iam]
            [heron.connectors.aws.ec2       :as ec2]
            [heron.connectors.aws.rds       :as rds]
            [heron.connectors.aws.lambda    :as lambda]
            [heron.connectors.aws.route53   :as route53]
            [heron.connectors.aws.acm       :as acm]))

;; Dev DB URI — isolated from heron-test used by Cucumber suite
(def dev-uri
  "datomic:sql://heron-dev?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

;; State atom — defonce prevents re-eval from closing a live connection
(defonce ^:private state (atom {:conn nil}))

(defn conn [] (:conn @state))
(defn db   [] (d/db (conn)))

(defn start!
  "Connect to heron-dev and ensure all schemas. Safe to call repeatedly."
  []
  (when-not (conn)
    (d/create-database dev-uri)
    (let [c (d/connect dev-uri)]
      (sync/ensure-schema! c s3/schema)
      (sync/ensure-schema! c iam/schema)
      (sync/ensure-schema! c ec2/schema)
      (sync/ensure-schema! c rds/schema)
      (sync/ensure-schema! c lambda/schema)
      (sync/ensure-schema! c route53/schema)
      (sync/ensure-schema! c acm/schema)
      (sync/ensure-schema! c connector/connector-run-schema)
      (swap! state assoc :conn c)
      (println "Connected to" dev-uri)))
  (conn))

(defn reset!
  "Drop and recreate the dev database. Use for a clean slate."
  []
  (when (conn)
    (d/release (conn))
    (swap! state assoc :conn nil))
  (d/delete-database dev-uri)
  (println "Dev database deleted. Call (start!) to recreate.")
  nil)

;; LocalStack opts — matches docker-compose.yml and test step files
(def ^:private localstack-opts
  {:region            "us-east-1"
   :account-id        "000000000000"
   :endpoint-override {:protocol :http
                       :hostname "localhost"
                       :port     4566}})

(defn seed-s3! []
  (let [c        (start!)
        started  (java.util.Date.)
        entities (run (s3/make-connector localstack-opts))]
    (sync/ingest! c entities)
    (sync/record-run! c {:provider       :aws
                         :connector      "s3"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count entities)})
    (println "Ingested" (count entities) "S3 bucket(s).")
    entities))

(defn seed-iam! []
  (let [c             (start!)
        started       (java.util.Date.)
        role-entities (run (iam/make-roles-connector localstack-opts))
        user-entities (run (iam/make-users-connector localstack-opts))]
    (sync/ingest! c role-entities)
    (sync/ingest! c user-entities)
    (sync/record-run! c {:provider       :aws
                         :connector      "iam-roles"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count role-entities)})
    (sync/record-run! c {:provider       :aws
                         :connector      "iam-users"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count user-entities)})
    (println "Ingested" (count role-entities) "IAM role(s),"
             (count user-entities) "IAM user(s).")
    {:roles role-entities :users user-entities}))

(defn seed-ec2! []
  (let [c          (start!)
        started    (java.util.Date.)
        instances  (run (ec2/make-instances-connector localstack-opts))
        sgs        (run (ec2/make-security-groups-connector localstack-opts))
        vpcs       (run (ec2/make-vpcs-connector localstack-opts))]
    (sync/ingest! c instances)
    (sync/ingest! c sgs)
    (sync/ingest! c vpcs)
    (doseq [[connector-name entities] [["ec2-instances" instances]
                                       ["ec2-security-groups" sgs]
                                       ["ec2-vpcs" vpcs]]]
      (sync/record-run! c {:provider       :aws
                           :connector      connector-name
                           :started-at     started
                           :finished-at    (java.util.Date.)
                           :resource-count (count entities)}))
    (println "Ingested" (count instances) "EC2 instance(s),"
             (count sgs) "security group(s),"
             (count vpcs) "VPC(s).")
    {:instances instances :security-groups sgs :vpcs vpcs}))

(defn seed-rds! []
  (let [c        (start!)
        started  (java.util.Date.)
        entities (run (rds/make-connector localstack-opts))]
    (sync/ingest! c entities)
    (sync/record-run! c {:provider       :aws
                         :connector      "rds"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count entities)})
    (println "Ingested" (count entities) "RDS instance(s).")
    entities))

(defn seed-lambda! []
  (let [c        (start!)
        started  (java.util.Date.)
        entities (run (lambda/make-connector localstack-opts))]
    (sync/ingest! c entities)
    (sync/record-run! c {:provider       :aws
                         :connector      "lambda"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count entities)})
    (println "Ingested" (count entities) "Lambda function(s).")
    entities))

(defn seed-route53! []
  (let [c        (start!)
        started  (java.util.Date.)
        zones    (run (route53/make-zones-connector localstack-opts))
        records  (run (route53/make-record-sets-connector localstack-opts))]
    (sync/ingest! c zones)
    (sync/ingest! c records)
    (sync/record-run! c {:provider       :aws
                         :connector      "route53-zones"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count zones)})
    (sync/record-run! c {:provider       :aws
                         :connector      "route53-records"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count records)})
    (println "Ingested" (count zones) "hosted zone(s),"
             (count records) "record set(s).")
    {:zones zones :records records}))

(defn seed-acm! []
  (let [c        (start!)
        started  (java.util.Date.)
        entities (run (acm/make-connector localstack-opts))]
    (sync/ingest! c entities)
    (sync/record-run! c {:provider       :aws
                         :connector      "acm"
                         :started-at     started
                         :finished-at    (java.util.Date.)
                         :resource-count (count entities)})
    (println "Ingested" (count entities) "ACM certificate(s).")
    entities))

(defn seed-all! []
  (seed-s3!)
  (seed-iam!)
  (seed-ec2!)
  (seed-rds!)
  (seed-lambda!)
  (seed-route53!)
  (seed-acm!)
  :done)

;; ── Example queries (evaluate interactively after (seed-all!)) ────────────────
;;
;; All resources:
;;   (d/q '[:find ?id ?label
;;           :where [?e :heron/id ?id] [?e :heron/label ?label]] (db))
;;
;; S3 buckets:
;;   (d/q '[:find ?name :where [?e :aws.s3.bucket/name ?name]] (db))
;;
;; IAM roles:
;;   (d/q '[:find ?name :where [?e :aws.iam.role/name ?name]] (db))
;;
;; EC2 instances:
;;   (d/q '[:find ?id ?type :where [?e :aws.ec2.instance/id ?id]
;;                                  [?e :aws.ec2.instance/type ?type]] (db))
;;
;; Security groups open to SSH:
;;   (d/q '[:find ?name :where [?sg :aws.ec2.security-group/name ?name]
;;                              [?sg :aws.ec2.security-group/ingress-rules ?r]
;;                              [?r :aws.ec2.sg-rule/protocol "tcp"]
;;                              [?r :aws.ec2.sg-rule/from-port ?from]
;;                              [?r :aws.ec2.sg-rule/to-port ?to]
;;                              [(>= 22 ?from)] [(<= 22 ?to)]
;;                              [?r :aws.ec2.sg-rule/cidr "0.0.0.0/0"]] (db))
;;
;; ConnectorRun history:
;;   (d/q '[:find ?connector ?count ?started
;;           :where [?r :heron.connector-run/connector ?connector]
;;                  [?r :heron.connector-run/resource-count ?count]
;;                  [?r :heron.connector-run/started-at ?started]] (db))
