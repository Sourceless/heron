(ns heron.connector)

(def base-schema
  "Datomic attributes required by every connector. Include this in each
   connector's schema vector. Datomic schema transact is idempotent, so
   multiple connectors all declaring :heron/id is safe."
  [{:db/ident       :heron/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Stable entity identity: <provider>:<account>:<service>:<type>:<id>"}])

(defprotocol IConnector
  "All connectors implement this single method.
   run returns a seq of entity maps, each containing:
     :heron/id — stable string, format: <provider>:<account>:<service>:<type>:<id>
   plus namespaced attributes for the resource's properties."
  (run [this]))
