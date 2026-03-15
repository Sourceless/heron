(ns user
  (:require [datomic.api                 :as d]
            [heron.connector             :as connector :refer [run]]
            [heron.sync                  :as sync]
            [heron.connectors.aws.s3     :as s3]
            [heron.connectors.aws.iam    :as iam]))

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
  (let [c       (start!)
        started (java.util.Date.)
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

(defn seed-all! []
  (seed-s3!)
  (seed-iam!)
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
;; IAM users:
;;   (d/q '[:find ?name :where [?e :aws.iam.user/name ?name]] (db))
;;
;; ConnectorRun history:
;;   (d/q '[:find ?connector ?count ?started
;;           :where [?r :heron.connector-run/connector ?connector]
;;                  [?r :heron.connector-run/resource-count ?count]
;;                  [?r :heron.connector-run/started-at ?started]] (db))
;;
;; Time-travel (as-of before a transaction):
;;   (def t1 (d/basis-t (db)))
;;   ;; ... make changes ...
;;   (d/q '[:find ?id :where [?e :aws.s3.bucket/name _] [?e :heron/id ?id]]
;;         (d/as-of (db) t1))
;;
;; Compliance-style check (buckets without a label — should return empty):
;;   (d/q '[:find ?id :where [?e :aws.s3.bucket/name _]
;;                           [?e :heron/id ?id]
;;                           (not [?e :heron/label _])] (db))
