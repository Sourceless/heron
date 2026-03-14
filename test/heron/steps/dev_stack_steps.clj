(ns heron.steps.dev-stack-steps
  (:require [lambdaisland.cucumber.dsl :refer [Given When Then]]
            [next.jdbc :as jdbc]
            [clj-http.client :as http])
  (:import (java.net Socket)))

;; Background

(Given "the dev stack is running" [state]
  ;; Assumes `docker compose up -d` was run beforehand.
  ;; Service healthchecks in docker-compose.yml are the readiness gate.
  state)

;; PostgreSQL scenarios

(When "I query PostgreSQL for existing tables" [state]
  (let [ds (jdbc/get-datasource {:dbtype   "postgresql"
                                 :host     (or (System/getenv "POSTGRES_HOST") "localhost")
                                 :port     5432
                                 :dbname   "datomic"
                                 :user     (or (System/getenv "POSTGRES_USER") "datomic")
                                 :password (or (System/getenv "POSTGRES_PASSWORD") "datomic")})
        tables (jdbc/execute! ds ["SELECT tablename FROM pg_tables WHERE schemaname = 'public'"])]
    (assoc state :tables (set (map :pg_tables/tablename tables)))))

(Then "the {string} table exists" [state table-name]
  (assert (contains? (:tables state) table-name)
          (str "Expected table '" table-name "' to exist, got: " (:tables state)))
  state)

;; Datomic transactor scenarios

(When "I connect to the Datomic transactor on port {int}" [state port]
  (let [result (try
                 (with-open [_ (Socket. "localhost" port)]
                   {:success true})
                 (catch Exception e
                   {:success false :error (.getMessage e)}))]
    (assoc state :datomic-connection result)))

(Then "the connection succeeds" [state]
  (assert (get-in state [:datomic-connection :success])
          (str "Expected Datomic connection to succeed: "
               (get-in state [:datomic-connection :error])))
  state)

;; LocalStack scenarios

(When "I call the LocalStack health endpoint" [state]
  (let [host     (or (System/getenv "LOCALSTACK_HOST") "localhost")
        response (http/get (str "http://" host ":4566/_localstack/health")
                           {:as :json :throw-exceptions false})]
    (assoc state :localstack-response response)))

(Then "the response status is {int}" [state status]
  (assert (= status (get-in state [:localstack-response :status]))
          (str "Expected status " status ", got: "
               (get-in state [:localstack-response :status])))
  state)

(Then "the {string} service status is {string}" [state service expected-status]
  (let [body   (get-in state [:localstack-response :body])
        ;; Health response: {"services": {"s3": "running", ...}}
        actual (get-in body [:services (keyword service)])]
    (assert (= expected-status actual)
            (str "Expected " service " status '" expected-status "', got: '" actual "'"))
    state))
