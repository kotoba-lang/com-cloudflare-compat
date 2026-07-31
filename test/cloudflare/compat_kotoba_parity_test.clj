;; W6 pure oracle: cloudflare.main coerce/path scalars
;; vs kotoba/compat_core.kotoba.

(ns cloudflare.compat-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cloudflare.main :as m]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]))

(def port-source (slurp "kotoba/compat_core.kotoba"))

(def export-prefix
  (str "ns-prefix tier default-limit max-limit health-actor "
       "blank? ws? digit? digit-val trim parse-nat as-int-string "
       "as-bool-string clamp-limit collection-path item-path fact-attr "
       "id-with-prefix create-status ok-status bad-request-status "
       "not-found-status missing-required-prefix unknown-fields-prefix"))

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest constants-match-main
  (let [s (compile-string-cases
           {"nsp" "(ns-prefix)"
            "ti" "(tier)"
            "ha" "(health-actor)"
            "mr" "(missing-required-prefix)"
            "uf" "(unknown-fields-prefix)"})
        n (compile-i64-cases
           {"dl" "(default-limit)"
            "ml" "(max-limit)"
            "cs" "(create-status)"
            "os" "(ok-status)"
            "br" "(bad-request-status)"
            "nf" "(not-found-status)"})]
    (is (= m/ns-prefix (get s "nsp")))
    (is (= m/tier (get s "ti")))
    (is (= "cloudflare-compat" (get s "ha")))
    (is (= m/default-limit (get n "dl")))
    (is (= m/max-limit (get n "ml")))
    (is (= 201 (get n "cs")))
    (is (= 200 (get n "os")))
    (is (= 400 (get n "br")))
    (is (= 404 (get n "nf")))
    (is (str/starts-with? "Missing required fields: x" (get s "mr")))
    (is (str/starts-with? "Unknown fields: y" (get s "uf")))))

(deftest as-int-string-matches-main
  (let [corpus ["" "  " "0" "42" "  7  " "12x" "x12" "-3" "100"]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "ai_" i)
                           (str "(as-int-string " (kotoba-literal s) ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (m/as-int s) (get actual (str "ai_" i))))))))

(deftest as-bool-string-matches-main
  (let [corpus ["" "1" "true" "TRUE" "Yes" "on" "ON" "false" "0" "no" "  true  "]
        cases (into {} (map-indexed
                        (fn [i s]
                          [(str "ab_" i)
                           (str "(as-bool-string " (kotoba-literal s) ")")])
                        corpus))
        actual (compile-i64-cases cases)]
    (doseq [[i s] (map-indexed vector corpus)]
      (testing (pr-str s)
        (is (= (if (m/as-bool s) 1 0)
               (get actual (str "ab_" i))))))))

(deftest clamp-limit-matches-paginate-policy
  (let [actual (compile-i64-cases
                {"d" "(clamp-limit 0)"
                 "neg" "(clamp-limit -5)"
                 "ok" "(clamp-limit 10)"
                 "one" "(clamp-limit 1)"
                 "max" "(clamp-limit 100)"
                 "over" "(clamp-limit 500)"})]
    (is (= m/default-limit (get actual "d")))
    (is (= m/default-limit (get actual "neg")))
    (is (= 10 (get actual "ok")))
    (is (= 1 (get actual "one")))
    (is (= m/max-limit (get actual "max")))
    (is (= m/max-limit (get actual "over")))))

(deftest paths-and-facts-match-main
  (let [plurals (mapv :plural m/entity-specs)
        path-cases (into {}
                         (mapcat
                          (fn [p]
                            [[(str "c_" p) (str "(collection-path " (kotoba-literal p) ")")]
                             [(str "i_" p) (str "(item-path " (kotoba-literal p) ")")]])
                          plurals))
        paths (compile-string-cases path-cases)
        entity-field-lit
        "[:record :compat/entity-field [[:entity :string] [:field :string]]]"
        id-prefix-lit
        "[:record :compat/id-prefix [[:prefix :string] [:hex16 :string]]]"
        facts (compile-string-cases
               {"f1" (str "(fact-attr (record-new " entity-field-lit " "
                          (kotoba-literal "Zone") " "
                          (kotoba-literal "name") "))")
                "f2" (str "(fact-attr (record-new " entity-field-lit " "
                          (kotoba-literal "DNSRecord") " "
                          (kotoba-literal "ttl") "))")
                "id" (str "(id-with-prefix (record-new " id-prefix-lit " "
                          (kotoba-literal "cloudfla_zon") " "
                          (kotoba-literal "deadbeefdeadbeef") "))")})]
    (doseq [{:keys [plural]} m/entity-specs]
      (is (= (str "/v1/" plural) (get paths (str "c_" plural))))
      (is (= (str "/v1/" plural "/{id}") (get paths (str "i_" plural)))))
    (is (= (str m/ns-prefix ".Zone/name") (get facts "f1")))
    (is (= (str m/ns-prefix ".DNSRecord/ttl") (get facts "f2")))
    (is (= "cloudfla_zon_deadbeefdeadbeef" (get facts "id")))
    (testing "route table uses the same path surface"
      (let [path-set (set (map :path m/routes))]
        (doseq [p plurals]
          (is (contains? path-set (get paths (str "c_" p))))
          (is (contains? path-set (get paths (str "i_" p)))))))))
