;; cloudflare.kotoba.oracle — product-shell loader for precompiled pure KIR.
;;
;; Authority product-shell pattern (W6 + T5.2 call-record + T6.4 mirror-delete):
;;   1. SSoT: kotoba/compat_core.kotoba
;;   2. Artifact: resources/cloudflare/oracle/compat_core.kir.edn
;;   3. Host pure helpers require shipped KIR (require-ready!); no soft mirrors
;;
;; CLJS load (ADR 0003 + T6.4 preload):
;;   - register-kir! / set-resource-loader! / preload! / preload-catalog!
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
  "Execute a pure export on the precompiled oracle.

  Prefer `call-record` when the host boundary is a map (T5.1 structural args)."
  [oracle-id export args]
  (let [kir (load-kir oracle-id)
        fn-name (if (symbol? export) export (symbol (name export)))]
    (ir/execute kir fn-name (vec args))))

(defn option-of
  "Host nil → option none; non-nil → option some (Product Value ABI v1)."
  [type value]
  (if (nil? value)
    [type false]
    [type true value]))

(defn option-string
  "Optional string: nil → none; otherwise some (including empty string)."
  [s]
  (option-of [:option :string] (when (some? s) (str s))))

(defn option-i64
  "Optional i64: nil → none; otherwise some long/BigInt."
  [n]
  (if (nil? n)
    [[:option :i64] false]
    [[:option :i64] true (as-i64 n)]))

(defn bool->host
  "KIR :bool / 0-1 word → host boolean."
  [v]
  (cond
    (true? v) true
    (false? v) false
    (number? v) (not (zero? #?(:clj (long v) :cljs v)))
    :else (boolean v)))

(defn project-field
  "Project one host map field into a guest ABI payload (T5.2).

  kind: :string :i64 :bool :option-string :option-i64 :raw"
  [kind v]
  (case kind
    :string (str (or v ""))
    :i64 (as-i64 v)
    :bool (boolean v)
    :option-string (option-string v)
    :option-i64 (option-i64 v)
    :raw v
    (if (nil? kind) v v)))

(defn map->args
  "Structural host map to ordered guest arg vector (T5.2 positional projection)."
  [m field-specs]
  (when-not (map? m)
    (throw (ex-info "map->args requires a host map"
                    {:phase :oracle-call-record :got (type m)})))
  (when-not (sequential? field-specs)
    (throw (ex-info "map->args requires field-specs sequential"
                    {:phase :oracle-call-record})))
  (mapv (fn [spec]
          (if (vector? spec)
            (let [[k kind] spec]
              (project-field kind (get m k)))
            (get m spec)))
        field-specs))

(defn call-record
  "Call an oracle export with a structural host map (T5.2).

  Projects `host-map` through `field-specs` into the positional guest ABI,
  then `call`. When the guest export takes a single native record, build it
  with `record` and pass `[[:in :raw]]`."
  [oracle-id export host-map field-specs]
  (call oracle-id export (map->args host-map field-specs)))

(defn record
  "Build a native guest record argument for `call` (T5.2 / T5.3).

  `schema` is `[:record :ns/name [[:field type] …]]`; `host-map` supplies fields."
  [schema host-map]
  (let [fields (nth schema 2)]
    (into [schema]
          (map (fn [[field field-type]]
                 (let [v (get host-map field)]
                   (when-not (contains? host-map field)
                     (throw (ex-info "record field missing for guest schema"
                                     {:schema (second schema) :field field})))
                   (cond
                     (= field-type :i64) (as-i64 v)
                     (= field-type :string) (str v)
                     (= field-type :bool) (boolean v)
                     (= field-type [:option :i64]) (option-i64 v)
                     (= field-type [:option :string]) (option-string v)
                     :else v))))
          fields)))

(defn catalog-ids [] (keys catalog))
(defn catalog-count [] (count catalog))

(defn require-ready!
  "Throw unless `oracle-id` is loadable (T6.4 — no soft mirror fallback)."
  [oracle-id]
  (when-not (ready? oracle-id)
    (throw (ex-info "kotoba oracle not ready (T6.4 requires shipped KIR)"
                    {:oracle-id oracle-id
                     :hint "preload-catalog! / register-kir! / set-resource-loader!, or run nbb from repo root with resources/"})))
  true)

(defn preload!
  "Load each oracle-id into the cache. nbb/browser entrypoints call once."
  [oracle-ids]
  (doseq [id oracle-ids]
    (load-kir id))
  (count oracle-ids))

(defn preload-catalog!
  "Load every catalog id into the cache."
  []
  (preload! (keys catalog)))
