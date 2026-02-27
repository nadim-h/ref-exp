(ns stats
  (:require
   [fastmath.stats :as fstats])
  (:import
   (org.hipparchus.stat.inference WilcoxonSignedRankTest)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; EXTERN ;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn wilcoxon
  [x y]
  (let [x-arr (double-array (map double x))
        y-arr (double-array (map double y))
        test  (WilcoxonSignedRankTest.)]
    (.wilcoxonSignedRankTest test x-arr y-arr false)))

(defn cliffs-delta
  [x y]
  (fstats/cliffs-delta x y))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; TAXONOMY ;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private status-cat
  {:compile-fail #{:compile-error :compile-timeout :compile-error-name}
   :runtime-fail #{:all-rt-failed :all-rt-timeout :mix-pass-rt-failed
                   :mix-pass-rt-timeout :rt-failed :rt-timeout}
   :test-fail    #{:all-test-failed :mix-pass-failed :mix-pass-mix-failed
                   :failed}
   :passed       #{:all-passed :passed}})

(def ^:private impr-cat
  {:ch-improved  #{:score-improved-target-smell-fixed
                   :score-improved-target-smell-remain
                   :score-improved-target-smell-worse}
   :ch-unchanged #{:score-unchanged-target-smell-fixed
                   :score-unchanged-target-smell-remain
                   :score-unchanged-target-smell-worse}
   :ch-worse     #{:score-worse-target-smell-fixed
                   :score-worse-target-smell-remain
                   :score-worse-target-smell-worse}

   :smell-fixed  #{:score-improved-target-smell-fixed
                   :score-unchanged-target-smell-fixed
                   :score-worse-target-smell-fixed}
   :smell-remain #{:score-improved-target-smell-remain
                   :score-unchanged-target-smell-remain
                   :score-worse-target-smell-remain}
   :smell-worse  #{:score-improved-target-smell-worse
                   :score-unchanged-target-smell-worse
                   :score-worse-target-smell-worse}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; GENERIC HELPERS ;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- round-1
  [n]
  (if (number? n)
    (-> n double (* 10.0) Math/round (/ 10.0))
    0.0))

(defn- round-2
  [n]
  (if (number? n)
    (-> n double (* 100.0) Math/round (/ 100.0))
    0.0))

(defn- rate
  [n total]
  (if (pos? total)
    (round-1 (* (/ n total) 100.0))
    0.0))

(defn- mean
  [coll]
  (if (seq coll)
    (double (/ (reduce + coll) (count coll)))
    0.0))

(defn- sum-freq
  [freqs keys-set]
  (reduce + (vals (select-keys freqs keys-set))))


(defn calc-ci-stats
  [coll]
  (let [n     (count coll)
        mean  (fstats/mean coll)
        std   (fstats/stddev coll)
        se    (/ std (Math/sqrt n))
        me    (* 1.96 se)]
    {:mean     (round-1 mean)
     :ci       (round-1 me)
     :ci-lower (round-1 (- mean me))
     :ci-upper (round-1 (+ mean me))
     :se       (round-2 se)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;; CORE STATS ;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ->global-rates
  [data]
  (let [extract-pct (fn [k]
                      (->> data
                           (keep k)
                           (map #(* 100.0 %))))]

    {:pass-rate (calc-ci-stats (extract-pct :pass-rate))
     :imp-rate  (calc-ci-stats (extract-pct :imp-rate))}))

(defn ->global-rates2
  [data]
  {:pass-at-1 (round-1 (* 100.0 (mean (map :pass-rate data))))
   :imp-at-1  (round-1 (* 100.0 (mean (map :imp-rate data))))})

(defn ->status-breakdown
  [data]
  (let [all-refs (mapcat :refactorings data)
        total    (count all-refs)
        freqs    (frequencies (map (comp keyword :status) all-refs))

        compile-fails (sum-freq freqs (:compile-fail status-cat))
        runtime-fails (sum-freq freqs (:runtime-fail status-cat))
        test-fails    (sum-freq freqs (:test-fail status-cat))
        success       (sum-freq freqs (:passed status-cat))]

    {:total-attempts    total
     :success-rate      (rate success total)
     :test-fail-rate    (rate test-fails total)
     :compile-fail-rate (rate compile-fails total)
     :runtime-fail-rate (rate runtime-fails total)
     :raw-counts        freqs}))

(defn ->health-breakdown
  [data]
  (let [all-refs (mapcat :refactorings data)
        passing-attempts (filter #(= :all-passed (keyword (:status %))) all-refs)
        total-refs    (count all-refs)

        freqs (frequencies (map (comp keyword :imp-status) passing-attempts))

        count-ch-improved  (sum-freq freqs (:ch-improved impr-cat))
        count-ch-unchanged (sum-freq freqs (:ch-unchanged impr-cat))
        count-ch-worse     (sum-freq freqs (:ch-worse impr-cat))

        count-smell-fixed  (sum-freq freqs (:smell-fixed impr-cat))
        count-smell-remain (sum-freq freqs (:smell-remain impr-cat))
        count-smell-worse  (sum-freq freqs (:smell-worse impr-cat))

        count-ideal (get freqs :score-improved-target-smell-fixed 0)]

    {:ch-improved-smell-fixed-rate (rate count-ideal total-refs)

     :ch-improvement-rate (rate count-ch-improved total-refs)
     :ch-unchanged-rate   (rate count-ch-unchanged total-refs)
     :ch-worse-rate       (rate count-ch-worse total-refs)

     :smell-fixed-rate    (rate count-smell-fixed total-refs)
     :smell-remain-rate   (rate count-smell-remain total-refs)
     :smell-worse-rate    (rate count-smell-worse total-refs)}))

(defn ->token-stats
  [data]
  (let [all-refs (mapcat :refactorings data)
        extract  (fn [k] (keep k all-refs))]
    {:avg-prompt     (mean (extract :prompt-tokens))
     :avg-completion (mean (extract :completion-tokens))
     :avg-total      (mean (extract :total-tokens))}))

(defn ->error-stats
  [data]
  (let [all-refs (mapcat :refactorings data)
        runtime-fail-statuses (:runtime-fail status-cat)

        comp-errs (->> all-refs
                       (map :comp-err)
                       (remove nil?))

        rt-err   (->> all-refs
                       (filter #(runtime-fail-statuses (keyword (:status %))))
                       (map :rt-err)
                       (remove nil?))]

    {:compile-categories (frequencies (map :category comp-errs))
     :compile-subcategories (frequencies (map :subcategory comp-errs))

     :runtime-categories  (frequencies (map :category rt-err))
     :runtime-subcategories  (frequencies (map :subcategory rt-err))

     ;:top-compiler-msgs (take 5 (sort-by val > (frequencies (map :message comp-errs))))
     ;:top-runtime-msgs (take 5 (sort-by val > (frequencies (map :message rt-err))))
     }))
