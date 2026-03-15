(ns heron.steps.check-engine-steps
  (:require [lambdaisland.cucumber.dsl  :refer [Given When Then]]
            [heron.check                :as check]
            [heron.connector            :refer [run]]
            [heron.sync                 :as sync]
            [heron.connectors.aws.s3    :as s3]
            [datomic.api                :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn s3/schema)
    (sync/ensure-schema! conn check/check-schema)
    conn))

(def ^:private checks-registry
  {"aws.s3/public-access-block-enabled" check/s3-public-access-block-enabled})

(def ^:private all-checks (vals checks-registry))

(When "I load the checks into Datomic" [state]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        loader (check/->CheckLoader all-checks)]
    (sync/ingest! conn (run loader))
    (assoc state :datomic-conn conn)))

(Then "the check {string} exists in Datomic" [state check-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :heron.check/id ?id]] db check-id)]
    (assert eid (str "Check '" check-id "' not found in Datomic")))
  state)

(Given "a compliant S3 bucket {string} is present in Datomic" [state bucket-name]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        entity {:heron/id                              (str "aws:000000000000:s3:bucket:" bucket-name)
                :heron/provider                        :aws
                :heron/label                           bucket-name
                :aws.s3.bucket/name                    bucket-name
                :aws.s3.bucket/block-public-acls       true
                :aws.s3.bucket/ignore-public-acls      true
                :aws.s3.bucket/block-public-policy     true
                :aws.s3.bucket/restrict-public-buckets true}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn)))

(When "I evaluate the {string} check" [state check-id]
  (let [chk    (get checks-registry check-id)
        db     (d/db (:datomic-conn state))
        result (check/evaluate db chk)]
    (assoc state :check-result result)))

(Then "{string} appears in the violations" [state label]
  (let [violations (:violations (:check-result state))
        labels     (set (map :heron/label violations))]
    (assert (contains? labels label)
            (str "Expected '" label "' in violations, got: " labels)))
  state)

(Then "{string} does not appear in the violations" [state label]
  (let [violations (:violations (:check-result state))
        labels     (set (map :heron/label violations))]
    (assert (not (contains? labels label))
            (str "Expected '" label "' absent from violations, but it was present")))
  state)
