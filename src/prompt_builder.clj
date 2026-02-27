(ns prompt-builder
  (:require
   [clojure.string :as str]
   [clojure.data.json :as json]))

(defn ->prompt-template
  [template]
  (json/read-str (slurp (str "template" "/" template ".json")) :key-fn keyword))

(defn- replace-placeholders
  [text params]
  (reduce-kv
   (fn [s k v]
     (str/replace s (str "{" (name k) "}") (str v)))
   text
   params))

(defn- format-examples
  [examples]
  (->> examples
       (map-indexed
        (fn [i ex]
          (let [header (str "### Example " (inc i))
                desc   (when (:description ex) (str "Description: " (:description ex) "\n"))
                before (when (:before ex) (str "Before:\n" (:before ex) "\n"))
                after  (str "After:\n" (:after ex) "\n")]
            (str/join "\n" (remove nil? [header desc before after])))))
       (str/join "\n\n")))

(defn- process-component
  [component params]
  (cond
    (:keys component)
    (let [lookup-keys (:keys component)
          param-values (map #(get params (keyword %)) lookup-keys)
          lookup-path  (map keyword param-values)
          result       (get-in (:templates component) lookup-path)]
      (cond
        (string? result) result
        (vector? result) (format-examples result)
        :else nil))

    (:key component)
    (let [lookup-key (keyword (:key component))
          lookup-val (get params lookup-key)
          templates  (:templates component)]
      (get templates (keyword lookup-val)))

    (:template component)
    (:template component)

    :else nil))

(defn build-prompt
  [data params]
  (let [components (:prompt_components data)

        raw-segments (keep #(process-component % params) components)
        full-template (str/join "\n\n" raw-segments)
        final-prompt (replace-placeholders full-template params)]

    {:system ""
     :user final-prompt}))

(defn- ->smell-line
  [start smell {:keys [start-line _end-line]}]
  (if (= smell "Complex Conditional")
    (- start-line start)
    0))

(defn build-params
  [start fun-code {:keys [smell location severity]} lang fun-name]
  {:snippet_language (name lang)
   :snippet_type (if (or (= lang :java) (not= 1 (count (str/split fun-name #"\."))))
                   "MemberFn" "StandaloneFn")
   :code_smell_category smell
   :code_smell_line (->smell-line start smell location)
   :snippet_code fun-code
   :detail severity})
