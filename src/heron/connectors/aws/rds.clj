(ns heron.connectors.aws.rds
  (:require [heron.connector :as connector :refer [IConnector]]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for RDS resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [{:db/ident       :aws.rds.db-instance/id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "RDS DB instance identifier."}

         {:db/ident       :aws.rds.db-instance/engine
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Database engine (e.g. mysql, postgres, aurora-mysql)."}

         {:db/ident       :aws.rds.db-instance/engine-version
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Engine version string."}

         {:db/ident       :aws.rds.db-instance/class
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "DB instance class (e.g. db.t3.micro)."}

         {:db/ident       :aws.rds.db-instance/multi-az?
          :db/valueType   :db.type/boolean
          :db/cardinality :db.cardinality/one
          :db/doc         "True if the instance is deployed in multiple Availability Zones."}

         {:db/ident       :aws.rds.db-instance/publicly-accessible?
          :db/valueType   :db.type/boolean
          :db/cardinality :db.cardinality/one
          :db/doc         "True if the instance is publicly accessible."}

         {:db/ident       :aws.rds.db-instance/encrypted?
          :db/valueType   :db.type/boolean
          :db/cardinality :db.cardinality/one
          :db/doc         "True if storage encryption is enabled."}]))

(defn- db-instance->entity [account-id {:keys [DBInstanceIdentifier Engine EngineVersion
                                                DBInstanceClass MultiAZ
                                                PubliclyAccessible StorageEncrypted]}]
  {:heron/id                            (str "aws:" account-id ":rds:db-instance:" DBInstanceIdentifier)
   :heron/provider                      :aws
   :heron/label                         DBInstanceIdentifier
   :aws.rds.db-instance/id              DBInstanceIdentifier
   :aws.rds.db-instance/engine          Engine
   :aws.rds.db-instance/engine-version  EngineVersion
   :aws.rds.db-instance/class           DBInstanceClass
   :aws.rds.db-instance/multi-az?       (boolean MultiAZ)
   :aws.rds.db-instance/publicly-accessible? (boolean PubliclyAccessible)
   :aws.rds.db-instance/encrypted?      (boolean StorageEncrypted)})

(defrecord RDSInstancesConnector [client account-id]
  IConnector
  (run [_]
    (loop [marker nil acc []]
      (let [req      (cond-> {:op :DescribeDBInstances}
                       marker (assoc :request {:Marker marker}))
            response (aws/invoke client req)]
        (when (:cognitect.anomalies/category response)
          (throw (ex-info "RDS DescribeDBInstances failed" {:response response})))
        (let [instances (mapv (partial db-instance->entity account-id) (:DBInstances response))
              next-acc  (into acc instances)]
          (if-let [next-marker (:Marker response)]
            (recur next-marker next-acc)
            next-acc))))))

(defn make-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [region account-id endpoint-override] :or {region "us-east-1"}}]
  (->RDSInstancesConnector
    (aws/client (cond-> {:api :rds :region region}
                  endpoint-override (assoc :endpoint-override endpoint-override)))
    account-id))
