;; W6 product-shell oracle authority for com-cloudflare-compat.

(ns cloudflare.kotoba-oracle-authority-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloudflare.main :as m]
            [cloudflare.kotoba.oracle :as oracle]
            [cloudflare.kotoba-oracle-gen :as gen]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(deftest oracle-catalog-ready
  (is (= 1 (oracle/catalog-count)))
  (is (oracle/ready? :compat))
  (is (some #{:compat} (oracle/catalog-ids))))

(deftest product-shell-compat-uses-oracle
  (testing "constants"
    (is (= "cloudflare" m/ns-prefix))
    (is (= "L5" m/tier))
    (is (= 20 m/default-limit))
    (is (= 100 m/max-limit)))
  (testing "coerce / clamp"
    (is (= 42 (m/as-int "42")))
    (is (= 0 (m/as-int "x")))
    (is (true? (m/as-bool "true")))
    (is (false? (m/as-bool "no")))
    (is (= 20 (m/clamp-limit 0)))
    (is (= 10 (m/clamp-limit 10)))
    (is (= 100 (m/clamp-limit 500))))
  (testing "paths / facts / ids"
    (is (= "/v1/zones" (m/collection-path "zones")))
    (is (= "/v1/zones/{id}" (m/item-path "zones")))
    (is (= "cloudflare.Zone/name" (m/fact-attr "Zone" "name")))
    (is (str/starts-with? (m/new-id "cloudfla_zon") "cloudfla_zon_")))
  (testing "handlers use oracle status codes"
    (let [[_ st] (m/handle-create (m/fresh-store) "Zone"
                                  {:name "ex.com" :status "active"})]
      (is (= 201 st)))
    (let [[body st] (m/handle-list (m/fresh-store) "Zone" {})]
      (is (= 200 st))
      (is (= "list" (:object body))))
    (let [[_ st] (m/healthz)]
      (is (= 200 st)))))

(deftest oracle-call-matches-live-compile
  (let [live (:kir (compiler/compile-source (slurp "kotoba/compat_core.kotoba")
                                            :wasm32-kotoba-v1 {}))]
    (is (= (ir/execute live 'ns-prefix [])
           (oracle/call :compat 'ns-prefix [])))
    (is (= (ir/execute live 'as-int-string ["42"])
           (oracle/call :compat 'as-int-string ["42"])))
    (is (= (ir/execute live 'clamp-limit [0])
           (oracle/call :compat 'clamp-limit [0])))
    (is (= (ir/execute live 'collection-path ["zones"])
           (oracle/call :compat 'collection-path ["zones"])))
    (is (= (ir/execute live 'fact-attr ["Zone" "name"])
           (oracle/call :compat 'fact-attr ["Zone" "name"])))))

(deftest precompiled-kir-does-not-drift
  (let [live (:kir (compiler/compile-source (slurp "kotoba/compat_core.kotoba")
                                            :wasm32-kotoba-v1 {}))
        shipped (edn/read-string
                 (slurp (io/resource "cloudflare/oracle/compat_core.kir.edn")))]
    (is (= live shipped)
        "compat_core KIR drift — run: clojure -M:oracle-gen")))

(deftest gen-compile-kir-roundtrip
  (let [kir (gen/compile-kir "kotoba/compat_core.kotoba")]
    (is (map? kir))
    (is (= "cloudflare" (ir/execute kir 'ns-prefix [])))))
