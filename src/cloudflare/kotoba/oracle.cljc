;; cloudflare.kotoba.oracle — product-shell loader for precompiled pure KIR.
;;
;; Authority dual-source (murakumo/com-cloudflare form):
;;   1. SSoT: kotoba/compat_core.kotoba
;;   2. Artifact: resources/cloudflare/oracle/compat_core.kir.edn
;;   3. Host public API delegates pure helpers here
;;
;; Compiler stays test-only. Production needs only kotoba-kir + resources.

(ns cloudflare.kotoba.oracle
  "Load precompiled kotoba KIR pure artifacts and execute exports."
  (:require [clojure.edn :as edn]
            [kotoba.kir :as ir]
            #?(:clj [clojure.java.io :as io])))

(def ^:private catalog
  {:compat "cloudflare/oracle/compat_core.kir.edn"})

(def ^:private kir-cache (atom {}))

(defn- read-resource [path]
  #?(:clj
     (if-let [url (io/resource path)]
       (slurp url)
       (throw (ex-info "kotoba oracle KIR resource missing"
                       {:path path
                        :hint "run: clojure -M:oracle-gen"})))
     :cljs
     (throw (ex-info "kotoba oracle resource load is JVM-only in this slice"
                     {:path path}))))

(defn load-kir [oracle-id]
  (if-let [hit (get @kir-cache oracle-id)]
    hit
    (let [path (or (get catalog oracle-id)
                   (throw (ex-info "unknown kotoba oracle id"
                                   {:oracle-id oracle-id
                                    :known (keys catalog)})))
          kir (edn/read-string (read-resource path))]
      (swap! kir-cache assoc oracle-id kir)
      kir)))

(defn ready? [oracle-id]
  (try
    (boolean (load-kir oracle-id))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn call
  "Execute a pure export on the precompiled oracle."
  [oracle-id export args]
  (let [kir (load-kir oracle-id)
        fn-name (if (symbol? export) export (symbol (name export)))]
    (ir/execute kir fn-name (vec args))))

(defn catalog-ids [] (keys catalog))
(defn catalog-count [] (count catalog))
