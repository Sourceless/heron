(ns heron.steps.iam-connector-steps
  (:require [lambdaisland.cucumber.dsl    :refer [Given When Then]]
            [heron.connector              :refer [run]]
            [heron.sync                   :as sync]
            [heron.connectors.aws.iam     :as iam]
            [datomic.api                  :as d]))

(def ^:private test-db-uri
  "datomic:sql://heron-test?jdbc:postgresql://localhost:5432/datomic?user=datomic&password=datomic")

(defn- fresh-conn! []
  (d/create-database test-db-uri)
  (let [conn (d/connect test-db-uri)]
    (sync/ensure-schema! conn iam/schema)
    conn))

(def ^:private localstack-opts
  {:region            "us-east-1"
   :account-id        "000000000000"
   :endpoint-override {:protocol :http
                       :hostname (or (System/getenv "LOCALSTACK_HOST") "localhost")
                       :port     4566}})

;; --- Roles ---

(When "I run the IAM roles connector against LocalStack" [state]
  (assoc state :iam-role-entities (run (iam/make-roles-connector localstack-opts))))

(When "I ingest the IAM roles into Datomic" [state]
  (let [conn (or (:datomic-conn state) (fresh-conn!))]
    (sync/ingest! conn (:iam-role-entities state))
    (assoc state :datomic-conn conn)))

(Given "a phantom IAM role {string} is present in Datomic" [state role-name]
  (let [conn (or (:datomic-conn state) (fresh-conn!))
        phantom {:heron/id          (str "aws:000000000000:iam:role:" role-name)
                 :heron/provider    :aws
                 :heron/label       role-name
                 :aws.iam.role/name role-name}]
    @(d/transact conn [phantom])
    (assoc state :datomic-conn conn)))

(When "I retract absent IAM role entities from Datomic" [state]
  (let [conn        (:datomic-conn state)
        current-ids (map :heron/id (:iam-role-entities state))
        db-before   (d/db conn)]
    (sync/retract-absent! conn :aws.iam.role/name current-ids)
    (assoc state :db-before-retraction db-before)))

(Then "the IAM role {string} exists in Datomic" [state role-name]
  (let [db (d/db (:datomic-conn state))]
    (assert (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.role/name ?n]] db role-name)
            (str "IAM role '" role-name "' not found in Datomic")))
  state)

(Then "the IAM role {string} does not exist in the current Datomic db" [state role-name]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.role/name ?n]] db role-name)]
    (assert (nil? eid) (str "IAM role '" role-name "' should have been retracted")))
  state)

(Then "the IAM role {string} is visible in Datomic as-of before the retraction" [state role-name]
  (let [db-before (:db-before-retraction state)
        eid       (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.role/name ?n]] db-before role-name)]
    (assert eid (str "IAM role '" role-name "' should be visible as-of before retraction")))
  state)

(Then "the IAM role {string} has provider :aws and label {string}" [state role-name label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.role/name ?n]] db role-name)]
    (assert eid (str "IAM role '" role-name "' not found"))
    (assert (= :aws  (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid))
            "Expected :heron/provider :aws")
    (assert (= label (d/q '[:find ?v . :in $ ?e :where [?e :heron/label ?v]] db eid))
            (str "Expected :heron/label '" label "'")))
  state)

;; --- Users ---

(When "I run the IAM users connector against LocalStack" [state]
  (assoc state :iam-user-entities (run (iam/make-users-connector localstack-opts))))

(When "I ingest the IAM users into Datomic" [state]
  (let [conn (or (:datomic-conn state) (fresh-conn!))]
    (sync/ingest! conn (:iam-user-entities state))
    (assoc state :datomic-conn conn)))

(Given "a phantom IAM user {string} is present in Datomic" [state user-name]
  (let [conn (or (:datomic-conn state) (fresh-conn!))
        phantom {:heron/id          (str "aws:000000000000:iam:user:" user-name)
                 :heron/provider    :aws
                 :heron/label       user-name
                 :aws.iam.user/name user-name}]
    @(d/transact conn [phantom])
    (assoc state :datomic-conn conn)))

(When "I retract absent IAM user entities from Datomic" [state]
  (let [conn        (:datomic-conn state)
        current-ids (map :heron/id (:iam-user-entities state))
        db-before   (d/db conn)]
    (sync/retract-absent! conn :aws.iam.user/name current-ids)
    (assoc state :db-before-retraction db-before)))

(Then "the IAM user {string} exists in Datomic" [state user-name]
  (let [db (d/db (:datomic-conn state))]
    (assert (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.user/name ?n]] db user-name)
            (str "IAM user '" user-name "' not found in Datomic")))
  state)

(Then "the IAM user {string} does not exist in the current Datomic db" [state user-name]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.user/name ?n]] db user-name)]
    (assert (nil? eid) (str "IAM user '" user-name "' should have been retracted")))
  state)

(Then "the IAM user {string} is visible in Datomic as-of before the retraction" [state user-name]
  (let [db-before (:db-before-retraction state)
        eid       (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.user/name ?n]] db-before user-name)]
    (assert eid (str "IAM user '" user-name "' should be visible as-of before retraction")))
  state)

(Then "the IAM user {string} has provider :aws and label {string}" [state user-name label]
  (let [db  (d/db (:datomic-conn state))
        eid (d/q '[:find ?e . :in $ ?n :where [?e :aws.iam.user/name ?n]] db user-name)]
    (assert eid (str "IAM user '" user-name "' not found"))
    (assert (= :aws  (d/q '[:find ?v . :in $ ?e :where [?e :heron/provider ?v]] db eid))
            "Expected :heron/provider :aws")
    (assert (= label (d/q '[:find ?v . :in $ ?e :where [?e :heron/label ?v]] db eid))
            (str "Expected :heron/label '" label "'")))
  state)
