(ns heron.steps.rds-connector-steps
  (:require [lambdaisland.cucumber.dsl    :refer [Given When Then]]
            [heron.sync                   :as sync]
            [heron.connectors.aws.rds     :as rds]
            [datomic.api                  :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test-rds?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/delete-database test-db-uri)
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn rds/schema)
    conn))

(Given "a synthetic RDS instance {string} is present in Datomic" [state db-id]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        entity {:heron/id                                (str "aws:000000000000:rds:db-instance:" db-id)
                :heron/provider                          :aws
                :heron/label                             db-id
                :aws.rds.db-instance/id                  db-id
                :aws.rds.db-instance/engine              "mysql"
                :aws.rds.db-instance/engine-version      "8.0.35"
                :aws.rds.db-instance/class               "db.t3.micro"
                :aws.rds.db-instance/multi-az?           false
                :aws.rds.db-instance/publicly-accessible? false
                :aws.rds.db-instance/encrypted?          true}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn :last-rds-id db-id)))

(When "I retract the RDS instance {string} from Datomic" [state db-id]
  (let [conn (:datomic-conn state)
        db   (d/db conn)
        eid  (d/q '[:find ?e . :in $ ?id :where [?e :aws.rds.db-instance/id ?id]] db db-id)]
    (when eid @(d/transact conn [[:db/retractEntity eid]])))
  state)

(Then "the RDS instance {string} exists in Datomic" [state db-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.rds.db-instance/id ?id]] db db-id)]
    (assert eid (str "RDS instance '" db-id "' not found in Datomic")))
  state)

(Then "the RDS instance {string} has provider :aws and label {string}" [state db-id label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.rds.db-instance/id ?id]] db db-id)]
    (assert eid)
    (assert (= :aws  (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid)))
    (assert (= label (d/q '[:find ?v . :in $ ?e :where [?e :heron/label ?v]] db eid))))
  state)

(Then "the RDS instance {string} has heron id {string}" [state db-id expected-hid]
  (let [db  (d/db (:datomic-conn state))
        hid (d/q '[:find ?v . :in $ ?id :where [?e :aws.rds.db-instance/id ?id]
                                                [?e :heron/id ?v]] db db-id)]
    (assert (= expected-hid hid) (str "Expected heron/id '" expected-hid "', got '" hid "'")))
  state)

(Then "the RDS instance {string} does not exist in the current Datomic db" [state db-id]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?id :where [?e :aws.rds.db-instance/id ?id]] db db-id)]
    (assert (nil? eid) (str "RDS instance '" db-id "' should have been retracted")))
  state)
