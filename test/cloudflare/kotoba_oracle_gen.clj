;; Regenerate precompiled KIR product-shell artifacts.
;;   clojure -M:oracle-gen

(ns cloudflare.kotoba-oracle-gen
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kotoba.compiler.core :as compiler])
  (:gen-class))

(defn discover-artifacts []
  (->> (file-seq (io/file "kotoba"))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) "_core.kotoba")))
       (sort-by #(.getName %))
       (mapv (fn [f]
               (let [base (str/replace (.getName f) #".kotoba$" "")]
                 {"source" (.getPath f)
                  "out" (str "resources/cloudflare/oracle/" base ".kir.edn")})))))

(defn compile-kir [source-path]
  (let [src (slurp source-path)
        r (compiler/compile-source src :wasm32-kotoba-v1 {})]
    (or (:kir r)
        (throw (ex-info "compile-source returned no :kir" {:source source-path})))))

(defn write-artifact! [{:strs [source out]}]
  (let [kir (compile-kir source)
        f (io/file out)]
    (io/make-parents f)
    (spit f (with-out-str (pp/pprint kir)))
    out))

(defn regenerate-all! []
  (mapv write-artifact! (discover-artifacts)))

(defn -main [& _]
  (doseq [p (regenerate-all!)]
    (println "wrote" p))
  (System/exit 0))
