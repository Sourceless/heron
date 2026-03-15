(ns heron.sync
  (:require [clojure.set :as set]
            [datomic.api :as d]))

(defn ensure-schema!
  "Transacts a schema attribute vector into conn. Safe to call repeatedly —
   Datomic is idempotent for attributes with identical definitions."
  [conn attrs]
  @(d/transact conn attrs))

(defn ingest!
  "Transacts entity maps into Datomic. Idempotent via :heron/id upsert (ADR-3).
   Returns the transaction result map, or nil if entities is empty."
  [conn entities]
  (when (seq entities)
    @(d/transact conn (vec entities))))

(defn record-run!
  "Transacts a ConnectorRun metadata entity. Call after ingest!.
   opts: {:provider keyword, :connector string,
          :started-at java.util.Date, :finished-at java.util.Date,
          :resource-count long}"
  [conn {:keys [provider connector started-at finished-at resource-count]}]
  @(d/transact conn [{:heron.connector-run/id             (str (java.util.UUID/randomUUID))
                      :heron.connector-run/provider       provider
                      :heron.connector-run/connector      connector
                      :heron.connector-run/started-at     started-at
                      :heron.connector-run/finished-at    finished-at
                      :heron.connector-run/resource-count resource-count}]))

(defn retract-absent!
  "Retract entities that carry scope-attr but whose :heron/id is absent from current-ids.
   Use after ingest! to remove resources that disappeared from the provider.
   scope-attr — a Datomic attribute present on every entity this connector manages
                (e.g. :aws.s3.bucket/name); used to scope the retraction to this connector.
   current-ids — set (or seq) of :heron/id strings from the current connector run."
  [conn scope-attr current-ids]
  (let [db       (d/db conn)
        prev-ids (set (d/q '[:find [?id ...]
                              :in $ ?a
                              :where [?e ?a]
                                     [?e :heron/id ?id]]
                            db scope-attr))
        absent   (set/difference prev-ids (set current-ids))]
    (when (seq absent)
      (let [eids        (keep #(d/q '[:find ?e . :in $ ?id
                                      :where [?e :heron/id ?id]]
                                    (d/db conn) %)
                              absent)
            retractions (mapv #(vector :db/retractEntity %) eids)]
        (when (seq retractions)
          @(d/transact conn retractions))))))
