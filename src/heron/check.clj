(ns heron.check
  (:require [heron.connector :as connector :refer [IConnector]]
            [heron.sync      :as sync]
            [datomic.api     :as d]))

(def check-schema
  "Datomic attributes for Check entities. Pass to sync/ensure-schema! before loading checks."
  (into connector/base-schema
        [{:db/ident       :heron.check/id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Stable check identifier, e.g. 'aws.s3/public-access-block-enabled'"}

         {:db/ident       :heron.check/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Human-readable check name"}

         {:db/ident       :heron.check/description
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "What this check tests and why"}]))

(def s3-public-access-block-enabled
  {:heron/id               "heron:check:aws.s3/public-access-block-enabled"
   :heron/label            "S3 Public Access Block Enabled"
   :heron.check/id         "aws.s3/public-access-block-enabled"
   :heron.check/name       "S3 Public Access Block Enabled"
   :heron.check/description
   "All S3 buckets must have all four public access block settings enabled."
   :heron.check/query
   '[:find ?id ?label
     :where [?e :aws.s3.bucket/name _]
            [?e :heron/id ?id]
            [?e :heron/label ?label]
            (not-join [?e]
              [?e :aws.s3.bucket/block-public-acls true]
              [?e :aws.s3.bucket/ignore-public-acls true]
              [?e :aws.s3.bucket/block-public-policy true]
              [?e :aws.s3.bucket/restrict-public-buckets true])]})

(defrecord CheckLoader [checks]
  IConnector
  (run [_]
    (mapv #(dissoc % :heron.check/query) checks)))

(defn evaluate
  "Runs check's in-code Datalog query against db.
   Returns {:heron.check/id string, :heron.check/passing? boolean,
            :violations [{:heron/id string, :heron/label string} ...]}"
  [db check]
  (let [results    (d/q (:heron.check/query check) db)
        violations (mapv (fn [[hid label]] {:heron/id hid :heron/label label}) results)]
    {:heron.check/id       (:heron.check/id check)
     :heron.check/passing? (empty? violations)
     :violations            violations}))
