(ns heron.sync
  (:require [datomic.api :as d]))

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
