(ns bleu-cmd
  (:require
   [babashka.process :as sh]
   [util :as ut]))

(defn- parse-bleu
  [ref pred]
  (->> (sh/shell {:out :string :continue true}
                 ".venv/bin/python" "py-utils/bleu.py" ref pred (name @ut/lang))
       :out
       Float/parseFloat
       ))

(defn lazy-dedupe-by-bleu
  [threshold tasks]
  (let [uniques-acc (atom [])]
    (letfn [(step [remaining-tasks]
              (lazy-seq
               (when-let [current-task (first remaining-tasks)]
                 (let [current-solution (:fun-code current-task)
                       uniques-snapshot @uniques-acc
                       is-duplicate? (if (empty? uniques-snapshot)
                                       false
                                       (some true?
                                             (pmap #(> (parse-bleu current-solution (:fun-code %))
                                                       threshold)
                                                   uniques-snapshot)))]
                   (if is-duplicate?
                     (step (rest remaining-tasks))
                     (do
                       (println "added unique, curr: " (count @uniques-acc) "/200")
                       (swap! uniques-acc conj current-task)
                       (cons current-task (step (rest remaining-tasks)))))))))]
      (step tasks))))
