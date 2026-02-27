(ns exec-cmd
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [util :as ut])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; CONFIGS ;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private HOST-SANDBOX-PATH (str (System/getProperty "user.home")
                                      "/exp_runs"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; GENERIC HELPERS ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn- exec-cmd
  [cmd-vec stdin]
  (let [timeout 5000
        proc (.start (ProcessBuilder. ^java.util.List cmd-vec))]

    (try
      (with-open [os (.getOutputStream proc)]
        (when (seq stdin)
          (.write os (.getBytes stdin "UTF-8"))
          (.flush os)))
      (catch java.io.IOException _e
        {:exit 1 :out "exception" :err "exception"}))

    (if (.waitFor proc timeout java.util.concurrent.TimeUnit/MILLISECONDS)
      {:exit (.exitValue proc)
       :out (slurp (.getInputStream proc) :encoding "UTF-8")
       :err (slurp (.getErrorStream proc) :encoding "UTF-8")}
      (do (.destroyForcibly proc)
          {:exit 1 :out "timeout" :err "timeout"}))))

(defn- norm-outp [s]
  (->> (or s "")
       str/trim
       (str/split-lines)
       (map str/trim)
       (filter seq)
       (str/join "\n")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; LANG SPECIFIC ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;; JAVA SPECIFIC

(defn- get-top-level-class-name
  [java-source]
  (let [pattern #"(?m)^\s*(public\s+)?(final\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)"
        matcher (re-matcher pattern java-source)]
    (when (re-find matcher)
      (nth (re-groups matcher) 3))))

(defn- cmd-javac
  [dir file code]
  (spit file code :encoding "UTF-8")
  (let [dir-name (.getName dir)
        file-name (.getName file)]
    (exec-cmd ["javac" "-nowarn" "-encoding" "UTF-8"
               (str HOST-SANDBOX-PATH "/" dir-name "/" file-name)]
              nil)))

(defn- cmd-java
  [class-name dir test-inp]
  (let [dir-name (.getName dir)]
    (exec-cmd ["java" "-cp"
               (str HOST-SANDBOX-PATH "/" dir-name) class-name]
              test-inp)))

;;;;;;;;;; CPP SPECIFIC

(defn- cmd-g++
  [dir file code]
  (spit file code :encoding "UTF-8")
  (let [dir-name (.getName dir)
        file-name (.getName file)
        exec-name "main.out"]
    (exec-cmd ["g++" "-std=c++17" "-finput-charset=UTF-8" "-fexec-charset=UTF-8"
               (str HOST-SANDBOX-PATH "/" dir-name "/" file-name) "-o"
               (str HOST-SANDBOX-PATH "/" dir-name "/" exec-name)]
              nil)))

(defn- cmd-cpp-run
  [exec-name dir test-inp]
  (let [dir-name (.getName dir)]
    (exec-cmd [(str HOST-SANDBOX-PATH "/" dir-name "/"  exec-name)]
              test-inp)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;; GENERIC TEST LOGIC ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-signal-message [exit-code]
  (case exit-code
    139 "Segmentation fault (SIGSEGV)"
    134 "Aborted (SIGABRT)"
    136 "Floating point exception (SIGFPE)"
    135 "Bus error (SIGBUS)"
    nil))

(defn- process-sol*
  [exec-fn expect-outp]
  (let [cmd-exec-outp (exec-fn)
        exit-code (:exit cmd-exec-outp)
        raw-err (:err cmd-exec-outp)

        signal-msg (get-signal-message exit-code)

        final-err (->> [raw-err signal-msg]
                       (remove clojure.string/blank?)
                       (clojure.string/join "\n"))]

    {:res (if (not (zero? exit-code))
            (if (or (= (:out cmd-exec-outp) "timeout") (= exit-code 124))
              :rt-timeout
              :rt-failed)
            (let [actual-outp (:out cmd-exec-outp)
                  norm-actual (norm-outp actual-outp)
                  norm-expect (norm-outp expect-outp)]
              (if (= norm-actual norm-expect)
                :passed
                :failed)))
     :rt-err final-err}))

(defn- ->sol-status
  [coll]
  (let [results (mapv :res coll)
        test-results (remove nil? results)
        test-count (count test-results)
        passed-count (count (filter #(= :passed %) test-results))
        failed-count (count (filter #(= :failed %) test-results))
        rt-failed-count (count (filter #(= :rt-failed %) test-results))
        rt-timeout-count (count (filter #(= :rt-timeout %) test-results))]
    (cond
      (= passed-count test-count) :all-passed
      (= passed-count 0) (cond
                           (= failed-count test-count) :all-test-failed
                           (= rt-failed-count test-count) :all-rt-failed
                           (= rt-timeout-count test-count) :all-rt-timeout
                           :else :all-failed-mixed)
      (and (= failed-count 0) (= rt-failed-count 0)) :mix-pass-rt-timeout
      (and (= failed-count 0) (= rt-timeout-count 0)) :mix-pass-rt-failed
      (and (= rt-failed-count 0) (= rt-timeout-count 0)) :mix-pass-failed
      :else :mix-pass-mix-failed)))

(defn- process-sol
  [exec-factory-fn tests-inp tests-outp]
  (let [;test-results* (mapv
        ;               (fn [input-str exp-out-str]
        ;                 (let [executor (exec-factory-fn input-str)]
        ;                   (process-sol* executor exp-out-str)))
        ;               tests-inp tests-outp)
        test-results* (reduce (fn [acc [input-str exp-out-str]]
                                (if (some #(not= (:res %) :passed) acc)
                                  (conj acc {:res nil :rt-err nil})
                                  (let [executor (exec-factory-fn input-str)
                                        res (process-sol* executor exp-out-str)]
                                    (conj acc res))))
                              []
                              (map vector tests-inp tests-outp))
        ;; For RT failures
       ; _ (println "")
       ; _ (pprint/pprint test-results*)
       ; _ (println "")
        ]
    {:status (->sol-status test-results*)
     :rt-err (->> test-results*
                   (map :rt-err)
                   (remove clojure.string/blank?)
                   (remove #(= [] %))
                   first)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;; DISPATCH ;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti ^:private run-lang-tests
  (fn [_ _ _ _] @ut/lang))

(defmethod run-lang-tests :default
  [_ _ _ _]
  (throw (IllegalArgumentException.
          (str "run-lang-tests not implemented for " @ut/lang))))

(defmethod run-lang-tests :java
  [tests-inp tests-outp _dir code]
  (if-let [class-name (get-top-level-class-name code)]
    (let [dir-path (.toPath (io/file HOST-SANDBOX-PATH))
          dirr (Files/createTempDirectory dir-path
                                          "exp-run-"
                                          (into-array FileAttribute []))
          dir (.toFile dirr)
          source-file (io/file dir (str class-name ".java"))
          cmd-compile-outp (cmd-javac dir source-file code)
          res {:status nil
               :comp-err nil
               :rt-err nil}]
      (if (not (zero? (:exit cmd-compile-outp)))
        (if (or (= (:out cmd-compile-outp) "timeout") (= (:exit cmd-compile-outp) 124))
          (assoc res :status :compile-timeout)
          (assoc res
                 :status :compile-error
                 :comp-err (:err cmd-compile-outp)))
        (let [exec-factory (fn [test-inp]
                             #(cmd-java class-name dir test-inp))]
          (merge res
                 (process-sol exec-factory tests-inp tests-outp)))))
    {:status :compile-error-name
     :comp-err nil
     :rt-err nil}))

(defmethod run-lang-tests :cpp
  [tests-inp tests-outp _dir code]
  (let [dir-path (.toPath (io/file HOST-SANDBOX-PATH))
        dirr (Files/createTempDirectory dir-path
                                        "exp-run-"
                                        (into-array FileAttribute []))
        dir (.toFile dirr)
        source-file (io/file dir "main.cpp")
        exec-name "main.out"
        cmd-compile-outp (cmd-g++ dir source-file code)
        res {:status nil
             :comp-err nil
             :rt-err nil}]

    (if (not (zero? (:exit cmd-compile-outp)))
      (if (or (= (:out cmd-compile-outp) "timeout") (= (:exit cmd-compile-outp) 124))
        (assoc res :status :compile-timeout)
        (assoc res
               :status :compile-error
               :comp-err (:err cmd-compile-outp)))

      (let [exec-factory (fn [test-inp]
                           #(cmd-cpp-run exec-name dir test-inp))]
        (merge res
               (process-sol exec-factory tests-inp tests-outp))))))

(defmethod run-lang-tests :python
  [tests-inp tests-outp _dir code]
  (let [exec-factory (fn [test-inp]
                       #(exec-cmd ["python3" "-W" "ignore" "-c" code] test-inp))]
    (process-sol exec-factory tests-inp tests-outp)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;; ERROR PARSING HELPER ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn classify-message
  [message patterns]
  (if-let [match (some #(when (re-find (:pattern %) message) %)
                       patterns)]
    (dissoc match :pattern)
    {:category :unknown :subcategory :unknown}))

(defn- parse-jvm-compiler-err
  [full-output]
  (when (seq full-output)
    (let [lines (str/split-lines full-output)]
      (first
       (for [line lines
             :let [trimmed (str/trim line)]
             :when (str/includes? trimmed "error:")]
         (let [msg (second (re-find #".*error:\s*(.*)$" trimmed))]
           (merge {:message (or msg "Unknown compilation error")
                   :full-output trimmed}
                  (classify-message msg ut/error-patterns))))))))

(defn- parse-jvm-runtime-err
  [full-output]
  (when-let [first-line (some-> full-output str/split-lines first)]
    (if-let [[_ cls msg] (re-find #"Exception in thread \".+?\" ([\w\.]+)(?::\s*(.*))?" first-line)]
      (let [full-msg (str cls ": " (or msg ""))]
        (merge {:message (or msg cls)
                :type cls
                :full-output full-output}
               (classify-message full-msg ut/exception-patterns)))
      (merge {:message first-line :full-output full-output}
             (if-let [c (classify-message full-output ut/exception-patterns)]
               c
               {:category :unknown :subcategory :unknown})))))


(defn- parse-py-runtime-err
  [full-output]
  (when (seq full-output)
    (let [last-line (->> (str/split-lines full-output)
                         (map str/trim)
                         (remove empty?)
                         last)
          match-msg (re-find #"^([\w\.]+):\s*(.*)$" last-line)
          match-type (when (not match-msg) (re-find #"^([\w\.]+)$" last-line))]

      (cond
        match-msg
        (let [[_ type msg] match-msg]
          (merge {:type type
                  :message msg
                  :full-output full-output}
                 (classify-message last-line ut/py-error-patterns)))

        match-type
        (let [[_ type] match-type]
          (merge {:type type
                  :message ""
                  :full-output full-output}
                 (classify-message last-line ut/py-error-patterns)))

        :else
        {:type :unknown
         :message (or last-line "Unparseable error")
         :full-output full-output
         :category :unknown}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;; MAIN ERROR PROCESSOR ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private py-compile-error-types
  #{"SyntaxError" "IndentationError" "TabError" "SystemError"})

(defmulti process-errors
  (fn [_failures] @ut/lang))

(defmethod process-errors :java
  [failures]
  (-> failures
      (update :comp-err parse-jvm-compiler-err)
      (update :rt-err (fn [errs]
                         (into [] (keep parse-jvm-runtime-err) errs)))))

(defmethod process-errors :python
  [failures]
  (let [processed (update failures :rt-err
                          (fn [errs]
                            (into [] (keep parse-py-runtime-err) errs)))
        first-err (first (:rt-err processed))
        is-syntax? (or (= (:category first-err) :syntax-error)
                       (py-compile-error-types (:type first-err)))]
    (if is-syntax?
      (-> processed
          (assoc :status :compile-error)
          (assoc :comp-err first-err)
          (assoc :rt-err nil))
      processed)))

(defmethod process-errors :cpp
  [_failures]
  (println "TODO"))

(defmethod process-errors :default
  [failures]
  failures)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; ENTRY ;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-code
  [inp sol]
  (let [tests-inp (concat (get-in inp [:public_tests :input] [])
                          (get-in inp [:private_tests :input] [])
                          (get-in inp [:generated_tests :input] []))
        tests-outp (concat (get-in inp [:public_tests :output] [])
                           (get-in inp [:private_tests :output] [])
                           (get-in inp [:generated_tests :output] []))]
    (if (or (empty? tests-inp) (empty? tests-outp))
      {:status :no-tests}
      (try
        (process-errors (run-lang-tests tests-inp tests-outp "temp" sol))
        (catch Exception e
          (println "failed during test run")
          (println e)
          {:status :exception})))))
