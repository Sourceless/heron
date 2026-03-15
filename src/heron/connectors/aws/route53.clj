(ns heron.connectors.aws.route53
  (:require [heron.connector :as connector :refer [IConnector]]
            [clojure.string  :as str]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for Route53 resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [;; Hosted Zone
         {:db/ident       :aws.route53.hosted-zone/zone-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Hosted zone ID (without the /hostedzone/ prefix)."}

         {:db/ident       :aws.route53.hosted-zone/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Zone name, e.g. example.com."}

         {:db/ident       :aws.route53.hosted-zone/private?
          :db/valueType   :db.type/boolean
          :db/cardinality :db.cardinality/one
          :db/doc         "True if the zone is private (VPC-associated)."}

         ;; Record Set
         {:db/ident       :aws.route53.record-set/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Record name, e.g. www.example.com."}

         {:db/ident       :aws.route53.record-set/type
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Record type: A, AAAA, CNAME, MX, NS, SOA, TXT, etc."}

         {:db/ident       :aws.route53.record-set/ttl
          :db/valueType   :db.type/long
          :db/cardinality :db.cardinality/one
          :db/doc         "TTL in seconds. 0 for alias records."}

         {:db/ident       :aws.route53.record-set/values
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/many
          :db/doc         "Record values. For alias records, the DNS name of the alias target."}

         {:db/ident       :aws.route53.record-set/zone-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Zone ID this record belongs to."}]))

(defn- strip-zone-prefix [raw-id]
  (str/replace raw-id #"^/hostedzone/" ""))

(defn- zone->entity [account-id {:keys [Id Name Config]}]
  (let [zone-id (strip-zone-prefix Id)]
    {:heron/id                        (str "aws:" account-id ":route53:hosted-zone:" zone-id)
     :heron/provider                  :aws
     :heron/label                     Name
     :aws.route53.hosted-zone/zone-id zone-id
     :aws.route53.hosted-zone/name    Name
     :aws.route53.hosted-zone/private? (= "PRIVATE" (:PrivateZone Config))}))

(defn- record-values [{:keys [ResourceRecords AliasTarget]}]
  (if AliasTarget
    [(:DNSName AliasTarget)]
    (mapv :Value ResourceRecords)))

(defn- record->entity [account-id zone-id {:keys [Name Type TTL] :as record}]
  {:heron/id                          (str "aws:" account-id ":route53:record-set:" zone-id "/" Name "/" Type)
   :heron/provider                    :aws
   :heron/label                       (str Name " " Type)
   :aws.route53.record-set/name       Name
   :aws.route53.record-set/type       Type
   :aws.route53.record-set/ttl        (or TTL 0)
   :aws.route53.record-set/values     (record-values record)
   :aws.route53.record-set/zone-id    zone-id})

(defn- list-zones [client]
  (loop [marker nil acc []]
    (let [req      (cond-> {:op :ListHostedZones}
                     marker (assoc :request {:Marker marker}))
          response (aws/invoke client req)]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "Route53 ListHostedZones failed" {:response response})))
      (let [next-acc (into acc (:HostedZones response))]
        (if (:IsTruncated response)
          (recur (:NextMarker response) next-acc)
          next-acc)))))

(defrecord Route53HostedZonesConnector [client account-id]
  IConnector
  (run [_]
    (mapv (partial zone->entity account-id) (list-zones client))))

(defrecord Route53RecordSetsConnector [client account-id]
  IConnector
  (run [_]
    (let [zones (list-zones client)]
      (vec
        (mapcat
          (fn [zone]
            (let [zone-id (strip-zone-prefix (:Id zone))]
              (loop [token nil acc []]
                (let [req      (cond-> {:op     :ListResourceRecordSets
                                        :request {:HostedZoneId zone-id}}
                                  token (assoc-in [:request :StartRecordName] token))
                      response (aws/invoke client req)]
                  (when (:cognitect.anomalies/category response)
                    (throw (ex-info "Route53 ListResourceRecordSets failed" {:response response})))
                  (let [records  (mapv (partial record->entity account-id zone-id)
                                       (:ResourceRecordSets response))
                        next-acc (into acc records)]
                    (if (:IsTruncated response)
                      (recur (:NextRecordName response) next-acc)
                      next-acc))))))
          zones)))))

(defn make-zones-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [region account-id endpoint-override] :or {region "us-east-1"}}]
  (->Route53HostedZonesConnector
    (aws/client (cond-> {:api :route53 :region region}
                  endpoint-override (assoc :endpoint-override endpoint-override)))
    account-id))

(defn make-record-sets-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [region account-id endpoint-override] :or {region "us-east-1"}}]
  (->Route53RecordSetsConnector
    (aws/client (cond-> {:api :route53 :region region}
                  endpoint-override (assoc :endpoint-override endpoint-override)))
    account-id))
