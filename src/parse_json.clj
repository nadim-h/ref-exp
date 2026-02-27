(ns parse-json
  (:require
   [clojure.data.json :as json]
   [cheshire.core :as chejson]
   [clojure.java.io :as io]))

(defn dump-ndjson
  [name data]
  (with-open [writer (java.io.BufferedWriter. (java.io.FileWriter. name))]
    (doseq [item data]
      (.write writer (chejson/generate-string item))
      (.newLine writer))))

(defn parse-json-fields
  [path]
  (let [file (io/file path)]
    (with-open [reader (io/reader file)]
      (mapv #(json/read-str %1 :key-fn keyword) (line-seq reader)))))


(defn process-json-file
  [path]
  (parse-json-fields path))
