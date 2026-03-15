(ns heron.connectors.aws.lambda
  (:require [heron.connector :as connector :refer [IConnector]]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for Lambda resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [{:db/ident       :aws.lambda.function/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Lambda function name."}

         {:db/ident       :aws.lambda.function/runtime
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Runtime identifier (e.g. nodejs20.x, python3.12)."}

         {:db/ident       :aws.lambda.function/handler
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Handler entry point (e.g. index.handler)."}

         {:db/ident       :aws.lambda.function/role
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "ARN of the execution role."}

         {:db/ident       :aws.lambda.function/timeout
          :db/valueType   :db.type/long
          :db/cardinality :db.cardinality/one
          :db/doc         "Function timeout in seconds."}

         {:db/ident       :aws.lambda.function/memory-size
          :db/valueType   :db.type/long
          :db/cardinality :db.cardinality/one
          :db/doc         "Memory allocated to the function in MB."}

         {:db/ident       :aws.lambda.function/env-var-names
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/many
          :db/doc         "Names (not values) of environment variables configured on the function."}]))

(defn- function->entity [account-id {:keys [FunctionName Runtime Handler Role Timeout MemorySize Environment]}]
  (let [env-var-names (vec (keys (:Variables Environment)))]
    {:heron/id                          (str "aws:" account-id ":lambda:function:" FunctionName)
     :heron/provider                    :aws
     :heron/label                       FunctionName
     :aws.lambda.function/name          FunctionName
     :aws.lambda.function/runtime       (or Runtime "")
     :aws.lambda.function/handler       (or Handler "")
     :aws.lambda.function/role          (or Role "")
     :aws.lambda.function/timeout       (or Timeout 0)
     :aws.lambda.function/memory-size   (or MemorySize 128)
     :aws.lambda.function/env-var-names (mapv name env-var-names)}))

(defrecord LambdaFunctionsConnector [client account-id]
  IConnector
  (run [_]
    (loop [marker nil acc []]
      (let [req      (cond-> {:op :ListFunctions}
                       marker (assoc :request {:Marker marker}))
            response (aws/invoke client req)]
        (when (:cognitect.anomalies/category response)
          (throw (ex-info "Lambda ListFunctions failed" {:response response})))
        (let [functions (mapv (partial function->entity account-id) (:Functions response))
              next-acc  (into acc functions)]
          (if-let [next-marker (:NextMarker response)]
            (recur next-marker next-acc)
            next-acc))))))

(defn make-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [region account-id endpoint-override] :or {region "us-east-1"}}]
  (->LambdaFunctionsConnector
    (aws/client (cond-> {:api :lambda :region region}
                  endpoint-override (assoc :endpoint-override endpoint-override)))
    account-id))
