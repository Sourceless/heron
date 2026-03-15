(ns heron.connectors.aws.acm
  (:require [heron.connector :as connector :refer [IConnector]]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for ACM resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [{:db/ident       :aws.acm.certificate/arn
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Certificate ARN."}

         {:db/ident       :aws.acm.certificate/domain
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Primary domain name (DomainName)."}

         {:db/ident       :aws.acm.certificate/status
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Certificate status (ISSUED, PENDING_VALIDATION, EXPIRED, etc.)."}

         {:db/ident       :aws.acm.certificate/renewal-eligibility
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Renewal eligibility (ELIGIBLE or INELIGIBLE)."}

         {:db/ident       :aws.acm.certificate/expiry
          :db/valueType   :db.type/instant
          :db/cardinality :db.cardinality/one
          :db/doc         "Certificate expiry date (NotAfter)."}

         {:db/ident       :aws.acm.certificate/sans
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/many
          :db/doc         "Subject alternative names."}]))

(defn- describe-cert [client arn]
  (let [response (aws/invoke client {:op      :DescribeCertificate
                                     :request {:CertificateArn arn}})]
    (when (:cognitect.anomalies/category response)
      (throw (ex-info "ACM DescribeCertificate failed" {:arn arn :response response})))
    (:Certificate response)))

(defn- cert->entity [account-id {:keys [CertificateArn DomainName Status
                                         RenewalEligibility NotAfter SubjectAlternativeNames]}]
  (cond-> {:heron/id                          (str "aws:" account-id ":acm:certificate:" CertificateArn)
           :heron/provider                    :aws
           :heron/label                       DomainName
           :aws.acm.certificate/arn           CertificateArn
           :aws.acm.certificate/domain        DomainName
           :aws.acm.certificate/status        (or Status "")
           :aws.acm.certificate/renewal-eligibility (or RenewalEligibility "")
           :aws.acm.certificate/sans          (vec (or SubjectAlternativeNames []))}
    NotAfter (assoc :aws.acm.certificate/expiry NotAfter)))

(defrecord ACMCertificatesConnector [client account-id]
  IConnector
  (run [_]
    (loop [token nil acc []]
      (let [req      (cond-> {:op :ListCertificates}
                       token (assoc :request {:NextToken token}))
            response (aws/invoke client req)]
        (when (:cognitect.anomalies/category response)
          (throw (ex-info "ACM ListCertificates failed" {:response response})))
        (let [arns     (mapv :CertificateArn (:CertificateSummaryList response))
              details  (mapv #(cert->entity account-id (describe-cert client %)) arns)
              next-acc (into acc details)]
          (if-let [next-token (:NextToken response)]
            (recur next-token next-acc)
            next-acc))))))

(defn make-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [region account-id endpoint-override] :or {region "us-east-1"}}]
  (->ACMCertificatesConnector
    (aws/client (cond-> {:api :acm :region region}
                  endpoint-override (assoc :endpoint-override endpoint-override)))
    account-id))
