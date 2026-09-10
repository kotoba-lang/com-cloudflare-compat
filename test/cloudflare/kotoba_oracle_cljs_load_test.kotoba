(ns cloudflare.kotoba-oracle-cljs-load-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cloudflare.kotoba.oracle :as oracle]
            [cloudflare.main :as m]
            [kotoba.kir :as ir]))

(deftest register-kir-bypasses-resource-read
  (oracle/clear-cache!)
  (let [live (edn/read-string
              (slurp (io/resource "cloudflare/oracle/compat_core.kir.edn")))]
    (oracle/register-kir! :compat live)
    (is (oracle/ready? :compat))
    (is (= (ir/execute live 'ns-prefix [])
           (oracle/call :compat 'ns-prefix [])))
    (oracle/clear-cache!)
    (is (oracle/ready? :compat))))

(deftest set-resource-loader-injects-edn-text
  (oracle/clear-cache!)
  (let [path "cloudflare/oracle/compat_core.kir.edn"
        text (slurp (io/resource path))
        prev (oracle/set-resource-loader!
              (fn [p] (when (= p path) text)))]
    (try
      (is (oracle/ready? :compat))
      (is (= 20 (oracle/i64->host (oracle/call :compat 'default-limit []))))
      (finally
        (oracle/set-resource-loader! prev)
        (oracle/clear-cache!)))))

(deftest pure-helpers-use-oracle-when-ready
  (is (oracle/ready? :compat))
  (is (= "cloudflare" m/ns-prefix))
  (is (= "/v1/zones" (m/collection-path "zones")))
  (is (= 20 (m/clamp-limit 0)))
  (is (= 201 (second (m/handle-create (m/fresh-store) "Zone"
                                      {:name "ex.com" :status "active"})))))
