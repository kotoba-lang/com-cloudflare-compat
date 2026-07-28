(ns cloudflare.main
  "Kotodama WASM entrypoint for the Cloudflare clean-room actor (L5) — Clojure port.

  L5 production surface: CRUD + pagination + filtering + relationship
  expansion + strict validation, over a Datomic-backed Kotoba schema.

  py→cljc port of src/main.py (ADR 260607 L5 cohort). Data-driven: every
  handler is a generic fold over `entity-specs`, so the actor's whole REST
  surface is derivable from the schema/manifest. No proprietary code or
  credentials; resource shapes only.

  State lives on the kotoba Datom log: `emit-facts` produces namespaced EAVT
  facts (`cloudflare.<Entity>/<field>`); `*store*` is the in-memory materialization
  used by the contract test and by the WASM runtime before a live engine binds.

  W6 product-shell authority (ADR 0002 + ADR 0003 cljs dual-source):
  constants + coerce/path/limit pure helpers DELEGATE to precompiled
  compat_core.kir.edn when oracle loadable (JVM or cljs/nbb).
  Handlers/store/clock stay host; cljs mirrors remain fallback."
  (:require [clojure.string :as str]
            [cloudflare.kotoba.oracle :as oracle]))

(def ^:private oid :compat)

(defn- o [export args]
  (oracle/call oid export args))

(defn- oracle-ready? []
  (oracle/ready? oid))

(defn- try-oracle
  [thunk mirror-thunk]
  (if (oracle-ready?)
    (try
      (thunk)
      (catch #?(:clj Exception :cljs :default) _
        (mirror-thunk)))
    (mirror-thunk)))

(def ns-prefix
  (try-oracle
   #(o 'ns-prefix [])
   (fn [] "cloudflare")))

(def tier
  (try-oracle
   #(o 'tier [])
   (fn [] "L5")))

(def default-limit
  (try-oracle
   #(oracle/i64->host (o 'default-limit []))
   (fn [] 20)))

(def max-limit
  (try-oracle
   #(oracle/i64->host (o 'max-limit []))
   (fn [] 100)))

;; --- schema-derived entity specs (the single source the handlers fold over) ---
(def entity-specs
  [{:entity "Zone"          :plural "zones"          :id-prefix "cloudfla_zon"
    :fields [:name :status :type :paused :developmentMode]  :required [:name :status]
    :coerce {:paused :bool :developmentMode :int}  :refs {}}
   {:entity "DNSRecord"     :plural "dnsrecords"     :id-prefix "cloudfla_dns"
    :fields [:name :type :content :ttl :proxied]    :required [:name :type]
    :coerce {:ttl :int :proxied :bool}              :refs {}}
   {:entity "WorkerScript"  :plural "workerscripts"  :id-prefix "cloudfla_wor"
    :fields [:name :usageModel :createdOn]          :required [:name :usageModel]
    :coerce {}                                       :refs {}}
   {:entity "Certificate"   :plural "certificates"   :id-prefix "cloudfla_cer"
    :fields [:hosts :status :type]                  :required [:hosts :status]
    :coerce {}                                       :refs {}}
   {:entity "PageRule"      :plural "pagerules"      :id-prefix "cloudfla_pag"
    :fields [:targetUrl :status :priority]          :required [:targetUrl :status]
    :coerce {:priority :int}                         :refs {}}
   {:entity "LoadBalancer"  :plural "loadbalancers"  :id-prefix "cloudfla_loa"
    :fields [:name :enabled :proxied]               :required [:name :enabled]
    :coerce {:enabled :bool :proxied :bool}         :refs {}}])

(def entities (mapv :entity entity-specs))

