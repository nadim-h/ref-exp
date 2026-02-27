(ns llm-com
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [util :as ut]))

(def ^:private vllm-base-url "http://localhost:8000")

(def ^:private def-timeout 120000)

(defn build-params
  [model]
  (let [defaults {:max_tokens 8192}]
    (merge defaults
           (case model
             :glm47 {:temperature 1.0
                     :top_p 0.95}

             :qwen3   {:temperature 0.7
                       :top_p 0.8
                       :top_k 20
                       :repetition_penalty 1.05}

             :gpt-oss120 {:temperature 1.0
                          :top_p 1.0}
             :gpt-oss20 {:temperature 1.0
                         :top_p 1.0}

             {:temperature 1.0
              :top_p 1.0}))))

;; BUILD REQ:

(defmulti build-req
  (fn [_model _prompt] @ut/llm-backend))

(defmethod build-req :default
  [_model _prompt]
  (throw (IllegalArgumentException.
          (str "build-req not implemented for " @ut/llm-backend))))

(defmethod build-req :vllm
  [model prompt]
  (let [params (build-params model)
        base-body {:model model
                   :messages [{:role "system" :content (:system prompt)}
                              {:role "user"   :content (:user   prompt)}]
                   :stream false}]

    {:url (str vllm-base-url "/v1/chat/completions")
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string (merge base-body params))}))

;; PARSE RESP

(defmulti parse-resp
  (fn [_http-resp] @ut/llm-backend))

(defmethod parse-resp :default
  [_http-resp]
  (throw (IllegalArgumentException.
          (str "parse-resp not implemented for " @ut/llm-backend))))


(defmethod parse-resp :vllm
  [http-resp]
  (let [body (-> http-resp :body (json/parse-string true))]
    {:response (-> body :choices first :message :content)
     :prompt-tokens (get-in body [:usage :prompt_tokens])
     :completion-tokens (get-in body [:usage :completion_tokens])
     :total-tokens (get-in body [:usage :total_tokens])}))

(defn call-llm
  "Calls the specified LLM service (e.g., :ollama or :vllm)."
  [model prompt n]
  (try
    (let [req-details (build-req model prompt)
          rsp (http/post (:url req-details)
                         {:body (:body req-details)
                          :headers (:headers req-details)
                          :timeout def-timeout
                          :throw-exceptions false})]
      ;(clojure.pprint/pprint resp)
      (cond
        (<= 200 (:status rsp) 299)
        (merge (parse-resp rsp) {:success true
                                 :status (:status rsp)})
        (> n 0) ;; Retry.. ineffective recursion
        (call-llm model prompt (dec n))

        :else
        {:success false
         :error (str "HTTP Error: " (:status rsp))
         :full-response rsp
         :response-body (:body rsp)}))
    (catch Exception e
      {:success false
       :error (.getMessage e)
       :exception e})))

(defn extract-code
  [rsp]
  (let [s (str rsp)
        patterns [#"(?s)```(?:python|py|java)?\s*\nCODE\s*\n(.*?)\nENDCODE\s*\n```"
                  #"(?s)```(?:python|py|java)?\s*\nCODE\s*\n(.*?)\nCODE\s*\n```"
                  #"(?s)```(?:python|py|java)?\s*\nCODE\s*\n(.*?)\n```"

                  #"(?s)```(?:python|py|java)\s*\n(.*?)\n```"
                  #"(?s)```\s*\n(.*?)\n```"

                  #"(?s)CODE\s*\n(.*?)\nENDCODE"
                  #"(?s)^CODE\s*\n(.*?)\nCODE$"
                  #"(?s)CODE\s*\n(.+)$"]
        result (some (fn [pattern]
                       (when-let [match (re-find pattern s)]
                         (second match)))
                     patterns)]
    (if result
      (str/trim result)
      "no code extracted")))

(defn refactor-method
  [model prompt]
  (let [;_ (timbre/info "Requesting Refactoring...")
        res (call-llm model prompt 3)
        ;_ (timbre/info "Received Refactoring!")
        ]
    (if (:success res)
      (let [code (extract-code (:response res))]

        (assoc res :refactored-code code))
      (do
        (println "context failure..")
        (assoc res :refactored-code "non-success request")))))
