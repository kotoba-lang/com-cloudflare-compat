(ns cloudflare.oracle-call-record-test
  "T5.2 call-record + T6.4 require-ready surface for com-cloudflare-compat."
  (:require [clojure.test :refer [deftest is testing]]
            [cloudflare.kotoba.oracle :as oracle]
            [cloudflare.main :as main]))

(deftest map->args-and-call-record
  (is (= ["zones"]
         (oracle/map->args {:plural "zones"} [[:plural :string]])))
  (when (oracle/ready? :compat)
    (let [via-call (oracle/call :compat 'collection-path ["zones"])
          via-rec (oracle/call-record :compat 'collection-path
                                      {:plural "zones"}
                                      [[:plural :string]])
          via-host (main/collection-path "zones")]
      (is (= via-call via-rec via-host))
      (is (= "/v1/zones" via-host)))
    (is (= "/v1/zones/{id}" (main/item-path "zones")))
    (is (string? (main/fact-attr "Zone" "name")))
    (is (= 20 (main/clamp-limit 0)))
    (is (= 100 (main/clamp-limit 999)))
    (is (= 7 (main/as-int "7")))
    (is (true? (main/as-bool "true")))
    (is (false? (main/as-bool "no")))
    (let [id (main/new-id "cloudfla_zon")]
      (is (string? id))
      (is (re-find #"^cloudfla_zon_" id)))))

(deftest require-ready-constants
  (when (oracle/ready? :compat)
    (is (= "cloudflare" main/ns-prefix))
    (is (= "L5" main/tier))
    (is (= 20 main/default-limit))
    (is (= 100 main/max-limit))
    (let [[body status] (main/healthz)]
      (is (= "ok" (:status body)))
      (is (= "cloudflare-compat" (:actor body)))
      (is (= 200 status)))))
