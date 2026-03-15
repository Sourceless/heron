(ns heron.connectors.aws.s3
  (:require [heron.connector :as connector :refer [IConnector]]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for S3 resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [{:db/ident       :aws.s3.bucket/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "S3 bucket name as returned by ListBuckets."}

         {:db/ident       :aws.s3.bucket/region
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "AWS region where the bucket was created."}]))

(defn- bucket->entity [account-id {:keys [Name]}]
  {:heron/id           (str "aws:" account-id ":s3:bucket:" Name)
   :heron/provider     :aws
   :heron/label        Name
   :aws.s3.bucket/name Name})

(defrecord S3Connector [client account-id]
  IConnector
  (run [_]
    (let [response (aws/invoke client {:op :ListBuckets})]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "S3 ListBuckets failed" {:response response})))
      (mapv (partial bucket->entity account-id) (:Buckets response)))))

(defn make-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [region account-id endpoint-override] :or {region "us-east-1"}}]
  (->S3Connector
    (aws/client (cond-> {:api :s3 :region region}
                  endpoint-override (assoc :endpoint-override endpoint-override)))
    account-id))
