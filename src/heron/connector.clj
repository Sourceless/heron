(ns heron.connector)

(def base-schema
  "Datomic attributes required by every connector. Include this in each
   connector's schema vector. Datomic schema transact is idempotent, so
   multiple connectors all declaring :heron/id is safe."
  [{:db/ident       :heron/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable entity identity: <provider>:<account>:<service>:<type>:<id>"}

   {:db/ident       :heron/provider
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Provider keyword: :aws | :github"}

   {:db/ident       :heron/label
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Human-readable display name for the resource."}

   {:db/ident       :heron/tags
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true
    :db/doc         "Provider tags as component entities (:heron.tag/key + :heron.tag/value)."}

   {:db/ident       :heron.tag/key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Tag key (e.g. \"env\")."}

   {:db/ident       :heron.tag/value
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Tag value (e.g. \"production\")."}])

(def connector-run-schema
  "Datomic attributes for ConnectorRun metadata entities."
  [{:db/ident       :heron.connector-run/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Unique run ID (random UUID string)."}

   {:db/ident       :heron.connector-run/provider
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/doc         "Provider keyword: :aws | :github"}

   {:db/ident       :heron.connector-run/connector
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "Connector name, e.g. \"s3\", \"iam-roles\", \"iam-users\"."}

   {:db/ident       :heron.connector-run/started-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Wall-clock time when connector/run was invoked."}

   {:db/ident       :heron.connector-run/finished-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "Wall-clock time when sync/ingest! completed."}

   {:db/ident       :heron.connector-run/resource-count
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Number of resource entities returned by the connector in this run."}])

(defprotocol IConnector
  "All connectors implement this single method.
   run returns a seq of entity maps, each containing:
     :heron/id — stable string, format: <provider>:<account>:<service>:<type>:<id>
   plus namespaced attributes for the resource's properties."
  (run [this]))
