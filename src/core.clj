(ns core
  (:require
   [bleu-cmd :as bl]
   [clojure.string :as str]
   [cs-cmd :as ht]
   [exec-cmd :as rc]
   [llm-com :as comm]
   [parse-json :as prs]
   [prompt-builder :as pb]
   [stats :as st]
   [tree-sitter :as ts]
   [util :as ut]
   [com.climate.claypoole :as cp]))

(defn give-ids
  [opts]
  (->> (prs/process-json-file (:inp opts))
       (map-indexed (fn [idx m]
                      (assoc m :id idx)))
       (prs/dump-ndjson (:outp opts))))

(defn ->n-pass-sols
  [n data test-data task-data]
  (println "searching for: " n " entries")
  (->> (shuffle data)
       (pmap (fn [entry]
               [entry (rc/run-code (ut/get-test-entry test-data (:name entry))
                                 (ts/replace-fun-correct (:fun-name entry)
                                                         (:task (ut/get-task-entry task-data (:id entry)))
                                                         (:fun-code entry)))]))
       (filter (fn [[_solution result]] (= :all-passed (:status result))))
       (map first)
       (bl/lazy-dedupe-by-bleu 0.90)
       (take n)))

(defn assign-fun-smells
  [opts]
  (ut/set-lang (:lang opts))
  (->> (prs/process-json-file (:inp opts))
       (map #(dissoc % :solution))
       (pmap #(assoc % :health (ht/->review-funs (:task %))))
       (map #(assoc % :score (:score (:health %))))
       (map #(assoc % :health (:funs (:health %))))
       (filter #(seq (:health %)))
       (prs/dump-ndjson (:outp opts))))

(defn aug-stuff
  [opts]
  (let [data (prs/process-json-file (:inp opts))
        all-tasks (mapcat (fn [{:keys [name id health task]}]
                            (map (fn [{:keys [fun-name smells]}]
                                   {:task           task
                                    :name           name
                                    :id             id
                                    :fun-name       fun-name
                                    :smells         smells})
                                 health))
                          data)]
    (ut/set-lang (:lang opts))
    (->> all-tasks
         (map #(assoc % :prio-smell (ut/pick-prio (:smells %))))
         (pmap #(assoc % :fun-code (ts/smell->fun-src (:prio-smell %) (:task %))))
         (pmap #(assoc % :fun-sloc (ts/count-sloc (:fun-code %))))
         (map #(dissoc % :task))

         (prs/dump-ndjson (:outp opts)))))

(defn gen-set
  [opts]
  (ut/set-lang (:lang opts))
  (let [data (prs/process-json-file (:inp opts))
        task-data (prs/process-json-file (:tasks opts))
        test-data (prs/process-json-file (:tests opts))]
    (->> (shuffle data)
           ;(take 10)
         (group-by #(:smell (:prio-smell %)))
            ;(#(->n-pass-sols2 2 % test-data task-data))

         (#(concat (->n-pass-sols 200 (get % "Bumpy Road Ahead") test-data task-data)
                   (->n-pass-sols 200 (get % "Complex Method") test-data task-data)
                   (->n-pass-sols 200 (get % "Complex Conditional") test-data task-data)
                   (->n-pass-sols 200 (get % "Deep, Nested Complexity") test-data task-data)
                   (->n-pass-sols 200 (get % "Large Method") test-data task-data)))

         (prs/dump-ndjson (:outp opts)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; R E F A C T O R I N G ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ->params
  [fun-name fun-code prio-smell tasks task-id lang]
  (let [task (:task (ut/get-task-entry tasks task-id))
        start-loc (ts/name->start-loc fun-name task)]
    (pb/build-params start-loc fun-code prio-smell lang fun-name)))

(defn refactoring*
  [tasks
   {:keys [model template lang] :as _opts}
   {:keys [fun-code fun-name prio-smell id] :as item}]
  (let [llm      (model ut/models)
        template (pb/->prompt-template template)
        params   (->params fun-name fun-code prio-smell tasks id lang)
        prompt   (pb/build-prompt template params)

        generate-fn (fn []
                      (let [rsp (comm/refactor-method llm prompt)]
                        (if (nil? (:refactored-code rsp))
                          (do (println "Error: refactored-code is nil for" fun-name)
                              (println rsp)
                              (System/exit 1))
                          {:refactored-method (:refactored-code rsp)
                           :completion-tokens (:completion-tokens rsp)
                           :prompt-tokens     (:prompt-tokens rsp)
                           :total-tokens      (:total-tokens rsp)})))]
    (merge item
           {:refactorings (vec (repeatedly 3 generate-fn))})))

(defn refactoring
  [{:keys [lang inp tasks] :as opts}]
  (println "Refactoring pipeline!: " opts)
  (ut/set-llm-backend :vllm)
  (ut/set-lang lang)
  (let [data (prs/process-json-file inp)
        tasks (prs/process-json-file tasks)]
    (->> (shuffle data)
        ;(take 2)
         (#(ut/pmap-with-prog 32 % (partial refactoring* tasks opts)))
         (prs/dump-ndjson (ut/get-data opts "refactor")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; T E S T ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn test-set*
  [test-data task-data {:keys [refactorings id name prio-smell] :as item}]
  (let [test-entry (ut/get-test-entry test-data name)
        task-entry (:task (ut/get-task-entry task-data id))]
    (assoc item :refactorings
           (mapv (fn [refactoring]
                   (merge refactoring
                          (rc/run-code
                           test-entry
                           (ts/replace-smell-fun prio-smell
                                                 task-entry
                                                 (:refactored-method refactoring)))))
                 refactorings))))

(defn pass-at-1
  [{:keys [refactorings]}]
  (let [n (count refactorings)
        c (count (filter #(= (keyword (:status %)) :all-passed)
                         refactorings))]
    (double (/ c n))))

(defn test-set
  [opts]
  (println opts)
  (println "Testing pipeline!")
  (ut/set-lang (:lang opts))
  (let [data (prs/process-json-file (ut/get-data opts "refactor"))
        task-data (prs/process-json-file (:tasks opts))
        test-data (prs/process-json-file (:tests opts))]
    (->> data
         (#(ut/pmap-with-prog 32 % (partial test-set* test-data task-data)))
         (map #(assoc % :pass-rate (pass-at-1 %)))
         (prs/dump-ndjson (ut/get-data opts "test")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; H E A L T H ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def total-cores (cp/ncpus))
(def outer-size  (max 2 (quot total-cores 2)))
(def inner-size  (max 2 (quot total-cores outer-size)))

(def item-pool (cp/threadpool outer-size))
(def ref-pool  (cp/threadpool inner-size))

(defn enrich-refs*
  [ref org-rev target-smell-name]
  (if (= (:status ref) "all-passed")
    (let [ref-code (:refactored-method ref)
          ref-rev  (ht/->review-funs ref-code)
          imp-stat (ht/assign-score target-smell-name org-rev ref-rev)]
      (assoc ref
             :ref-sloc (ts/count-sloc ref-code)
             :refactored-review ref-rev
             :imp-status imp-stat))
    (assoc ref :imp-status nil)))

(defn enrich-refs [item]
  (let [target  (get-in item [:prio-smell :smell])
        org-rev (:org-review item)]
    (update item :refactorings
            (fn [refs]
              (cp/pmap ref-pool
                       #(enrich-refs* % org-rev target)
                       refs)))))

(defn calc-rates
  [{:keys [refactorings]}]
  (let [n    (count refactorings)
        imp-c  (count (filter #(= (:imp-status %)
                                  :score-improved-target-smell-fixed)
                              refactorings))]
    (double (/ imp-c n))))

(defn health-set
  [opts]
  (println opts)
  (println "Stats pipeline!")
  (ut/set-lang (:lang opts))
  (let [data (prs/process-json-file (ut/get-data opts "test"))]
    (->> data
         (cp/pmap (cp/threadpool total-cores)
                  #(assoc % :org-review (ht/->review-funs (:fun-code %))))
         (cp/pmap item-pool enrich-refs)
         (map #(assoc % :imp-rate (calc-rates %)))
         (map #(dissoc % :smells))
         (prs/dump-ndjson (ut/get-data opts "health")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; S T A T S ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- gen-stats
  [data]
  (let [status (st/->status-breakdown data)
        tokens (st/->token-stats data)
        health (st/->health-breakdown data)
        rates  (st/->global-rates data)
        errs   (st/->error-stats data)]
    {:meta
     {:total-problems (count data)
      :total-attempts (:total-attempts status)
      :attempts-passed (get-in status [:raw-counts :all-passed] 0)}

     :metrics rates

     :tokens tokens

     :exec
     (select-keys status [:success-rate :compile-fail-rate
                          :runtime-fail-rate :test-fail-rate])

     :errs errs

     :passed-quality health}))

(defn stats-set
  [opts]
  (let [coll (prs/process-json-file (ut/get-data opts "health"))
        grouped (group-by #(get-in % [:prio-smell :smell] "Unknown") coll)
        clean-key (fn [k] (str/replace k #"[^a-zA-Z0-9]" "-"))

        smell-stats (reduce-kv
                     (fn [acc smell-name data-slice]
                       (assoc acc (keyword (clean-key smell-name))
                              (gen-stats data-slice)))
                     (array-map)
                     grouped)

        all-stats (gen-stats coll)

        extr (fn [d k] (->> d (keep k) (into [])))

        zs (prs/process-json-file (ut/get-data (assoc opts :template "zs") "health"))

        safe-wilc (fn [a b]
                    (try
                      (st/wilcoxon a b)
                      (catch Exception _
                        nil)))
        safe-cliff (fn [a b]
                     (try
                       (st/cliffs-delta a b)
                       (catch Exception _
                         nil)))

        wilc-pass  (safe-wilc (extr zs :pass-rate) (extr coll :pass-rate))
        cliff-pass (safe-cliff (extr zs :pass-rate) (extr coll :pass-rate))
        wilc-imp   (safe-wilc (extr zs :imp-rate)  (extr coll :imp-rate))
        cliff-imp  (safe-cliff (extr zs :imp-rate)  (extr coll :imp-rate))

        pval-map   {:pass wilc-pass :imp wilc-imp}
        cliff-map  {:pass cliff-pass :imp cliff-imp}

        smell-stats-with-all
        (-> smell-stats
            (assoc :all (-> all-stats
                            (assoc :p-value pval-map)
                            (assoc :cliff-data cliff-map))))]

    (println "Stats pipeline!: " opts)
    (prs/dump-ndjson (ut/get-data opts "stats") smell-stats-with-all)))

(comment
  (def opts {:lang :java :template "cot-hsp" :model :gpt-oss120})
  (def data (prs/process-json-file (ut/get-data opts "health")))

  ;(pmeans "java_tst21.jsonl")
  ;(pmeans "python_tst21.jsonl")

  (def data (prs/process-json-file "agent/gptme_9825_cpp_out.jsonl"))
  (def opts {:lang :cpp :inp "agent/gptme_9825_cpp.jsonl" :outp "agent/gptme_9825_cpp_out.jsonl" :tests "agent/tests/cpp_test_set.jsonl"})

  (test-set2 opts)


  (->> data
       first
       :ref-review
       keys)



  -
  )
