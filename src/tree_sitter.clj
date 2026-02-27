(ns tree-sitter
  (:require
   [clojure.string :as str]
   [util :as ut])
  (:import
   [org.treesitter
    TSParser
    TreeSitterCpp
    TreeSitterJava
    TreeSitterPython]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;; PARSING LOGIC ;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- code->root-node
  [parser code]
  (->> (.parseString parser nil code) .getRootNode))

(defn- node->start-line
  [node]
  (.getRow (.getStartPoint node)))

(defn- node->end-line
  [node]
  (.getRow (.getEndPoint node)))

(defn- node->children
  [node]
  (map #(.getNamedChild node %) (range (.getNamedChildCount node))))

(defn- node->all-children
  [node]
  (map #(.getChild node %) (range (.getChildCount node))))

(defn- node->src-code
  [^String code node]
  (if (and code node)
    (try
      (let [start-byte (.getStartByte node)
            end-byte   (.getEndByte node)
            code-bytes (.getBytes code "UTF-8")
            code-len   (alength code-bytes)]
        (if (<= 0 start-byte end-byte code-len)
          (String. code-bytes start-byte (- end-byte start-byte) "UTF-8")
          "FAIL"))
      (catch Exception _
        "FAIL"))
    "FAIL"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;; MULTIMETHOD ;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def java-lang   (delay (TreeSitterJava.)))
(def cpp-lang    (delay (TreeSitterCpp.)))
(def python-lang (delay (TreeSitterPython.)))

(defn get-lang-instance [lang-key]
  (case lang-key
    :java   @java-lang
    :cpp    @cpp-lang
    :python @python-lang
    (throw (IllegalArgumentException. (str "Unsupported lang: " lang-key)))))

;; 2. Create a ThreadLocal Parser
;;    Each of your 32 threads gets its OWN parser.
;;    They reuse it, avoiding the expensive setup/teardown crash.
(def ^:private thread-parser
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] (TSParser.)))))

;; 3. Safe get-parser function
(defn get-parser []
  (let [parser (.get thread-parser)       ;; Grab this thread's unique parser
        current-lang @ut/lang             ;; Get the current language keyword
        lang-instance (get-lang-instance current-lang)]
    (.setLanguage parser lang-instance)   ;; Configure it
    parser))

(defmulti get-parser2 (fn [] @ut/lang))

(defmethod get-parser2 :java
  []
  (let [parser (TSParser.)]
    (.setLanguage parser (TreeSitterJava.)) parser))

(defmethod get-parser2 :cpp
  []
  (let [parser (TSParser.)]
    (.setLanguage parser (TreeSitterCpp.)) parser))

(defmethod get-parser2
  :python []
  (let [parser (TSParser.)]
    (.setLanguage parser (TreeSitterPython.)) parser))

(defmulti get-comment-types
  (fn [] @ut/lang))

(defmethod get-comment-types :default
  []
  (throw (IllegalArgumentException.
          (str "get-comment-types not implemented for " @ut/lang))))

(defmethod get-comment-types :java
  []
  #{"line_comment" "block_comment"})

(defmethod get-comment-types :cpp
  []
  #{"comment"})

(defmethod get-comment-types :python
  []
  #{"comment"})

;;;;;;;;;;;;;;;

(defmulti get-funs
  (fn [_root-node] @ut/lang))

(defmethod get-funs :default
  [_]
  (throw (IllegalArgumentException.
          (str "get-funs not implemented for " @ut/lang))))

(defmethod get-funs :java
  [root-node]
  (let [fun-types #{"method_declaration" "constructor_declaration"}]
    (->> (tree-seq some? node->children root-node)
         (filter #(contains? fun-types (.getType %))))))

(defmethod get-funs :cpp
  [root-node]
  ;; Does not capture std::function and lambdas.
  ;; e.g. cant capture nested functions
  (let [fun-types #{"function_definition"}]
    (->> (tree-seq some? node->children root-node)
         (filter #(contains? fun-types (.getType %))))))

(defmethod get-funs :python
  [root-node]
  (let [fun-types #{"function_definition"}]
    (->> (tree-seq some? node->children root-node)
         (filter #(contains? fun-types (.getType %))))))

(defmulti get-fun-name-node (fn [_fun-node] @ut/lang))

(defmethod get-fun-name-node :java
  [fun-node]
  (->> (node->children fun-node)
       (filter #(= "identifier" (.getType %)))
       first))

(defmethod get-fun-name-node :cpp
  [fun-node]
  (some->> (tree-seq some? node->children fun-node)
           (filter #(= "function_declarator" (.getType %)))
           first
           node->children
           (filter #(or
                     (= "qualified_identifier" (.getType %))
                     (= "field_identifier" (.getType %))
                     (= "identifier" (.getType %))
                     (= "operator_name" (.getType %))
                     (= "destructor_name" (.getType %))))
           first))

(defmethod get-fun-name-node :python
  [fun-node]
  (->> (node->children fun-node)
       (filter #(= "identifier" (.getType %)))
       first))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;::::::::::::::::::::::::::: HELPERS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-closest-class-ancestor
  "Walks up the tree to find the nearest class/interface/enum container."
  [node]
  (loop [curr (.getParent node)]
    (cond
      (nil? curr) nil

      (contains? #{"class_definition"      ; Python/Java Class
                   "class_declaration"     ; Java Class
                   "interface_declaration" ; Java Interface
                   "enum_declaration"      ; Java Enum
                   "record_declaration"}   ; Java Record
                 (.getType curr))
      curr

      :else (recur (.getParent curr)))))

(defmulti get-nodes-to-remove
  (fn [_root-node] @ut/lang))

(defmethod get-nodes-to-remove :default
  [root-node]
  (let [comment-types (get-comment-types)]
    (->> (tree-seq some? node->all-children root-node)
         (filter #(contains? comment-types (.getType %))))))

(defmethod get-nodes-to-remove :python
  [root-node]
  (let [all-nodes (tree-seq some? node->all-children root-node)
        regular-comments (filter #(= "comment" (.getType %)) all-nodes)

        docstring-nodes (->> all-nodes
                             (filter #(= "expression_statement" (.getType %)))
                             (filter (fn [node]
                                       (and (= 1 (.getNamedChildCount node))
                                            (= "string" (.getType (.getNamedChild node 0)))))))]
    (concat regular-comments docstring-nodes)))

(defn- node->bytes-range
  "Returns the start and end byte of a node as a vector."
  [node]
  [(.getStartByte node) (.getEndByte node)])

(defn- get-class-nodes [root-node]
  (->> (tree-seq some? node->children root-node)
       (filter #(contains? #{"class_definition" "class_declaration"} (.getType %)))))

(defn- get-class-name-node [class-node]
  (->> (node->children class-node)
       (filter #(= "identifier" (.getType %)))
       first))

(defn- class-node-matches-name? [class-node code class-name]
  (when-let [name-node (get-class-name-node class-node)]
    (= class-name (node->src-code code name-node))))

(defn- node-matches-name?
  [node code fun-name]
  (when-let [ident-node (get-fun-name-node node)]
    (= fun-name (node->src-code code ident-node))))

(defn- name->fun-node
  [root-node code fun-name]
  (if (str/includes? fun-name ".")
    (let [parts (str/split fun-name #"\.")]
      (loop [current-node root-node
             [current-part & remaining-parts] parts]
        (if (empty? remaining-parts)
          (->> (get-funs current-node)
               (filter #(node-matches-name? % code current-part))
               (filter #(if-let [owner (get-closest-class-ancestor %)]
                          (= (.getStartByte owner) (.getStartByte current-node))
                          (= (.getStartByte root-node) (.getStartByte current-node))))
               first)
          (if-let [next-class-node (->> (get-class-nodes current-node)
                                        (filter #(class-node-matches-name? % code current-part))
                                        first)]
            (recur next-class-node remaining-parts)
            nil))))

    (->> (get-funs root-node)
         (filter #(node-matches-name? % code fun-name))
         first)))

(defn- node-matches-lines?
  [node {:keys [start-line end-line]}]
  (if (and node start-line end-line)
    (and (<= (node->start-line node) (dec start-line))
         (>= (node->end-line node) (dec end-line)))
    false))

(defn- location->fun-node
  [root-node loc]
  (->> (get-funs root-node)
       (filter #(node-matches-lines? % loc))
       first))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; APIS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn remove-comments
  [code]
  (let [parser (get-parser)
        root-node (code->root-node parser code)
        nodes-to-remove (get-nodes-to-remove root-node)
        comment-ranges (sort-by first (map node->bytes-range nodes-to-remove))
        code-bytes (.getBytes code "UTF-8")]
    (loop [ranges comment-ranges
           last-end 0
           acc []]
      (if-let [[start end] (first ranges)]
        (let [before (String. code-bytes last-end (- start last-end) "UTF-8")]
          (recur (rest ranges) end (conj acc before)))
        (let [remaining (String. code-bytes last-end (- (alength code-bytes) last-end) "UTF-8")]
          (str/join (conj acc remaining)))))))

(defn count-sloc
  [code]
  (let [;code-without-comments (remove-comments code)
        lines (str/split-lines code)]
    (->> lines
         (map str/trim)
         (remove str/blank?)
         count)))


(defn name->start-loc
  [fun-name code]
  (let [parser (get-parser)
        root (code->root-node parser code)
        fun-node (name->fun-node root code fun-name)]
    (node->start-line fun-node)))

(defn name->fun-src
  [fun-name code]
  (let [parser (get-parser)
        root-node (code->root-node parser code)
        fun-node (name->fun-node root-node code fun-name)]
    (try
      (node->src-code code fun-node)
      (catch Exception e
        (println "exception!" e)))))

(defn replace-fun-correct
  [fun-name code new-fun]
  (let [parser (get-parser)
        root-node (code->root-node parser code)
        old-node (name->fun-node root-node code fun-name)]
    (if-not old-node
      ;(throw (Exception. (str "Function '" fun-name "' not found in:\n" code)))
      "err"
      (let [start-line-idx (node->start-line old-node)
            first-line-of-old-fun (-> (str/split-lines code) (nth start-line-idx))
            indentation-prefix (second (re-find #"^(\s*)" first-line-of-old-fun))

            lines-of-new-fun (str/split-lines new-fun)
            first-new-line (first lines-of-new-fun)
            rest-new-lines (rest lines-of-new-fun)

            indented-new-fun
            (if-not (seq rest-new-lines)
              first-new-line
              (let [indented-rest (->> rest-new-lines
                                       (map #(if (str/blank? %) "" (str indentation-prefix %)))
                                       (str/join "\n"))]
                (str first-new-line "\n" indented-rest)))
            code-bytes (.getBytes code "UTF-8")

            start-byte (.getStartByte old-node)
            end-byte (.getEndByte old-node)

            before (String. code-bytes 0 start-byte "UTF-8")
            after (String. code-bytes end-byte (- (alength code-bytes) end-byte) "UTF-8")]
        (str before indented-new-fun after)))))


(defn replace-smell-fun
  [{:keys [location]} code new-fun]
  (let [parser (get-parser)
        root-node (code->root-node parser code)
        old-node (location->fun-node root-node location)]

    (if-not old-node
      "FAIL: Function not found for replacement"
      (let [start-line-idx (node->start-line old-node)
            first-line-of-old-fun (-> (str/split-lines code) (nth start-line-idx))
            indentation-prefix (second (re-find #"^(\s*)" first-line-of-old-fun))

            lines-of-new-fun (str/split-lines new-fun)
            first-new-line (first lines-of-new-fun)
            rest-new-lines (rest lines-of-new-fun)

            indented-new-fun
            (if-not (seq rest-new-lines)
              first-new-line
              (let [indented-rest (->> rest-new-lines
                                       (map #(if (str/blank? %) "" (str indentation-prefix %)))
                                       (str/join "\n"))]
                (str first-new-line "\n" indented-rest)))

            code-bytes (.getBytes code "UTF-8")
            start-byte (.getStartByte old-node)
            end-byte   (.getEndByte old-node)

            before (String. code-bytes 0 start-byte "UTF-8")
            after  (String. code-bytes end-byte (- (alength code-bytes) end-byte) "UTF-8")]

        (str before indented-new-fun after)))))


(defn smell->fun-src
  [{:keys [location]} code]
  (let [parser (get-parser)
        root-node (code->root-node parser code)
        fun-node (location->fun-node root-node location)]
    (if fun-node
      (node->src-code code fun-node)
      "FAIL: Function not found or location invalid")))

