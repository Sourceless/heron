(ns heron.steps.report-engine-steps
  (:require [lambdaisland.cucumber.dsl  :refer [Given When Then]]
            [heron.report               :as report]
            [heron.connector            :refer [run]]
            [heron.sync                 :as sync]
            [heron.connectors.aws.s3    :as s3]
            [clojure.string             :as str]
            [datomic.api                :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test-report-engine?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn!
  "Drops and recreates the report-engine test database so each scenario
   starts from a clean slate."
  []
  (d/delete-database test-db-uri)
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn s3/schema)
    (sync/ensure-schema! conn report/report-schema)
    conn))

(def ^:private reports-registry
  {"aws/untagged-ec2-instances"   report/untagged-ec2-instances
   "aws/expiring-acm-certificates" report/expiring-acm-certificates
   "github/public-repositories"   report/public-github-repositories})

(def ^:private all-reports (vals reports-registry))

;; Ad-hoc report exercising the existing S3 schema — used in test scenarios
;; because EC2/GitHub connectors are not yet implemented.
(def ^:private all-s3-buckets
  {:heron.report/id   "all-s3-buckets"
   :heron.report/name "All S3 Buckets"
   :heron.report/query
   '[:find (pull ?e [:heron/id :heron/label])
     :where [?e :aws.s3.bucket/name _]]})

(def ^:private test-reports-registry
  (assoc reports-registry "all-s3-buckets" all-s3-buckets))

(When "I load the reports into Datomic" [state]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        loader (report/->ReportLoader all-reports)]
    (sync/ingest! conn (run loader))
    (assoc state :datomic-conn conn)))

(Then "the report {string} exists in Datomic" [state report-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :heron.report/id ?id]] db report-id)]
    (assert eid (str "Report '" report-id "' not found in Datomic")))
  state)

(Given "an S3 bucket {string} is present in Datomic" [state bucket-name]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        entity {:heron/id             (str "aws:000000000000:s3:bucket:" bucket-name)
                :heron/provider       :aws
                :heron/label          bucket-name
                :aws.s3.bucket/name   bucket-name
                :aws.s3.bucket/region "us-east-1"}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn)))

(When "I evaluate the {string} report" [state report-id]
  (let [rpt    (get test-reports-registry report-id)
        db     (d/db (:datomic-conn state))
        result (report/evaluate db rpt)]
    (assoc state :report-result result)))

(When "I evaluate the {string} report again" [state report-id]
  (let [rpt    (get test-reports-registry report-id)
        db     (d/db (:datomic-conn state))
        result (report/evaluate db rpt)]
    (assoc state :report-result result)))

(When "I record the report run" [state]
  (report/record-run! (:datomic-conn state) (:report-result state))
  state)

(When "{string} is retracted from Datomic" [state bucket-name]
  (let [conn (or (:datomic-conn state) (fresh-conn!))
        db   (d/db conn)
        hid  (str "aws:000000000000:s3:bucket:" bucket-name)
        eid  (d/q '[:find ?e . :in $ ?id :where [?e :heron/id ?id]] db hid)]
    (when eid
      @(d/transact conn [[:db/retractEntity eid]])))
  state)

(Then "the report has {int} result(s)" [state n]
  (let [results (:result (:report-result state))]
    (assert (= n (count results))
            (str "Expected " n " result(s), got " (count results) ": " results)))
  state)

(Then "{string} appears in the report results" [state label]
  (let [results (:result (:report-result state))
        labels  (set (keep :heron/label results))]
    (assert (contains? labels label)
            (str "Expected '" label "' in report results, got: " labels)))
  state)

(Then "{string} appears in the added items" [state label]
  (let [added (:added (:report-result state))]
    (assert (some #(str/includes? % label) added)
            (str "Expected '" label "' in added items, got: " added)))
  state)

(Then "{string} appears in the removed items" [state label]
  (let [removed (:removed (:report-result state))]
    (assert (some #(str/includes? % label) removed)
            (str "Expected '" label "' in removed items, got: " removed)))
  state)
