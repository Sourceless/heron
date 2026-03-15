(ns heron.steps.lambda-connector-steps
  (:require [lambdaisland.cucumber.dsl    :refer [Given When Then]]
            [heron.sync                   :as sync]
            [heron.connectors.aws.lambda  :as lambda]
            [datomic.api                  :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test-lambda?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/delete-database test-db-uri)
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn lambda/schema)
    conn))

(Given "a synthetic Lambda function {string} is present in Datomic" [state fn-name]
  (let [conn   (or (:datomic-conn state) (fresh-conn!))
        entity {:heron/id                        (str "aws:000000000000:lambda:function:" fn-name)
                :heron/provider                  :aws
                :heron/label                     fn-name
                :aws.lambda.function/name        fn-name
                :aws.lambda.function/runtime     "python3.12"
                :aws.lambda.function/handler     "index.handler"
                :aws.lambda.function/role        "arn:aws:iam::000000000000:role/lambda-role"
                :aws.lambda.function/timeout     30
                :aws.lambda.function/memory-size 256
                :aws.lambda.function/env-var-names ["DB_HOST" "LOG_LEVEL"]}]
    @(d/transact conn [entity])
    (assoc state :datomic-conn conn)))

(When "I retract the Lambda function {string} from Datomic" [state fn-name]
  (let [conn (:datomic-conn state)
        db   (d/db conn)
        eid  (d/q '[:find ?e . :in $ ?n :where [?e :aws.lambda.function/name ?n]] db fn-name)]
    (when eid @(d/transact conn [[:db/retractEntity eid]])))
  state)

(Then "the Lambda function {string} exists in Datomic" [state fn-name]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.lambda.function/name ?n]] db fn-name)]
    (assert eid (str "Lambda function '" fn-name "' not found in Datomic")))
  state)

(Then "the Lambda function {string} has provider :aws and label {string}" [state fn-name label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.lambda.function/name ?n]] db fn-name)]
    (assert eid)
    (assert (= :aws  (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid)))
    (assert (= label (d/q '[:find ?v . :in $ ?e :where [?e :heron/label ?v]] db eid))))
  state)

(Then "the Lambda function {string} has heron id {string}" [state fn-name expected-hid]
  (let [db  (d/db (:datomic-conn state))
        hid (d/q '[:find ?v . :in $ ?n :where [?e :aws.lambda.function/name ?n]
                                               [?e :heron/id ?v]] db fn-name)]
    (assert (= expected-hid hid) (str "Expected heron/id '" expected-hid "', got '" hid "'")))
  state)

(Then "the Lambda function {string} does not exist in the current Datomic db" [state fn-name]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.lambda.function/name ?n]] db fn-name)]
    (assert (nil? eid) (str "Lambda function '" fn-name "' should have been retracted")))
  state)
