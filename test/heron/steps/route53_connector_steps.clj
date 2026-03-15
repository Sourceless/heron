(ns heron.steps.route53-connector-steps
  (:require [lambdaisland.cucumber.dsl      :refer [Given When Then]]
            [heron.sync                     :as sync]
            [heron.connectors.aws.route53   :as route53]
            [datomic.api                    :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test-route53?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/delete-database test-db-uri)
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn route53/schema)
    conn))

(Given "a synthetic Route53 hosted zone {string} with id {string} is present in Datomic" [state zone-name zone-id]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        entity {:heron/id                         (str "aws:000000000000:route53:hosted-zone:" zone-id)
                :heron/provider                   :aws
                :heron/label                      zone-name
                :aws.route53.hosted-zone/zone-id  zone-id
                :aws.route53.hosted-zone/name     zone-name
                :aws.route53.hosted-zone/private? false}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn)))

(Given "a synthetic Route53 record set {string} type {string} in zone {string} is present in Datomic" [state rec-name rec-type zone-id]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        entity {:heron/id                       (str "aws:000000000000:route53:record-set:" zone-id "/" rec-name "/" rec-type)
                :heron/provider                 :aws
                :heron/label                    (str rec-name " " rec-type)
                :aws.route53.record-set/name    rec-name
                :aws.route53.record-set/type    rec-type
                :aws.route53.record-set/ttl     300
                :aws.route53.record-set/values  ["1.2.3.4"]
                :aws.route53.record-set/zone-id zone-id}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn)))

(When "I retract the hosted zone {string} from Datomic" [state zone-id]
  (let [conn (:datomic-conn state)
        db   (d/db conn)
        eid  (d/q '[:find ?e . :in $ ?id :where [?e :aws.route53.hosted-zone/zone-id ?id]] db zone-id)]
    (when eid @(d/transact conn [[:db/retractEntity eid]])))
  state)

(Then "the hosted zone {string} exists in Datomic" [state zone-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.route53.hosted-zone/zone-id ?id]] db zone-id)]
    (assert eid (str "Hosted zone '" zone-id "' not found in Datomic")))
  state)

(Then "the hosted zone {string} has provider :aws and label {string}" [state zone-id label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.route53.hosted-zone/zone-id ?id]] db zone-id)]
    (assert eid)
    (assert (= :aws  (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid)))
    (assert (= label (d/q '[:find ?v . :in $ ?e :where [?e :heron/label ?v]] db eid))))
  state)

(Then "the hosted zone {string} has heron id {string}" [state zone-id expected-hid]
  (let [db  (d/db (:datomic-conn state))
        hid (d/q '[:find ?v . :in $ ?id :where [?e :aws.route53.hosted-zone/zone-id ?id]
                                                [?e :heron/id ?v]] db zone-id)]
    (assert (= expected-hid hid) (str "Expected heron/id '" expected-hid "', got '" hid "'")))
  state)

(Then "the record set {string} type {string} exists in Datomic" [state rec-name rec-type]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n ?t :where [?e :aws.route53.record-set/name ?n]
                                                  [?e :aws.route53.record-set/type ?t]] db rec-name rec-type)]
    (assert eid (str "Record set '" rec-name " " rec-type "' not found in Datomic")))
  state)

(Then "the hosted zone {string} does not exist in the current Datomic db" [state zone-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.route53.hosted-zone/zone-id ?id]] db zone-id)]
    (assert (nil? eid) (str "Hosted zone '" zone-id "' should have been retracted")))
  state)
