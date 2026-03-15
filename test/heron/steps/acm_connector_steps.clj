(ns heron.steps.acm-connector-steps
  (:require [lambdaisland.cucumber.dsl    :refer [Given When Then]]
            [heron.sync                   :as sync]
            [heron.connectors.aws.acm     :as acm]
            [datomic.api                  :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test-acm?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/delete-database test-db-uri)
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn acm/schema)
    conn))

(defn- test-arn [domain]
  (str "arn:aws:acm:us-east-1:000000000000:certificate:"
       (-> domain (clojure.string/replace #"\." "-") (str "-id"))))

(Given "a synthetic ACM certificate for {string} is present in Datomic" [state domain]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        arn    (test-arn domain)
        entity {:heron/id                              (str "aws:000000000000:acm:certificate:" arn)
                :heron/provider                        :aws
                :heron/label                           domain
                :aws.acm.certificate/arn               arn
                :aws.acm.certificate/domain            domain
                :aws.acm.certificate/status            "ISSUED"
                :aws.acm.certificate/renewal-eligibility "ELIGIBLE"
                :aws.acm.certificate/sans              [domain (str "www." domain)]}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn :last-cert-domain domain)))

(When "I retract the ACM certificate for {string} from Datomic" [state domain]
  (let [conn (:datomic-conn state)
        db   (d/db conn)
        eid  (d/q '[:find ?e . :in $ ?d :where [?e :aws.acm.certificate/domain ?d]] db domain)]
    (when eid @(d/transact conn [[:db/retractEntity eid]])))
  state)

(Then "the ACM certificate for {string} exists in Datomic" [state domain]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?d :where [?e :aws.acm.certificate/domain ?d]] db domain)]
    (assert eid (str "ACM certificate for '" domain "' not found in Datomic")))
  state)

(Then "the ACM certificate for {string} has provider :aws and label {string}" [state domain label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?d :where [?e :aws.acm.certificate/domain ?d]] db domain)]
    (assert eid)
    (assert (= :aws  (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid)))
    (assert (= label (d/q '[:find ?v . :in $ ?e :where [?e :heron/label ?v]] db eid))))
  state)

(Then "the ACM certificate for {string} has heron id {string}" [state domain expected-hid]
  (let [db  (d/db (:datomic-conn state))
        hid (d/q '[:find ?v . :in $ ?d :where [?e :aws.acm.certificate/domain ?d]
                                               [?e :heron/id ?v]] db domain)]
    (assert (= expected-hid hid) (str "Expected heron/id '" expected-hid "', got '" hid "'")))
  state)

(Then "the ACM certificate for {string} does not exist in the current Datomic db" [state domain]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?d :where [?e :aws.acm.certificate/domain ?d]] db domain)]
    (assert (nil? eid) (str "ACM certificate for '" domain "' should have been retracted")))
  state)
