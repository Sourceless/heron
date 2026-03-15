(ns heron.report
  (:require [heron.connector :as connector :refer [IConnector]]
            [clojure.set     :as set]
            [datomic.api     :as d]))

(def report-schema
  "Datomic attributes for Report and ReportRun entities.
   Pass to sync/ensure-schema! before loading reports."
  (into connector/base-schema
        [;; Report entity
         {:db/ident       :heron.report/id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/unique      :db.unique/identity
          :db/doc         "Stable report identifier, e.g. 'aws/untagged-ec2-instances'"}

         {:db/ident       :heron.report/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Human-readable report name"}

         {:db/ident       :heron.report/description
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "What this report lists"}

         ;; ReportRun entity
         {:db/ident       :heron.report-run/id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/unique      :db.unique/identity
          :db/doc         "Unique run ID (random UUID string)"}

         {:db/ident       :heron.report-run/report-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "The :heron.report/id this run belongs to"}

         {:db/ident       :heron.report-run/evaluated-at
          :db/valueType   :db.type/instant
          :db/cardinality :db.cardinality/one
          :db/doc         "Wall-clock time when the report was evaluated"}

         {:db/ident       :heron.report-run/result-count
          :db/valueType   :db.type/long
          :db/cardinality :db.cardinality/one
          :db/doc         "Number of items in this run's result set"}

         {:db/ident       :heron.report-run/result-ids
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/many
          :db/doc         "The :heron/id of every item in this run's result set"}

         {:db/ident       :heron.report-run/added-ids
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/many
          :db/doc         "Items present in this run but absent from the previous run"}

         {:db/ident       :heron.report-run/removed-ids
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/many
          :db/doc         "Items absent from this run but present in the previous run"}]))

(def untagged-ec2-instances
  {:heron/id                 "heron:report:aws/untagged-ec2-instances"
   :heron/label              "Untagged EC2 Instances"
   :heron.report/id          "aws/untagged-ec2-instances"
   :heron.report/name        "Untagged EC2 Instances"
   :heron.report/description "EC2 instances missing required cost-center or environment tags."
   :heron.report/query
   '[:find (pull ?e [:heron/id :heron/label])
     :where [?e :aws.ec2.instance/id _]
            (not [?e :aws.ec2.instance/tag-cost-center _])]})

(def expiring-acm-certificates
  {:heron/id                 "heron:report:aws/expiring-acm-certificates"
   :heron/label              "Expiring ACM Certificates"
   :heron.report/id          "aws/expiring-acm-certificates"
   :heron.report/name        "Expiring ACM Certificates"
   :heron.report/description "TLS certificates expiring within 30 days."
   ;; Expiry filter is applied in Clojure after query — ACM connector populates
   ;; :aws.acm.certificate/expiry as a java.util.Date; post-processing filters
   ;; to within 30 days. Until the ACM connector is implemented this returns #{}.
   :heron.report/query
   '[:find (pull ?e [:heron/id :heron/label :aws.acm.certificate/expiry])
     :where [?e :aws.acm.certificate/expiry _]]})

(def public-github-repositories
  {:heron/id                 "heron:report:github/public-repositories"
   :heron/label              "Public GitHub Repositories"
   :heron.report/id          "github/public-repositories"
   :heron.report/name        "Public GitHub Repositories"
   :heron.report/description "All public repositories in the organisation."
   :heron.report/query
   '[:find (pull ?e [:heron/id :heron/label])
     :where [?e :github.repo/visibility "public"]]})

(defrecord ReportLoader [reports]
  IConnector
  (run [_]
    (mapv #(dissoc % :heron.report/query) reports)))

(defn- prev-result-ids
  "Returns the set of :heron/id strings from the most recent ReportRun
   for report-id, or #{} if no previous run exists."
  [db report-id]
  (let [runs (d/q '[:find ?run ?t
                    :in $ ?report-id
                    :where [?run :heron.report-run/report-id ?report-id]
                           [?run :heron.report-run/evaluated-at ?t]]
                  db report-id)]
    (if (seq runs)
      (let [latest-eid (first (apply max-key second runs))]
        (set (:heron.report-run/result-ids
              (d/pull db [:heron.report-run/result-ids] latest-eid))))
      #{})))

(defn evaluate
  "Runs report's in-code Datalog query against db, then computes change
   detection against the most recent ReportRun stored in db.
   Returns {:heron.report/id string
            :result          [map ...]  ; full pull results from the query
            :result-ids      #{string}  ; :heron/id of every item in result
            :added           #{string}  ; ids present now but absent from prev run
            :removed         #{string}} ; ids absent now but present in prev run"
  [db report]
  (let [raw         (d/q (:heron.report/query report) db)
        result      (mapv first raw)
        current-ids (set (keep :heron/id result))
        prev-ids    (prev-result-ids db (:heron.report/id report))]
    {:heron.report/id (:heron.report/id report)
     :result          result
     :result-ids      current-ids
     :added           (set/difference current-ids prev-ids)
     :removed         (set/difference prev-ids current-ids)}))

(defn record-run!
  "Persists a ReportRun entity to Datomic from the result of evaluate."
  [conn result]
  @(d/transact conn [{:heron.report-run/id           (str (java.util.UUID/randomUUID))
                      :heron.report-run/report-id    (:heron.report/id result)
                      :heron.report-run/evaluated-at (java.util.Date.)
                      :heron.report-run/result-count (count (:result result))
                      :heron.report-run/result-ids   (vec (:result-ids result))
                      :heron.report-run/added-ids    (vec (:added result))
                      :heron.report-run/removed-ids  (vec (:removed result))}]))
