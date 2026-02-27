(ns util
  (:require
   [progrock.core :as pr]
   [com.climate.claypoole :as cp]))

(defonce lang (atom :java))
(defonce llm-backend (atom :ollama))

(def models {:qwen3              "Qwen/Qwen3-Coder-30B-A3B-Instruct"
             :gpt-oss20          "openai/gpt-oss-20b"
             :gpt-oss120         "openai/gpt-oss-120b"})

(defn get-data
  [opts stage]
  (str "outs/"
       (name (:model opts)) "/"
       (name (:lang opts)) "/"
       stage "/"
       (:template opts) ".jsonl"))

(defn set-lang
  [new-lang]
  (reset! lang new-lang))

(defn set-llm-backend
  [new-llm-backend]
  (reset! llm-backend new-llm-backend))

(defn ->ext
  []
  (condp = @lang
    :java ".java"
    :cpp ".cpp"
    :python ".py"))

(defn get-test-entry [test-corp entry-name]
  (some #(when (= (:name %) entry-name) %) test-corp))

(defn get-task-entry [task-corp corp-id]
  (some #(when (= (:id %) corp-id) %) task-corp))

(def smells ["Large Method"
             "Complex Conditional"
             "Bumpy Road Ahead"
             "Deep, Nested Complexity"
             "Complex Method"])

(def ^:private category->priority
  (into {} (map-indexed (fn [n x] [x n]) smells)))

(defn pick-prio
  [code-smells]
  (let [[priority prio-smell] (->> code-smells
                                   (mapv
                                    (fn [{:keys [smell] :as m}]
                                      [(get category->priority smell Integer/MAX_VALUE) m]))
                                   (sort-by first)
                                   first)]
    (when (not= priority Integer/MAX_VALUE)
      prio-smell)))

(defn pmap-with-prog
  [batch-size coll f]
  (let [bar (atom (pr/progress-bar (count coll)));(atom (pr/progress-bar (num-solutions coll)))
        opt {:format ":progress/:total   :percent% [:bar]  Elapsed: :elapsed  ETA: :remaining"}]
    (pr/print @bar opt)
    (let [results (->> coll
                       (partition-all batch-size)
                       (mapcat (fn [batch]
                                 (let [batch-results (doall (cp/pmap (cp/ncpus) f batch))]
                                   (swap! bar pr/tick (count batch))
                                   (pr/print @bar opt)
                                   batch-results)))
                       vec)]
      (println)
      results)))



;;==============================================================================
;; Java Compiler Error Patterns
;;==============================================================================
(def error-patterns
  [;; Syntax Errors
   {:pattern     #"';' expected"
    :category    :syntax-error
    :subcategory :missing-semicolon}
   {:pattern     #"'\{' expected"
    :category    :syntax-error
    :subcategory :missing-opening-brace}
   {:pattern     #"'\}' expected"
    :category    :syntax-error
    :subcategory :missing-closing-brace}
   {:pattern     #"'\(' expected"
    :category    :syntax-error
    :subcategory :missing-opening-paren}
   {:pattern     #"'\)' expected"
    :category    :syntax-error
    :subcategory :missing-closing-paren}
   {:pattern     #"'\)' or ',' expected"
    :category    :syntax-error
    :subcategory :missing-closing-paren-or-comma}
   {:pattern     #"'\]' expected"
    :category    :syntax-error
    :subcategory :missing-closing-bracket}
   {:pattern     #"'\[' expected"
    :category    :syntax-error
    :subcategory :missing-opening-bracket}
   {:pattern     #"unclosed string literal"
    :category    :syntax-error
    :subcategory :unclosed-string-literal}
   {:pattern     #"reached end of file while parsing"
    :category    :syntax-error
    :subcategory :unexpected-eof}
   {:pattern     #"illegal start of (expression|type)"
    :category    :syntax-error
    :subcategory :illegal-start}
   {:pattern     #"not a statement"
    :category    :syntax-error
    :subcategory :not-a-statement}
   {:pattern     #"<identifier> expected"
    :category    :syntax-error
    :subcategory :missing-identifier}
   {:pattern     #"break outside switch or loop"
    :category    :syntax-error
    :subcategory :misplaced-break}
   {:pattern     #"continue outside of loop"
    :category    :syntax-error
    :subcategory :misplaced-continue}
   {:pattern     #"illegal forward reference"
    :category    :syntax-error
    :subcategory :forward-reference}
   {:pattern     #"illegal line end in character literal"
    :category    :syntax-error
    :subcategory :malformed-character-literal}
   {:pattern     #"illegal character:"
    :category    :syntax-error
    :subcategory :illegal-character}
   {:pattern     #"illegal escape character"
    :category    :syntax-error
    :subcategory :illegal-escape-character}
   {:pattern     #"method reference not expected here"
    :category    :syntax-error
    :subcategory :misplaced-method-reference}
   {:pattern     #"invalid method reference"
    :category    :syntax-error
    :subcategory :invalid-method-reference}

   ;; Type Errors
   {:pattern     #"cannot find symbol"
    :category    :type-error
    :subcategory :undefined-symbol}
   {:pattern     #"incompatible types"
    :category    :type-error
    :subcategory :incompatible-types}
   {:pattern     #"possible lossy conversion"
    :category    :type-error
    :subcategory :lossy-conversion}
   {:pattern     #"method .+ in class .+ cannot be applied to given types"
    :category    :type-error
    :subcategory :method-signature-mismatch}
   {:pattern     #"constructor .+ in class .+ cannot be applied to given types"
    :category    :type-error
    :subcategory :constructor-signature-mismatch}
   {:pattern     #"reference to .+ is ambiguous"
    :category    :type-error
    :subcategory :ambiguous-reference}
   {:pattern     #"cannot assign a value to final variable"
    :category    :type-error
    :subcategory :final-variable-assignment}
   {:pattern     #"bad operand types"
    :category    :type-error
    :subcategory :bad-operand-types}
   {:pattern     #"bad operand type"
    :category    :type-error
    :subcategory :bad-operand-types}
   {:pattern     #"(unchecked conversion|inconvertible types|generic type)"
    :category    :type-error
    :subcategory :generics-or-unchecked}
   {:pattern     #"type .+ does not take parameters"
    :category    :type-error
    :subcategory :non-generic-type-with-parameters}
   {:pattern     #".+ cannot be dereferenced"
    :category    :type-error
    :subcategory :primitive-dereference}
   {:pattern     #"no suitable method found for .+"
    :category    :type-error
    :subcategory :method-signature-mismatch}
   {:pattern     #"local variables referenced from a lambda expression must be final or effectively final"
    :category    :type-error
    :subcategory :expression-must-be-final}

   ;; Semantic Errors
   {:pattern     #"variable .+ might not have been initialized"
    :category    :semantic-error
    :subcategory :uninitialized-variable}
   {:pattern     #"missing return statement"
    :category    :semantic-error
    :subcategory :missing-return-statement}
   {:pattern     #"unreachable statement"
    :category    :semantic-error
    :subcategory :unreachable-statement}
   {:pattern     #"symbol '.+' already defined"
    :category    :semantic-error
    :subcategory :duplicate-variable}
   {:pattern     #"method .+ is already defined in class .+"
    :category    :semantic-error
    :subcategory :duplicate-method}
   {:pattern     #"class .+ is already defined"
    :category    :semantic-error
    :subcategory :duplicate-class}
   {:pattern     #"method does not override or implement a method from a supertype"
    :category    :semantic-error
    :subcategory :override-error}
   {:pattern     #"undefined label:"
    :category    :semantic-error
    :subcategory :undefined-label}
   {:pattern     #"variable .+ is already defined in .+"
    :category    :semantic-error
    :subcategory :duplicate-variable}
   {:pattern     #"statements not expected outside of methods and initializers"
    :category    :semantic-error
    :subcategory :statement-outside-method}
   {:pattern     #"class, interface, enum, or record expected"
    :category    :semantic-error
    :subcategory :encapsulation-expected}
   {:pattern     #"illegal start of statement"
    :category    :semantic-error
    :subcategory :illegal-start-of-statement}
   {:pattern     #"wrong number of type arguments"
    :category    :semantic-error
    :subcategory :wrong-number-of-args}

   ;; Access/Visibility Errors
   {:pattern     #"has private access"
    :category    :access-error
    :subcategory :private-access}
   {:pattern     #"has protected access"
    :category    :access-error
    :subcategory :protected-access}
   {:pattern     #".+ is not public"
    :category    :access-error
    :subcategory :not-public-access}
   {:pattern     #"non-static (variable|method) .+ cannot be referenced from a static context"
    :category    :access-error
    :subcategory :static-access-to-instance}
   {:pattern     #"(cannot access|is not visible)"
    :category    :access-error
    :subcategory :not-visible}

   ;; Modifier / Declaration Errors
   {:pattern     #"modifier .* not allowed here"
    :category    :declaration-error
    :subcategory :invalid-modifier}
   {:pattern     #"modifier .* is already present"
    :category    :declaration-error
    :subcategory :duplicate-modifier}
   {:pattern     #"invalid method declaration; return type required"
    :category    :declaration-error
    :subcategory :missing-return-type}
   {:pattern     #"interface abstract methods cannot have body"
    :category    :declaration-error
    :subcategory :interface-abstract-method-body}
   {:pattern     #"cannot override final method"
    :category    :declaration-error
    :subcategory :override-final-method}
   {:pattern     #"declaration not allowed here"
    :category    :declaration-error
    :subcategory :declaration-not-allowed}

   ;; Class Errors
   {:pattern     #"class .+ is public, should be declared in a file named"
    :category    :class-error
    :subcategory :wrong-filename-for-public-class}
   {:pattern     #"cannot inherit from final"
    :category    :class-error
    :subcategory :inherit-from-final}
   {:pattern     #"class, interface, or enum expected"
    :category    :class-error
    :subcategory :invalid-top-level-declaration}
   {:pattern     #".+ is abstract; cannot be instantiated"
    :category    :class-error
    :subcategory :abstract-class-instantiation}
   {:pattern     #"missing method body, or declare abstract"
    :category    :class-error
    :subcategory :missing-method-body}
   {:pattern     #".+ is not abstract and does not override abstract method"
    :category    :class-error
    :subcategory :missing-method-implementation}

   ;; Import/Package / Module Errors
   {:pattern     #"package .+ does not exist"
    :category    :import-error
    :subcategory :package-not-found}
   {:pattern     #"package .* is not visible"
    :category    :import-error
    :subcategory :package-not-visible}
   {:pattern     #"(module .* not found|module .* not loaded|module .* cannot be found)"
    :category    :module-error
    :subcategory :module-not-found}
   {:pattern     #"(module .* does not export|module .* does not read|module .* is not readable)"
    :category    :module-error
    :subcategory :module-access}
   {:pattern     #"cannot find package"
    :category    :import-error
    :subcategory :package-not-found}

   ;; Array Errors
   {:pattern     #"array required, but .+ found"
    :category    :array-error
    :subcategory :not-an-array}
   {:pattern     #"array dimension missing"
    :category    :array-error
    :subcategory :missing-array-dimension}

   ;; Exception Handling
   {:pattern     #"unreported exception .+; must be caught or declared to be thrown"
    :category    :exception-error
    :subcategory :unreported-exception}
   {:pattern     #"exception .+ is never thrown in body of corresponding try statement"
    :category    :exception-error
    :subcategory :unnecessary-catch-clause}
   {:pattern     #"unhandled exception type"
    :category    :exception-error
    :subcategory :unhandled-exception}

   ;; Annotation Errors
   {:pattern     #"\@Override is not applicable to (.*) declarations"
    :category    :annotation-error
    :subcategory :misplaced-override-annotation}

   ;; Build / Classfile Errors
   {:pattern     #"(badly formed class file|class file has wrong version|class file contains)"
    :category    :build-error
    :subcategory :classfile-problem}
   {:pattern     #"preview feature"
    :category    :build-error
    :subcategory :preview-feature-disabled}])

;;==============================================================================
;; Runtime Exception / Error Patterns
;;==============================================================================
(def exception-patterns
  [;; Common Runtime Exceptions
   {:pattern     #"java\.lang\.NullPointerException"
    :category    :runtime-exception
    :subcategory :null-pointer}
   {:pattern     #"java\.lang\.ArithmeticException: / by zero"
    :category    :runtime-exception
    :subcategory :division-by-zero}
   {:pattern     #"java\.lang\.ArithmeticException"
    :category    :runtime-exception
    :subcategory :arithmetic}
   {:pattern     #"java\.lang\.ArrayIndexOutOfBoundsException"
    :category    :runtime-exception
    :subcategory :array-index-out-of-bounds}
   {:pattern     #"java\.lang\.StringIndexOutOfBoundsException"
    :category    :runtime-exception
    :subcategory :string-index-out-of-bounds}
   {:pattern     #"java\.lang\.IndexOutOfBoundsException"
    :category    :runtime-exception
    :subcategory :index-out-of-bounds}
   {:pattern     #"java\.lang\.ClassCastException"
    :category    :runtime-exception
    :subcategory :class-cast}
   {:pattern     #"java\.lang\.NumberFormatException"
    :category    :runtime-exception
    :subcategory :number-format}
   {:pattern     #"java\.lang\.IllegalArgumentException"
    :category    :runtime-exception
    :subcategory :illegal-argument}
   {:pattern     #"java\.lang\.IllegalStateException"
    :category    :runtime-exception
    :subcategory :illegal-state}
   {:pattern     #"java\.util\.ConcurrentModificationException"
    :category    :runtime-exception
    :subcategory :concurrent-modification}
   {:pattern     #"java\.util\.NoSuchElementException"
    :category    :runtime-exception
    :subcategory :no-such-element}
   {:pattern     #"java\.lang\.UnsupportedOperationException"
    :category    :runtime-exception
    :subcategory :unsupported-operation}
   {:pattern     #"java\.lang\.SecurityException"
    :category    :runtime-exception
    :subcategory :security}
   {:pattern     #"java\.lang\.IllegalMonitorStateException"
    :category    :runtime-exception
    :subcategory :illegal-monitor}
   {:pattern     #"java\.lang\.InterruptedException"
    :category    :checked-exception
    :subcategory :interrupted}
   {:pattern     #"java\.lang\.NegativeArraySizeException"
    :category    :runtime-exception
    :subcategory :negative-array-size}
   {:pattern     #"java\.util\.InputMismatchException"
    :category    :runtime-exception
    :subcategory :input-mismatch}
   {:pattern     #"java\.lang\.RuntimeException"
    :category    :runtime-exception
    :subcategory :generic-runtime}

   ;; Checked exceptions
   {:pattern     #"java\.lang\.reflect\.InvocationTargetException"
    :category    :checked-exception
    :subcategory :invocation-target}
   {:pattern     #"java\.lang\.ClassNotFoundException"
    :category    :checked-exception
    :subcategory :class-not-found}

   ;; Reflection/Class Loading / Linkage / native
   {:pattern     #"java\.lang\.NoClassDefFoundError"
    :category    :linkage-error
    :subcategory :no-class-def-found}
   {:pattern     #"java\.lang\.NoSuchMethodError"
    :category    :linkage-error
    :subcategory :no-such-method}
   {:pattern     #"java\.lang\.NoSuchFieldError"
    :category    :linkage-error
    :subcategory :no-such-field}
   {:pattern     #"java\.lang\.UnsupportedClassVersionError"
    :category    :linkage-error
    :subcategory :unsupported-class-version}
   {:pattern     #"java\.lang\.IncompatibleClassChangeError"
    :category    :linkage-error
    :subcategory :incompatible-class-change}
   {:pattern     #"java\.lang\.ClassFormatError"
    :category    :linkage-error
    :subcategory :class-format}
   {:pattern     #"java\.lang\.ExceptionInInitializerError"
    :category    :linkage-error
    :subcategory :initializer-error}
   {:pattern     #"java\.lang\.UnsatisfiedLinkError"
    :category    :linkage-error
    :subcategory :native-linkage}

   ;; Checked Exceptions (often indicate environmental issues)
   {:pattern     #"java\.io\.FileNotFoundException"
    :category    :checked-exception
    :subcategory :file-not-found}
   {:pattern     #"java\.io\.IOException"
    :category    :checked-exception
    :subcategory :io-exception}
   {:pattern     #"java\.sql\.SQLException"
    :category    :checked-exception
    :subcategory :sql-exception}
   {:pattern     #"java\.util\.concurrent\.TimeoutException"
    :category    :checked-exception
    :subcategory :timeout}

   ;; Serious Errors (VM / memory / stack / assertion)
   {:pattern     #"java\.lang\.StackOverflowError"
    :category    :error
    :subcategory :stack-overflow}
   {:pattern     #"java\.lang\.OutOfMemoryError: Java heap space"
    :category    :error
    :subcategory :oom-heap-space}
   {:pattern     #"java\.lang\.OutOfMemoryError: GC overhead limit exceeded"
    :category    :error
    :subcategory :oom-gc-overhead}
   {:pattern     #"java\.lang\.OutOfMemoryError: Metaspace"
    :category    :error
    :subcategory :oom-metaspace}
   {:pattern     #"java\.lang\.OutOfMemoryError"
    :category    :error
    :subcategory :out-of-memory}
   {:pattern     #"java\.lang\.AssertionError"
    :category    :error
    :subcategory :assertion-error}
   {:pattern     #"java\.util\.EmptyStackException"
    :category    :error
    :subcategory :empty-stack}

   {:pattern     #"timeout"
    :category    :timeout
    :subcategory :timeout}])


(def py-error-patterns
  [;;============================================================================
   ;; Python Syntax & Indentation Errors (Compile-time)
   ;; More specific patterns should come first.
   ;;============================================================================
   {:pattern     #"^SyntaxError: unterminated string literal"
    :category    :syntax-error
    :subcategory :unclosed-string-literal}
   {:pattern     #"^SyntaxError: EOL while scanning string literal"
    :category    :syntax-error
    :subcategory :unclosed-string-literal}
   {:pattern     #"^SyntaxError: '.+' was never closed"
    :category    :syntax-error
    :subcategory :unclosed-bracket}
   {:pattern     #"^SyntaxError: invalid character in identifier"
    :category    :syntax-error
    :subcategory :invalid-character-identifier}
   {:pattern     #"^SyntaxError: invalid character"
    :category    :syntax-error
    :subcategory :invalid-character}
   {:pattern     #"^SyntaxError: unexpected EOF while parsing"
    :category    :syntax-error
    :subcategory :unexpected-eof}
   {:pattern     #"^SyntaxError: can't assign to"
    :category    :syntax-error
    :subcategory :invalid-assignment}
   {:pattern     #"^SyntaxError: invalid syntax"
    :category    :syntax-error
    :subcategory :invalid-syntax}
   {:pattern     #"^SyntaxError: unmatched '\)'"
    :category    :syntax-error
    :subcategory :invalid-syntax}
   {:pattern     #"^IndentationError: expected an indented block"
    :category    :syntax-error
    :subcategory :missing-indent}
   {:pattern     #"^IndentationError: unindent does not match any outer indentation level"
    :category    :syntax-error
    :subcategory :inconsistent-unindent}
   {:pattern     #"^TabError: inconsistent use of tabs and spaces in indentation"
    :category    :syntax-error
    :subcategory :mixed-tabs-and-spaces}
   {:pattern     #"^SyntaxWarning: invalid escape sequence"
    :category    :syntax-error
    :subcategory :invalid-escape-sequence}
   {:pattern     #"^SyntaxError: unterminated triple-quoted string literal"
    :category    :syntax-error
    :subcategory :unterminated-triple-quoted-string}
   {:pattern     #"^EOFError: EOF when reading a line"
    :category    :eof-error
    :subcategory :eof-when-reading-a-line}
   {:pattern     #"^SyntaxError: 'return' outside function"
    :category    :syntax-error
    :subcategory :return-outside-function}
   {:pattern     #"^SyntaxError: closing parenthesis"
    :category    :syntax-error
    :subcategory :missing-closing-parenthesis}
   {:pattern     #"^SyntaxError: cannot delete conditional expression"
    :category    :syntax-error
    :subcategory :delete-conditional-expr}
   {:pattern     #"^SyntaxError: unexpected character after line continuation character"
    :category    :syntax-error
    :subcategory :unexpected-character-after-line-cont}
   {:pattern     #"^SyntaxError: no binding for nonlocal"
    :category    :syntax-error
    :subcategory :no-binding-for-nonlocal}
   {:pattern      #"^IndentationError: unexpected indent"
    :category     :syntax-error
    :subcategory  :indent-error-unexpected-indent}
   {:pattern      #"'continue' not properly in loop"
    :category     :syntax-error
    :subcategory  :continue-not-properly-in-loop}
   {:pattern      #"not properly in loop"
    :category     :syntax-error
    :subcategory  :not-properly-in-loop}
   {:pattern      #"'break' outside loop"
    :category     :syntax-error
    :subcategory  :break-outside-loop}
   {:pattern      #"outside loop"
    :category     :syntax-error
    :subcategory  :outside-loop}
   {:pattern      #"is parameter and global"
    :category     :syntax-error
    :subcategory  :param-and-global}
   {:pattern      #"is parameter and nonlocal"
    :category     :syntax-error
    :subcategory  :param-and-nonlocal}
   {:pattern      #"expected '\:'"
    :category     :syntax-error
    :subcategory  :expected-colon}
   {:pattern      #"used prior to global declaration"
    :category     :syntax-error
    :subcategory  :name-used-before-global}
   {:pattern      #"'yield' outside function"
    :category     :syntax-error
    :subcategory  :yield-outside-fun}
   {:pattern      #"assigned to before nonlocal declaration"
    :category     :syntax-error
    :subcategory  :assigned-before-declaration}
   {:pattern      #"Generator expression must be parenthesized"
    :category     :syntax-error
    :subcategory  :generator-missing-paren}

   ;;============================================================================
   ;; Python Runtime Exceptions & Errors
   ;;============================================================================
   ;; Name/Scope Errors
   {:pattern     #"^NameError: name '.+' is used prior to global declaration"
    :category    :runtime-error
    :subcategory :name-used-before-global}
   {:pattern     #"^NameError: name '.+' is not defined"
    :category    :runtime-error
    :subcategory :undefined-name}
   {:pattern     #"^UnboundLocalError:"
    :category    :runtime-error
    :subcategory :unbound-local-variable}
   {:pattern     #"not associated with a value in enclosing scope"
    :category    :runtime-error
    :subcategory :free-value-not-in-scope}

   ;; Type/Value Errors (Specific before General)
   {:pattern     #"^TypeError: unsupported operand type"
    :category    :runtime-error
    :subcategory :unsupported-operand-type}
   {:pattern     #"^TypeError: '.+' object is not iterable"
    :category    :runtime-error
    :subcategory :not-iterable}
   {:pattern     #"^TypeError:"
    :category    :runtime-error
    :subcategory :type-mismatch}
   {:pattern     #"^ValueError: invalid literal for int\(\) with base \d+:"
    :category    :runtime-error
    :subcategory :invalid-int-literal}
   {:pattern     #"^ValueError: math domain error"
    :category    :runtime-error
    :subcategory :math-domain-error}
   {:pattern     #"^ValueError:"
    :category    :runtime-error
    :subcategory :invalid-value}

   ;; Container/Sequence Errors
   {:pattern     #"^IndexError: .+ index out of range"
    :category    :runtime-error
    :subcategory :index-out-of-bounds}
   {:pattern     #"index out of range"
    :category    :runtime-error
    :subcategory :index-out-of-bounds}
   {:pattern     #"^KeyError:"
    :category    :runtime-error
    :subcategory :missing-key}
   {:pattern     #"^StopIteration"
    :category    :runtime-error
    :subcategory :iterator-exhausted}
   {:pattern     #"pop from empty list"
    :category    :runtime-error
    :subcategory :pop-empty-list}

   ;; Attribute/Object Errors
   {:pattern     #"^AttributeError:"
    :category    :runtime-error
    :subcategory :invalid-attribute}

   ;; Arithmetic/Math Errors
   {:pattern     #"^ZeroDivisionError: division by zero"
    :category    :runtime-error
    :subcategory :division-by-zero}
   {:pattern     #"^ZeroDivisionError: integer modulo by zero"
    :category    :runtime-error
    :subcategory :integer-modulo-by-zero}
   {:pattern     #"^OverflowError:"
    :category    :runtime-error
    :subcategory :numeric-overflow}
   {:pattern     #"^FloatingPointError:"
    :category    :runtime-error
    :subcategory :floating-point-error}

   ;; Import/Module Errors (Specific subclass first)
   {:pattern     #"^ModuleNotFoundError: No module named '.+'"
    :category    :runtime-error
    :subcategory :module-not-found}
   {:pattern     #"^ImportError:"
    :category    :runtime-error
    :subcategory :import-error}

   ;; System/Resource Errors
   {:pattern     #"^FileNotFoundError:"
    :category    :runtime-error
    :subcategory :file-not-found}
   {:pattern     #"^RecursionError: maximum recursion depth exceeded"
    :category    :runtime-error
    :subcategory :stack-overflow}
   {:pattern     #"^MemoryError"
    :category    :runtime-error
    :subcategory :out-of-memory}

   ;; Assertion/Logic Errors
   {:pattern     #"^AssertionError"
    :category    :runtime-error
    :subcategory :assertion-failed}

   ;; Unicode/Encoding Errors
   {:pattern     #"^UnicodeDecodeError:"
    :category    :runtime-error
    :subcategory :unicode-decode-error}
   {:pattern     #"^UnicodeEncodeError:"
    :category    :runtime-error
    :subcategory :unicode-encode-error}
   {:pattern     #"^UnicodeError:"
    :category    :runtime-error
    :subcategory :unicode-error}
   {:pattern     #"^timeout"
    :category    :runtime-error
    :subcategory :timeout}])
