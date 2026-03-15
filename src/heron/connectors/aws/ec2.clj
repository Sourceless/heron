(ns heron.connectors.aws.ec2
  (:require [heron.connector :as connector :refer [IConnector]]
            [cognitect.aws.client.api :as aws]))

(def schema
  "Datomic attributes for EC2 resources. Pass to sync/ensure-schema! before ingesting."
  (into connector/base-schema
        [;; EC2 Instance
         {:db/ident       :aws.ec2.instance/id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "EC2 instance ID (e.g. i-0abc123)."}

         {:db/ident       :aws.ec2.instance/type
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "EC2 instance type (e.g. t3.micro)."}

         {:db/ident       :aws.ec2.instance/state
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "EC2 instance state name (running, stopped, terminated, etc.)."}

         {:db/ident       :aws.ec2.instance/ami
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "AMI ID used to launch the instance."}

         {:db/ident       :aws.ec2.instance/vpc-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "VPC ID the instance belongs to."}

         {:db/ident       :aws.ec2.instance/subnet-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Subnet ID the instance is launched into."}

         ;; Security Group
         {:db/ident       :aws.ec2.security-group/group-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Security group ID (e.g. sg-0abc123)."}

         {:db/ident       :aws.ec2.security-group/name
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Security group name."}

         {:db/ident       :aws.ec2.security-group/vpc-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "VPC ID the security group belongs to."}

         {:db/ident       :aws.ec2.security-group/ingress-rules
          :db/valueType   :db.type/ref
          :db/cardinality :db.cardinality/many
          :db/isComponent true
          :db/doc         "Inbound rules as component entities."}

         {:db/ident       :aws.ec2.security-group/egress-rules
          :db/valueType   :db.type/ref
          :db/cardinality :db.cardinality/many
          :db/isComponent true
          :db/doc         "Outbound rules as component entities."}

         ;; Security group rule (shared by ingress and egress components)
         {:db/ident       :aws.ec2.sg-rule/protocol
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "IP protocol: tcp, udp, icmp, or -1 (all)."}

         {:db/ident       :aws.ec2.sg-rule/from-port
          :db/valueType   :db.type/long
          :db/cardinality :db.cardinality/one
          :db/doc         "Start of port range. -1 for all-traffic rules."}

         {:db/ident       :aws.ec2.sg-rule/to-port
          :db/valueType   :db.type/long
          :db/cardinality :db.cardinality/one
          :db/doc         "End of port range. -1 for all-traffic rules."}

         {:db/ident       :aws.ec2.sg-rule/cidr
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "IPv4 CIDR block (e.g. 0.0.0.0/0)."}

         ;; VPC
         {:db/ident       :aws.ec2.vpc/vpc-id
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "VPC ID (e.g. vpc-0abc123)."}

         {:db/ident       :aws.ec2.vpc/cidr
          :db/valueType   :db.type/string
          :db/cardinality :db.cardinality/one
          :db/doc         "Primary IPv4 CIDR block of the VPC."}

         {:db/ident       :aws.ec2.vpc/default?
          :db/valueType   :db.type/boolean
          :db/cardinality :db.cardinality/one
          :db/doc         "True if this is the account default VPC."}]))

(defn- make-client [{:keys [region endpoint-override] :or {region "us-east-1"}}]
  (aws/client (cond-> {:api :ec2 :region region}
                endpoint-override (assoc :endpoint-override endpoint-override))))

;; ── Instances ─────────────────────────────────────────────────────────────────

(defn- instance-name [instance]
  (some->> (:Tags instance)
           (filter #(= "Name" (:Key %)))
           first
           :Value))

(defn- instance->entity [account-id instance]
  (let [{:keys [InstanceId InstanceType ImageId VpcId SubnetId State]} instance
        state-name (:Name State)
        label      (or (instance-name instance) InstanceId)]
    {:heron/id                 (str "aws:" account-id ":ec2:instance:" InstanceId)
     :heron/provider           :aws
     :heron/label              label
     :aws.ec2.instance/id      InstanceId
     :aws.ec2.instance/type    InstanceType
     :aws.ec2.instance/state   state-name
     :aws.ec2.instance/ami     ImageId
     :aws.ec2.instance/vpc-id  (or VpcId "")
     :aws.ec2.instance/subnet-id (or SubnetId "")}))

(defrecord EC2InstancesConnector [client account-id]
  IConnector
  (run [_]
    (let [response (aws/invoke client {:op :DescribeInstances})]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "EC2 DescribeInstances failed" {:response response})))
      (->> (:Reservations response)
           (mapcat :Instances)
           (mapv (partial instance->entity account-id))))))

;; ── Security Groups ───────────────────────────────────────────────────────────

(defn- permission->rules [perm]
  (let [protocol  (:IpProtocol perm)
        from-port (get perm :FromPort -1)
        to-port   (get perm :ToPort -1)]
    (for [range (:IpRanges perm)]
      {:aws.ec2.sg-rule/protocol  protocol
       :aws.ec2.sg-rule/from-port from-port
       :aws.ec2.sg-rule/to-port   to-port
       :aws.ec2.sg-rule/cidr      (:CidrIp range)})))

(defn- sg->entity [account-id {:keys [GroupId GroupName VpcId IpPermissions IpPermissionsEgress]}]
  {:heron/id                            (str "aws:" account-id ":ec2:security-group:" GroupId)
   :heron/provider                      :aws
   :heron/label                         GroupName
   :aws.ec2.security-group/group-id     GroupId
   :aws.ec2.security-group/name         GroupName
   :aws.ec2.security-group/vpc-id       (or VpcId "")
   :aws.ec2.security-group/ingress-rules (vec (mapcat permission->rules IpPermissions))
   :aws.ec2.security-group/egress-rules  (vec (mapcat permission->rules IpPermissionsEgress))})

(defrecord EC2SecurityGroupsConnector [client account-id]
  IConnector
  (run [_]
    (let [response (aws/invoke client {:op :DescribeSecurityGroups})]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "EC2 DescribeSecurityGroups failed" {:response response})))
      (mapv (partial sg->entity account-id) (:SecurityGroups response)))))

;; ── VPCs ──────────────────────────────────────────────────────────────────────

(defn- vpc->entity [account-id {:keys [VpcId CidrBlock IsDefault]}]
  {:heron/id             (str "aws:" account-id ":ec2:vpc:" VpcId)
   :heron/provider       :aws
   :heron/label          VpcId
   :aws.ec2.vpc/vpc-id   VpcId
   :aws.ec2.vpc/cidr     CidrBlock
   :aws.ec2.vpc/default? (boolean IsDefault)})

(defrecord EC2VpcsConnector [client account-id]
  IConnector
  (run [_]
    (let [response (aws/invoke client {:op :DescribeVpcs})]
      (when (:cognitect.anomalies/category response)
        (throw (ex-info "EC2 DescribeVpcs failed" {:response response})))
      (mapv (partial vpc->entity account-id) (:Vpcs response)))))

;; ── Factories ─────────────────────────────────────────────────────────────────

(defn make-instances-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [account-id] :as opts}]
  (->EC2InstancesConnector (make-client opts) account-id))

(defn make-security-groups-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [account-id] :as opts}]
  (->EC2SecurityGroupsConnector (make-client opts) account-id))

(defn make-vpcs-connector
  "opts: {:region str, :account-id str, :endpoint-override {:protocol :http :hostname str :port int}}"
  [{:keys [account-id] :as opts}]
  (->EC2VpcsConnector (make-client opts) account-id))
