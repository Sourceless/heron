(ns heron.steps.s3-connector-steps
  (:require [lambdaisland.cucumber.dsl :refer [When Then]]
            [heron.connector          :refer [run]]
            [heron.sync               :as sync]
            [heron.connectors.aws.s3  :as s3]
            [datomic.api              :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn s3/schema)
    conn))

(def ^:private localstack-opts
  {:region            "us-east-1"
   :account-id        "000000000000"
   :endpoint-override {:protocol :http
                       :hostname (or (System/getenv "LOCALSTACK_HOST") "localhost")
                       :port     4566}})

(When "I run the S3 connector against LocalStack" [state]
  (assoc state :s3-entities (run (s3/make-connector localstack-opts))))

(When "I ingest the connector results into Datomic" [state]
  (let [conn (or (:datomic-conn state) (fresh-conn!))]
    (sync/ingest! conn (:s3-entities state))
    (assoc state :datomic-conn conn)))

(Then "the bucket {string} exists in Datomic" [state bucket-name]
  (let [db (d/db (:datomic-conn state))]
    (assert (d/q '[:find ?e . :in $ ?n :where [?e :aws.s3.bucket/name ?n]] db bucket-name)
            (str "Bucket '" bucket-name "' not found in Datomic")))
  state)

(Then "there is exactly 1 entity with heron id {string}" [state heron-id]
  (let [db      (d/db (:datomic-conn state))
        results (d/q '[:find [?e ...] :in $ ?id :where [?e :heron/id ?id]] db heron-id)]
    (assert (= 1 (count results))
            (str "Expected 1 entity with :heron/id '" heron-id "', found: " (count results))))
  state)
