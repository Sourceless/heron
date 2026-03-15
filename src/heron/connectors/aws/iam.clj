(ns heron.connectors.aws.iam
  (:require [heron.connector :as connector :refer [IConnector]]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for IAM resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [{:db/ident       :aws.iam.role/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "IAM role name as returned by ListRoles."}

         {:db/ident       :aws.iam.user/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "IAM user name as returned by ListUsers."}]))

(defn- role->entity [account-id {:keys [RoleName]}]
  {:heron/id          (str "aws:" account-id ":iam:role:" RoleName)
   :heron/provider    :aws
   :heron/label       RoleName
   :aws.iam.role/name RoleName})

(defn- user->entity [account-id {:keys [UserName]}]
  {:heron/id          (str "aws:" account-id ":iam:user:" UserName)
   :heron/provider    :aws
   :heron/label       UserName
   :aws.iam.user/name UserName})

(defn- make-client [{:keys [region endpoint-override] :or {region "us-east-1"}}]
  (aws/client (cond-> {:api :iam :region region}
                endpoint-override (assoc :endpoint-override endpoint-override))))

(defrecord IAMRolesConnector [client account-id]
  IConnector
  (run [_]
    (let [response (aws/invoke client {:op :ListRoles})]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "IAM ListRoles failed" {:response response})))
      (mapv (partial role->entity account-id) (:Roles response)))))

(defrecord IAMUsersConnector [client account-id]
  IConnector
  (run [_]
    (let [response (aws/invoke client {:op :ListUsers})]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "IAM ListUsers failed" {:response response})))
      (mapv (partial user->entity account-id) (:Users response)))))

(defn make-roles-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [account-id] :as opts}]
  (->IAMRolesConnector (make-client opts) account-id))

(defn make-users-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [account-id] :as opts}]
  (->IAMUsersConnector (make-client opts) account-id))