(defn collection-path
  "REST collection path for a plural. Kotoba `collection-path` when ready."
  [plural]
  (try-oracle
   #(o 'collection-path [(str plural)])
   #(str "/v1/" plural)))

(defn item-path
  "REST item path template for a plural. Kotoba `item-path` when ready."
  [plural]
  (try-oracle
   #(o 'item-path [(str plural)])
   #(str "/v1/" plural "/{id}")))

(def routes
  (vec (mapcat (fn [{:keys [plural entity]}]
                 [{:method "POST"   :path (collection-path plural) :op (str "create " entity) :entity entity}
                  {:method "GET"    :path (collection-path plural) :op (str "list " entity)   :entity entity}
                  {:method "GET"    :path (item-path plural)       :op (str "get " entity)    :entity entity}
                  {:method "PATCH"  :path (item-path plural)       :op (str "update " entity) :entity entity}
                  {:method "DELETE" :path (item-path plural)       :op (str "delete " entity) :entity entity}])
               entity-specs)))

;; --- platform primitives ---
(defn now []
  #?(:clj (str (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn- rand-hex16 []
  #?(:clj (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 16)
     :cljs (subs (str/replace (str (random-uuid)) "-" "") 0 16)))

(defn new-id
  "id-prefix + '_' + hex16. Kotoba `id-with-prefix` when ready."
  [prefix]
  (let [hex (rand-hex16)]
    (try-oracle
     #(o 'id-with-prefix [(str prefix) hex])
     #(str prefix "_" hex))))

;; --- coercion ---
(defn as-int [v]
  (cond (number? v) (long v)
        (string? v)
        (try-oracle
         #(oracle/i64->host (o 'as-int-string [(str v)]))
         #(try
            #?(:clj (Long/parseLong (str/trim v))
               :cljs (let [n (js/parseInt v 10)] (if (js/isNaN n) 0 n)))
            (catch #?(:clj Exception :cljs :default) _ 0)))
        :else 0))

(defn as-float [v]
  (cond (number? v) (double v)
        (string? v) (try #?(:clj (Double/parseDouble (str/trim v)) :cljs (let [n (js/parseFloat v)] (if (js/isNaN n) 0.0 n)))
                         (catch #?(:clj Exception :cljs :default) _ 0.0))
        :else 0.0))

(defn as-bool [v]
  (cond (nil? v) false
        (boolean? v) v
        (string? v)
        (try-oracle
         #(= 1 (oracle/i64->host (o 'as-bool-string [(str v)])))
         #(contains? #{"1" "true" "yes" "on"} (str/lower-case v)))
        :else (contains? #{true} v)))

(defn coerce-field [kind v]
  (case kind :int (as-int v) :float (as-float v) :bool (as-bool v) v))

(defn clamp-limit
  "Clamp raw limit for pagination. Kotoba `clamp-limit` when ready."
  [raw]
  (try-oracle
   #(oracle/i64->host (o 'clamp-limit [(oracle/as-i64 raw)]))
   (fn []
     (let [base (if (< raw 1) default-limit raw)
           lo (if (< base 1) 1 base)]
       (if (< max-limit lo) max-limit lo)))))

(defn fact-attr
  "EAVT attribute key for entity/field. Kotoba `fact-attr` when ready."
  [entity field]
  (try-oracle
   #(o 'fact-attr [(str entity) (str field)])
   #(str ns-prefix "." entity "/" field)))

;; --- in-memory store (materializes the Datom log; live engine binds in prod) ---
(defn fresh-store [] (atom {}))
(def ^:dynamic *store* (fresh-store))

(defn emit-facts
  "EAVT facts for one record: {\"cloudflare.<Entity>/<field>\" v ...}. The datomic
  binding transacts these; the in-memory store keeps the record by id."
  [entity rec]
  (into {} (map (fn [[k v]] [(fact-attr entity (name k)) v]) rec)))

(defn persist! [store entity rec]
  (swap! store assoc-in [entity (:id rec)] rec)
  rec)

(defn query
  ([store entity] (vec (vals (get @store entity))))
  ([store entity id] (if-let [r (get-in @store [entity id])] [r] [])))

(defn retract! [store entity id] (swap! store update entity dissoc id) {:id id :deleted true})

;; --- validation ---
(defn require-fields [data fields]
  (let [missing (remove #(let [v (get data %)] (and (some? v) (not= v ""))) fields)]
    (when (seq missing)
      {:error {:message (str (try-oracle
                              #(o 'missing-required-prefix [])
                              (fn [] "Missing required fields: "))
                             (str/join ", " (map name missing)))
               :type "invalid_request_error"}})))

(defn reject-unknown [data allowed]
  (let [allowed-set (set allowed)
        extra (remove allowed-set (keys data))]
    (when (seq extra)
      {:error {:message (str (try-oracle
                              #(o 'unknown-fields-prefix [])
                              (fn [] "Unknown fields: "))
                             (str/join ", " (map name extra)))
               :type "invalid_request_error"}})))

;; --- list helpers ---
(defn apply-filters [rows params fields]
  (reduce (fn [out f]
            (let [want (get params f)]
              (if (and (some? want) (not= want ""))
                (filterv #(= (str (get % f)) (str want)) out)
                out)))
          rows fields))

(defn paginate [rows params]
  (let [raw (or (let [l (as-int (get params :limit))] (when (pos? l) l)) 0)
        limit (clamp-limit raw)
        start (get params :starting_after)
        rows (if (some? start)
               (let [ids (mapv :id rows)
                     idx #?(:clj (.indexOf ^java.util.List ids start)
                            :cljs (.indexOf (clj->js ids) start))]
                 (if (>= idx 0) (vec (drop (inc idx) rows)) rows))
               rows)
        page (vec (take limit rows))]
    [page (> (count rows) limit)]))

(defn expand [store rec params refs]
  (let [want (set (str/split (or (get params :expand) "") #","))]
    (reduce (fn [r [field ent]]
              (if (and (contains? want (name field)) (get r field))
                (assoc r (keyword (str (name field) "_obj")) (first (query store ent (get r field))))
                r))
            rec refs)))

;; --- generic handlers (return [body status]) ---
(defn- spec-for [entity] (first (filter #(= (:entity %) entity) entity-specs)))

(defn- status-code [export mirror]
  (try-oracle
   #(oracle/i64->host (o export []))
   (fn [] mirror)))

(defn- not-found []
  [{:error {:message "Not found" :type "not_found"}}
   (status-code 'not-found-status 404)])

(defn handle-create [store entity data]
  (let [{:keys [fields required coerce id-prefix]} (spec-for entity)
        bad (status-code 'bad-request-status 400)]
    (or (some-> (reject-unknown data fields) (vector bad))
        (some-> (require-fields data required) (vector bad))
        (let [base {:id (new-id id-prefix)}
              rec (reduce (fn [m f] (assoc m f (coerce-field (get coerce f) (get data f)))) base fields)
              rec (assoc rec :createdAt (now) :updatedAt (now))]
          (persist! store entity rec)
          [rec (status-code 'create-status 201)]))))

(defn handle-list [store entity params]
  (let [{:keys [fields]} (spec-for entity)
        rows (apply-filters (query store entity) params fields)
        [page has-more] (paginate rows params)]
    [{:object "list" :data page :has_more has-more :count (count page) :total (count rows)}
     (status-code 'ok-status 200)]))

(defn handle-get [store entity id params]
  (let [{:keys [refs]} (spec-for entity) rows (query store entity id)]
    (if (empty? rows)
      (not-found)
      [(expand store (first rows) params refs)
       (status-code 'ok-status 200)])))

(defn handle-update [store entity id data]
  (let [{:keys [fields]} (spec-for entity) rows (query store entity id)
        bad (status-code 'bad-request-status 400)]
    (if (empty? rows)
      (not-found)
      (or (some-> (reject-unknown data fields) (vector bad))
          (let [rec (reduce-kv (fn [m k v] (if (#{:id :createdAt} k) m (assoc m k v)))
                               (first rows) data)
                rec (assoc rec :updatedAt (now))]
            (persist! store entity rec)
            [rec (status-code 'ok-status 200)])))))

(defn handle-delete [store entity id]
  (if (empty? (query store entity id))
    (not-found)
    [(retract! store entity id)
     (status-code 'ok-status 200)]))

(defn healthz []
  [{:status "ok"
    :actor (try-oracle
            #(o 'health-actor [])
            (fn [] "cloudflare-compat"))
    :tier tier
    :entities entities}
   (status-code 'ok-status 200)])

;; --- WASM runtime registration (kotodama). The runtime host owns the live
;;     Datom log; handlers stay pure folds over a store, so this is G5-clean. ---
(defn start! [] :cloudflare-compat/ready)
