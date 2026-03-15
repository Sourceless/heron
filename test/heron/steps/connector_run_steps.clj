(ns heron.steps.connector-run-steps
  (:require [lambdaisland.cucumber.dsl :refer [When Then]]
            [heron.connector          :as connector :refer [run]]
            [heron.sync               :as sync]
            [heron.connectors.aws.s3  :as s3]
            [datomic.api              :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/delete-database test-db-uri)
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn s3/schema)
    (sync/ensure-schema! conn connector/connector-run-schema)
    conn))

(def ^:private localstack-opts
  {:region            "us-east-1"
   :account-id        "000000000000"
   :endpoint-override {:protocol :http
                       :hostname (or (System/getenv "LOCALSTACK_HOST") "localhost")
                       :port     4566}})

(When "I run the S3 connector and record the run" [state]
  (let [conn       (or (:datomic-conn state) (fresh-conn!))
        started-at (java.util.Date.)
        entities   (run (s3/make-connector localstack-opts))]
    (sync/ingest! conn entities)
    (let [finished-at (java.util.Date.)]
      (sync/record-run! conn {:provider       :aws
                              :connector      "s3"
                              :started-at     started-at
                              :finished-at    finished-at
                              :resource-count (count entities)}))
    (assoc state :datomic-conn conn :connector-run-connector "s3")))

(Then "a ConnectorRun entity exists for connector {string}" [state connector-name]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?c :where [?e :heron.connector-run/connector ?c]]
                 db connector-name)]
    (assert eid (str "No ConnectorRun found for connector '" connector-name "'")))
  state)

(Then "the ConnectorRun for {string} has provider :aws" [state connector-name]
  (let [db       (d/db (:datomic-conn state))
        provider (d/q '[:find ?p . :in $ ?c
                        :where [?e :heron.connector-run/connector ?c]
                               [?e :heron.connector-run/provider ?p]]
                      db connector-name)]
    (assert (= :aws provider)
            (str "Expected provider :aws, got: " provider)))
  state)

(Then "the ConnectorRun for {string} has a resource-count greater than 0" [state connector-name]
  (let [db    (d/db (:datomic-conn state))
        count (d/q '[:find ?n . :in $ ?c
                     :where [?e :heron.connector-run/connector ?c]
                            [?e :heron.connector-run/resource-count ?n]]
                   db connector-name)]
    (assert (and (number? count) (pos? count))
            (str "Expected resource-count > 0, got: " count)))
  state)

(Then "the ConnectorRun for {string} has started-at before finished-at" [state connector-name]
  (let [db             (d/db (:datomic-conn state))
        [started finished] (first (d/q '[:find ?s ?f :in $ ?c
                                         :where [?e :heron.connector-run/connector ?c]
                                                [?e :heron.connector-run/started-at ?s]
                                                [?e :heron.connector-run/finished-at ?f]]
                                       db connector-name))]
    (assert (and started finished (.before ^java.util.Date started ^java.util.Date finished))
            (str "Expected started-at before finished-at, got: " started " / " finished)))
  state)

(Then "there are {int} ConnectorRun entities for connector {string}" [state expected-count connector-name]
  (let [db     (d/db (:datomic-conn state))
        eids   (d/q '[:find [?e ...] :in $ ?c :where [?e :heron.connector-run/connector ?c]]
                    db connector-name)
        actual (count eids)]
    (assert (= expected-count actual)
            (str "Expected " expected-count " ConnectorRun entities for '" connector-name "', found: " actual)))
  state)
