;; cloudflare.kotoba.oracle — product-shell loader for precompiled pure KIR.
;;
;; Authority dual-source (murakumo/com-cloudflare form):
;;   1. SSoT: kotoba/compat_core.kotoba
;;   2. Artifact: resources/cloudflare/oracle/compat_core.kir.edn
;;   3. Host public API delegates pure helpers here
;;
;; CLJS load (optional, ADR 0003):
;;   - register-kir! — inject pre-parsed KIR
;;   - set-resource-loader! — custom (fn [path] → string)
;;   - nbb/node default: read resources/<path> from process.cwd()
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

(def ^:private resource-loader (atom nil))

(defn set-resource-loader!
  "Install a resource loader used by cljs/nbb when classpath io is unavailable.
  Pass nil to clear. Returns the previous loader."
  [f]
  (let [prev @resource-loader]
    (reset! resource-loader f)
    prev))

(defn register-kir!
  "Inject a pre-parsed KIR document for `oracle-id`."
  [oracle-id kir]
  (swap! kir-cache assoc oracle-id kir)
  kir)

(defn clear-cache!
  "Drop all cached KIR documents (does not clear resource-loader)."
  []
  (reset! kir-cache {}))

#?(:cljs
   (defn- node-resource-slurp
     [path]
     (try
       (let [fs (js/require "fs")
             path-mod (js/require "path")
             cwd (str (.cwd js/process))
             full (.resolve path-mod cwd "resources" path)]
         (when (.existsSync fs full)
           (.readFileSync fs full "utf8")))
       (catch :default _ nil))))

(defn- read-resource [path]
  #?(:clj
     (if-let [url (io/resource path)]
       (slurp url)
       (throw (ex-info "kotoba oracle KIR resource missing"
                       {:path path
                        :hint "run: clojure -M:oracle-gen"})))
     :cljs
     (let [from-loader (when-let [f @resource-loader] (f path))
           from-node (when (nil? from-loader) (node-resource-slurp path))
           text (or from-loader from-node)]
       (if text
         text
         (throw (ex-info "kotoba oracle resource load failed on cljs"
                         {:path path
                          :hint "set-resource-loader!, register-kir!, or run nbb from repo root with resources/"}))))))

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

(defn as-i64
  [n]
  #?(:clj (long n)
     :cljs (js/BigInt n)))

(defn i64->host
  [v]
  #?(:clj (long v)
     :cljs (js/Number v)))

(defn call
  "Execute a pure export on the precompiled oracle."
  [oracle-id export args]
  (let [kir (load-kir oracle-id)
        fn-name (if (symbol? export) export (symbol (name export)))]
    (ir/execute kir fn-name (vec args))))

(defn catalog-ids [] (keys catalog))
(defn catalog-count [] (count catalog))
