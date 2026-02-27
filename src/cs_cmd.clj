(ns cs-cmd
  (:require
   [babashka.process :as sh]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [util :as ut]))

(defn- parse-review
  [code]
  (->> (sh/shell {:in code :out :string :continue true}
                 (str "timeout 20 cs review --output-format edn --file-name dummy" (ut/->ext)))
       :out
       edn/read-string))

(defn ->review-funs
  [code]
  (let [rev (parse-review code)
        score (:score rev)
        raw-reviews (:review rev)

        {func-reviews true module-reviews false}
        (group-by #(boolean (seq (:functions %))) raw-reviews)]

    {:score (if (some? score) score 0)

     :module (not-empty
              (mapv (fn [cat]
                      {:smell    (:category cat)
                       :details  (:description cat)
                       :severity (:indication cat)})
                    module-reviews))

     :funs  (or (some->> func-reviews
                         (mapcat (fn [cat]
                                   (map (fn [f]
                                          (let [title (or (:title f) "UnknownFunction")
                                                clean-name (first (str/split title #":"))

                                                details (or (:details f) "")

                                                severity-str (re-find #"\d+" details)
                                                severity-int (if severity-str
                                                               (try
                                                                 (Integer/parseInt severity-str)
                                                                 (catch NumberFormatException _ 0))
                                                               0)]
                                            {:fun-name clean-name
                                             :smell    (:category cat)
                                             :severity severity-int
                                             :details  details
                                             :location {:start-line (:start-line f)
                                                        :end-line   (:end-line f)}}))
                                        (:functions cat))))
                         (group-by :fun-name)
                         (mapv (fn [[fname smells]]
                                 {:fun-name fname
                                  :smells   (mapv #(dissoc % :fun-name) smells)})))
                [])}))

(defn sum-smell-severities
  [review]
  (apply merge-with +
         (for [fun (:funs review)
               smell (:smells fun)]
           {(:smell smell) (:severity smell)})))

(defn assign-score
  [target-smell-name org-rev ref-rev]
  (let [org-smells (sum-smell-severities org-rev)
        ref-smells (sum-smell-severities ref-rev)

        target-before (get org-smells target-smell-name 0)
        target-after  (get ref-smells target-smell-name 0)

        score-diff (- (:score ref-rev) (:score org-rev))
        score-status (cond
                       (pos? score-diff)  "improved"
                       (neg? score-diff)  "worse"
                       :else              "unchanged")

        smell-status (cond
                       (< target-after target-before) "fixed"
                       (> target-after target-before) "worse"
                       :else                          "remain")]

    (keyword (str "score-" score-status "-target-smell-" smell-status))))

(comment
  )
