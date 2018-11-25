(ns taoensso.tufte
  "A simple, fast, monitoring profiler for Clojure/Script.

  Usage: wrap+name interesting body exprs with the `p` macro. Then activate
  profiling of these wrapped exprs using the `profiled` or `profile` macros:

    (profiled {} (p :my-fn (my-fn))) ; Returns [<body-result> <?pstats>]
    (profile  {} (p :my-fn (my-fn))) ; Returns  <body-result>, dispatches
                                     ; ?pstats to any registered handlers.

  Extensive facilities are provided for compile-time elision and runtime
  filtering.

  See the relevant docstrings for more info:
    `p`, `profiled`, `profile`, `add-handler!` ; Core API

    (p        [opts & body] [id & body]) ; e.g. `(p ::my-pid (do-work))`
    (profiled [opts & body])             ; e.g. `(profiled {:level 2} (my-fn))`
    (profile  [opts & body])             ; e.g. `(profiled {:level 2} (my-fn))`

    (add-handler! [handler-id ns-pattern handler-fn])

  How/where to use this library:
    Tufte profiling is highly optimized: even without elision, you can usually
    leave profiling enabled in production (e.g. for sampled profiling, or to
    detect unusual performance behaviour). Tufte's stats maps are well suited
    to programmatic monitoring."

  {:author "Peter Taoussanis (@ptaoussanis)"}

  #?(:clj
     (:require
      [taoensso.encore      :as enc]
      [taoensso.tufte.stats :as stats]
      [taoensso.tufte.impl  :as impl]))

  #?(:clj (:import [taoensso.tufte.impl PStats]))

  #?(:cljs
     (:require
      [taoensso.encore      :as enc  :refer-macros []]
      [taoensso.tufte.stats :as stats]
      [taoensso.tufte.impl  :as impl :refer [PStats]]))

  #?(:cljs (:require-macros [taoensso.tufte :refer [profiled]])))

(enc/assert-min-encore-version [2 85 0])

;;;; Level filtering

;; We distinguish between run and min levels to ensure that it's
;; always possible to set the min-level > any run level (i.e. to
;; disable profiling)
(defn valid-run-level? [x] (if (#{0 1 2 3 4 5}   x) true false))
(defn valid-min-level? [x] (if (#{0 1 2 3 4 5 6} x) true false))

(def ^:private ^:const invalid-run-level-msg         "Invalid Tufte profiling level: should be int e/o #{0 1 2 3 4 5}")
(def ^:private ^:const invalid-min-level-msg "Invalid minimum Tufte profiling level: should be int e/o #{0 1 2 3 4 5 6}")

(defn ^:static valid-run-level [x]
  (or (#{0 1 2 3 4 5} x)
      (throw (ex-info invalid-run-level-msg {:given x :type (type x)}))))

(comment (enc/qb 1e5 (valid-run-level 4))) ; 7.82

(defn ^:static valid-min-level [x]
  (or (#{0 1 2 3 4 5 6} x)
      (throw (ex-info invalid-min-level-msg {:given x :type (type x)}))))

(def ^:dynamic  *min-level* "e/o #{0 1 2 3 4 5 6}" 2)
(defn        set-min-level!
  "Sets root binding of minimum profiling level, e/o #{0 1 2 3 4 5 6}.
    0 => Enable  all profiling.
    6 => Disable all profiling."
  [level]
  (valid-min-level level)
  #?(:cljs (set!             *min-level*        level)
     :clj  (alter-var-root #'*min-level* (fn [_] level))))

(comment (enc/qb 1e6 *min-level*)) ; 25.93

(defmacro with-min-level
  "Executes body with dynamic minimum profiling level, e/o #{0 1 2 3 4 5 6}.
    0 => Enable  all profiling.
    6 => Disable all profiling."
  [level & body]
  (if (integer? level)
    (do
      (valid-min-level level)
      `(binding [*min-level*                ~level ] ~@body))
    `(binding [*min-level* (valid-min-level ~level)] ~@body)))

;;;; Namespace filtering

(def -compile-ns-filter (enc/memoize_ enc/compile-ns-filter))

(def ^:dynamic *ns-filter* "(fn [?ns] -> truthy)." (-compile-ns-filter "*"))

(defn set-ns-pattern!
  "Sets root binding of namespace filter.
  See `compile-ns-filter` docstring for details on `ns-pattern` arg."
  [ns-pattern]
  (let [nsf? (-compile-ns-filter ns-pattern)]
    #?(:cljs (set!             *ns-filter*        nsf?)
       :clj  (alter-var-root #'*ns-filter* (fn [_] nsf?)))))

(defmacro with-ns-pattern
  "Executes body with dynamic namespace filter.
  See `compile-ns-filter` docstring for details on `ns-pattern` arg."
  [ns-pattern & body]
  `(binding [*ns-filter* (-compile-ns-filter ~ns-pattern)]
     ~@body))

(comment
  (def nsf? (compile-ns-filter #{"foo.*" "bar"}))
  (nsf? "foo.bar")
  (with-ns-pattern "foo.baz"    (profiled {} (p {:id "id"} "body")))
  (with-ns-pattern "taoensso.*" (profiled {} (p {:id "id"} "body"))))

;;;; Combo filtering
;; TODO Consider ns-pattern->min-level feature (+ sync with Timbre)

#?(:clj
   (def ^:private compile-time-min-level
     (when-let [level (enc/read-sys-val "TUFTE_MIN_LEVEL")]
       (println (str "Compile-time (elision) Tufte min-level: " level))
       (valid-min-level level))))

#?(:clj
   (def ^:private compile-time-ns-filter
     (let [ns-pattern (enc/read-sys-val "TUFTE_NS_PATTERN")]
       (when ns-pattern
         (println (str "Compile-time (elision) Tufte ns-pattern: " ns-pattern)))
       (-compile-ns-filter (or ns-pattern "*")))))

#?(:clj ; Called only at macro-expansiom time
   (defn -elide?
     "Returns true iff level or ns are compile-time filtered."
     [level-form ns-str-form]
     (not
       (and
         (or ; Level okay
           (nil? compile-time-min-level)
           (not (valid-run-level? level-form)) ; Not a compile-time level const
           (>= ^long level-form ^long compile-time-min-level))

         (or ; Namespace okay
           (not (string? ns-str-form)) ; Not a compile-time ns-str const
           (compile-time-ns-filter ns-str-form))))))

(defn #?(:clj may-profile? :cljs ^boolean may-profile?)
  "Returns true iff level and ns are runtime unfiltered."
  ([level   ] (may-profile? level *ns*))
  ([level ns]
   (if (>=  ^long (valid-run-level level)
         ;; ^long (valid-min-level *min-level*)
            ^long                  *min-level* ; Assume valid
         )
     (if (*ns-filter* ns) true false)
     false)))

(comment (enc/qb 1e5 (may-profile? 2))) ; 14.09

;;;; Output handlers
;; Handlers are used for `profile` output, let us nicely decouple stat
;; creation and consumption.

(defrecord HandlerVal [ns-str level ?id ?data pstats pstats-str_ ?file ?line])

(def      handlers_ "{<handler-id> <handler-fn>}" impl/handlers_)
(defn add-handler!
  "Use this to register interest in stats output produced by `profile` calls.
  Each registered `handler-fn` will be called as:

    (handler-fn {:ns-str _ :level _ :?id _ :?data _ :pstats _ :pstats-str_ _})

  Map args:
    :ns-str      - Namespace string where `profile` call took place
    :level       - Level e/o #{0 1 2 3 4 5}, given in `(profile {:level _} ...)`
    :?id         - Optional id,              given in `(profile {:id    _} ...)`
    :?data       - Optional arb data,        given in `(profile {:data  _} ...)`
    :pstats      - As in `(second (profiled ...))`. Derefable, mergeable.
    :pstats-str_ - `(delay (format-pstats pstats))

  Error handling (NB):
    Handler errors will be silently swallowed. Please `try`/`catch` and
    appropriately deal with (e.g. log) possible errors *within* `handler-fn`.

  Async/blocking:
    `handler-fn` should ideally be non-blocking, or reasonably cheap. Handler
     dispatch occurs through a 1-thread 1k-buffer dropping queue.

  Ns filtering:
    Provide an optional `ns-pattern` arg to only call handler for matching
    namespaces. See `compile-ns-filter` docstring for details on `ns-pattern`.

  Handler ideas:
    Save to a db, log, `put!` to an appropriate `core.async` channel, filter,
    aggregate, use for a realtime analytics dashboard, examine for outliers
    or unexpected output, ..."

  ([handler-id handler-fn] (add-handler! handler-id nil handler-fn))
  ([handler-id ns-pattern handler-fn]
   (let [f (if (or (nil? ns-pattern) (= ns-pattern "*"))
             handler-fn
             (let [nsf? (-compile-ns-filter ns-pattern)]
               (fn [m]
                 (when (nsf? (get m :ns-str))
                   (handler-fn m)))))]
     (set (keys (swap! handlers_ assoc handler-id f))))))

(defn remove-handler! [handler-id]
  (set (keys (swap! handlers_ dissoc handler-id))))

(declare format-pstats)

(defn add-basic-println-handler!
  "Adds a simple handler that logs `profile` stats output with `println`."
  [{:keys [ns-pattern format-columns]
    :or   {ns-pattern "*"
           format-columns stats/all-format-columns}}]

  (enc/have? [:el stats/all-format-columns] :in format-columns)
  (add-handler! :basic-println
    ns-pattern
    (fn [m]
      (let [{:keys [?id ?data pstats]} m]
        (println
          (str
            (when ?id   (str "\nid: "   ?id))
            (when ?data (str "\ndata: " ?data))
            "\n" (format-pstats pstats format-columns)))))))

(comment (add-basic-println-handler! {}))

(declare merge-pstats)

(defn add-aggregate-window-handler
  "Adds a handler that aggregates profiling stats and calls `drain-fn` when the
  aggregate spans `n` seconds.

  After `drain-fn` is invoked, the aggregate is reset, and thus subsequent aggregate
  data is disjoint from any previous aggregate data."
  [{:keys [ns-pattern
           n
           drain-fn]
    :or   {ns-pattern  "*"
           n           300
           drain-fn (fn [pstats] (println (format-pstats pstats stats/all-format-columns)))}}]
  (let [pstats-state (atom nil)
        start-time (atom (enc/now-nano*))
        n (* n 1e9)]
    (add-handler!
      :aggregate-window-handler
      ns-pattern
      (fn [m]
        (let [{:keys [^PStats pstats]} m
              t1 (.-t1 pstats)
              time-spent (- t1 @start-time)]
          (if (>= time-spent n)
            ; drain buffer
            (let [full-window (merge-pstats @pstats-state pstats)]
              (reset! pstats-state nil)
              (reset! start-time t1)
              (drain-fn full-window))
            ; common case: Aggregate pstats
            (swap! pstats-state (fn [s] (merge-pstats s pstats)))))))))

(comment
  (do
    (add-aggregate-window-handler {:n 1})

    (profile {:id :foo1} (p :foo (Thread/sleep 100)))
    (Thread/sleep 900)
    (profile {:id :foo2} (p :foo (Thread/sleep 100)))

    (profile {:id :bar1} (p :bar (Thread/sleep 500)))
    (profile {:id :bar2} (p :bar (Thread/sleep 550)))

    (profile {:id :foobar} (p :foobar (Thread/sleep 1100)))
    ))

;;;; Some low-level primitives

(defn profiling? "Returns e/o #{nil :thread :dynamic}."
  [] (if impl/*pdata* :dynamic (when (impl/pdata-proxy-get) :thread)))

(comment (enc/qb 1e6 (profiling?))) ; 49.69

(def ^:const ^:private default-nmax 8e5)
(defn new-pdata
  "Note: this is a low-level primitive for advanced users!
  Returns a new pdata object for use with `with-profiling` and/or `capture-time!`.
  Deref to get pstats:

    (let [pd (new-pdata)
          t0 (System/nanoTime)]
      (with-profiling pd {}
        (p :foo (Thread/sleep 100))
        (capture-time! pd :bar (- t0 (System/nanoTime))))
      @pd)

  Dynamic (thread-safe) by default.
  *WARNING*: don't change this default unless you're very sure the resulting
  pdata object will not be concurrently modified across threads. Concurrent
  modification will lead to bad data and/or exceptions!"
  ([] (new-pdata nil))
  ([{:keys [dynamic? nmax] :or {dynamic? true nmax default-nmax}}]
   (if dynamic?
     (impl/new-pdata-dynamic nmax)
     (impl/new-pdata-local   nmax))))

(comment
  @@(new-pdata)

  ;; Note that dynamic pdata with non-dyanmic `with-profiling` is fine:
  (let [pd (new-pdata)
        t0 (System/nanoTime)]
    (with-profiling pd {}
      (p :foo (Thread/sleep 100))
      (capture-time! pd :bar (- t0 (System/nanoTime))))
    @pd) ; => pstats
  )

(defmacro with-profiling
  "Note: this is a low-level primitive for advanced users!
  Enables `p` forms in body and returns body's result.

  See `new-pdata` for more info on low-level primitives."
  [pdata {:keys [dynamic? nmax] :or {nmax default-nmax}} & body]
  (if dynamic?
    `(binding [impl/*pdata* ~pdata] (do ~@body))
    `(try
       (impl/pdata-proxy-push ~pdata)
       (do ~@body)
       (finally (impl/pdata-proxy-pop)))))

(defn capture-time!
  "Note: this is a low-level primitive for advanced users!
  Can be useful when tracking time across arbitrary thread boundaries or for
  async jobs / callbacks / etc.

  See `new-pdata` for more info on low-level primitives."
  ([pdata id nano-secs-elapsed] (impl/capture-time! pdata id nano-secs-elapsed))
  ([      id nano-secs-elapsed]
   (when-let [pd (or impl/*pdata* (impl/pdata-proxy-get))]
     (impl/capture-time! pd id nano-secs-elapsed))))

(comment
  @(second
     (profiled {}
       (let [t0 (System/nanoTime)
             _  (Thread/sleep 2200)
             t1 (System/nanoTime)]
         (capture-time! :foo (- t1 t0)))))

  (let [pd (new-pdata)]
    (enc/qb 1e6 (capture-time! pd :foo 100))
    @@pd)

  (let [pd (new-pdata)]
    (with-profiling pd {}
      (p :foo (Thread/sleep 100))
      (p :bar (Thread/sleep 200)))
    @@pd))

;;;; Core macros

#?(:clj
   (defmacro profiled
     "Always executes body, and always returns [<body-result> <?pstats>].

     When [ns level] unelided and [ns level `when`] unfiltered, executes body
     with profiling active.

     Handy if you'd like to consume stats output directly.
     Otherwise see `profile`.

     `pstats` objects are derefable and mergeable:
       - @pstats                 -> {:stats {:n _ :min _ ...} :clock {:t0 _ :t1 _ :total _}}
       - @(merge-pstats ps1 ps2) -> {:stats {:n _ :min _ ...} :clock {:t0 _ :t1 _ :total _}}

     Full set of `:stats` keys:
       :n :min :max :mean :mad :sum :p50 :p90 :p95 :p99

     Compile-time opts:
       :level    - e/o #{0 1 2 3 4 5} ; Default is `5`
       :dynamic? - Use multi-threaded profiling? ; Default is `false`
       :nmax     - ~Max captures per id before compaction ; Default is 8e5
       :when     - Optional arbitrary conditional form (e.g. boolean expr)

     Note on laziness:
       Please note that lazy seqs and other forms of laziness (e.g. delays)
       will only contribute to profiling results if/when evaluation actually
       occurs. This is intentional and a useful property. Compare:

       (profiled {}  (delay (Thread/sleep 2000))) ; Doesn't count sleep
       (profiled {} @(delay (Thread/sleep 2000))) ; Does counts sleep"

     [opts & body]
     (let [ns-str (str *ns*)]

       (when-not (map? opts)
         (throw
           (ex-info "`tufte/profiled` requires a compile-time map as first arg."
             {:ns-str ns-str :line (:line (meta &form))
              :form (cons 'profiled (cons opts body))})))

       (let [level-form (get opts :level    5)
             dynamic?   (get opts :dynamic? false)
             test-form  (get opts :when     true)
             nmax (long (get opts :nmax     default-nmax))]

         (when (integer? level-form) (valid-run-level level-form))

         (if (-elide? level-form ns-str)
           `[(do ~@body)]
           (let [runtime-check
                 (if (= test-form true) ; Common case
                        `(may-profile? ~level-form ~ns-str)
                   `(and (may-profile? ~level-form ~ns-str) ~test-form))]

             (if dynamic?
               `(if ~runtime-check
                  (let [pd# (impl/new-pdata-dynamic ~nmax)]
                    (binding [impl/*pdata* pd#]
                      [(do ~@body) @pd#]))
                  [(do ~@body)])

               `(if ~runtime-check
                  (let [pd# (impl/new-pdata-local ~nmax)]
                    (try
                      (impl/pdata-proxy-push pd#)
                      [(do ~@body) @pd#]
                      (finally (impl/pdata-proxy-pop))))
                  [(do ~@body)]))))))))

(comment (enc/qb 1e6 (profiled {}))) ; 277.51

#?(:clj
   (defmacro profile
     "Always executes body, and always returns <body-result>.

     When [ns level] unelided and [ns level `when`] unfiltered, executes body
     with profiling active and dispatches stats to any registered handlers
     (see `add-handler!`).

     Handy if you'd like to consume/aggregate stats output later/elsewhere.
     Otherwise see `profiled`.

     Compile-time opts:
       :level    - e/o #{0 1 2 3 4 5} ; Default is `5`
       :dynamic? - Use multi-threaded profiling? ; Default is `false`
       :nmax     - ~Max captures per id before compaction ; Default is 8e5
       :when     - Optional arbitrary conditional form (e.g. boolean expr)
       :id       - Optional stats id provided to handlers (e.g. `::my-stats-1`)
       :data     - Optional arbitrary data provided to handlers

     Note on laziness:
       Please note that lazy seqs and other forms of laziness (e.g. delays)
       will only contribute to profiling results if/when evaluation actually
       occurs. This is intentional and a useful property. Compare:

       (profiled {}  (delay (Thread/sleep 2000))) ; Doesn't count sleep
       (profiled {} @(delay (Thread/sleep 2000))) ; Does count sleep"

     [opts & body]
     (let [ns-str (str *ns*)]

       (when-not (map? opts)
         (throw
           (ex-info "`tufte/profile` requires a compile-time map as first arg."
             {:ns-str ns-str :line (:line (meta &form))
              :form (cons 'profile (cons opts body))})))

       (let [level-form (get opts :level 5)
             id-form    (get opts :id)
             data-form  (get opts :data)]

         (when (integer? level-form) (valid-run-level level-form))

         `(let [[result# pstats#] (profiled ~opts ~@body)]
            (when pstats#
              (impl/handle!
                (HandlerVal. ~ns-str ~level-form ~id-form ~data-form
                  pstats# (delay (format-pstats pstats#))
                  ~*file* ~(:line (meta &form)))))
            result#)))))

(comment
  (profiled {} "body")
  (profiled {:when (chance 0.5)} "body")
  (profile  {:id ::my-id} "body"))

#?(:clj
   (defmacro p
     "Profiling spy. Always executes body, and always returns <body-result>.

     When [ns level] unelided and profiling is active, records execution
     time of body.

     Compile-time opts:
      :id    - Id for this body in stats output (e.g. `::my-fn-call`)
      :level - e/o #{0 1 2 3 4 5} ; Default is `5`"

     {:arglists '([id & body] [opts & body])}
     [s1 & body]
     (let [ns-str  (str *ns*)
           opts    (if (map? s1) s1 {:level 5 :id s1})
           level   (get opts :level)
           id-form (get opts :id)]

       ;; If *any* level is present, it must be a valid compile-time level
       ;; since this macro doesn't offer runtime level checking
       (when level (valid-run-level level))

       (when (nil? id-form)
         (throw
           (ex-info "`tufte/p` requires an id."
             {:ns-str ns-str :line (:line (meta &form))
              :opts opts
              :form (cons 'p (cons s1 body))})))

       (if (-elide? level ns-str)
         `(do ~@body)
         ;; Note no runtime `may-profile?` check
         `(let [~'__pd-dynamic impl/*pdata*]
            (if-let [~'__pd (or ~'__pd-dynamic (impl/pdata-proxy-get))]
              (let [~'__t0     (enc/now-nano*)
                    ~'__result (do ~@body)
                    ~'__t1     (enc/now-nano*)]

                ;; Note that `capture-time!` expense is excl. from p time
                (impl/capture-time! ~'__pd ~id-form (- ~'__t1 ~'__t0))

                ~'__result)
              (do ~@body)))))))

#?(:clj (defmacro pspy "`p` alias" [& args] `(p ~@args)))

(comment
  (p :p1 "body")
  (profiled {} (p :p1))
  (profiled {} (p {:level 5 :id :p1}))
  (profiled {} (p (let [x :foo/id] x) "body"))
  (enc/qb 1e5  (profiled {} 2 (p :p1))) ; 39.5
  (enc/time-ms (profiled {} 2 (enc/qb 1e6 (p :p1)))) ; 3296
  (profiled {:level 2 :when (chance 0.5)} (p :p1 "body"))
  (profiled {} (p :foo (p :bar))))

;;;; Public user utils

(defn compile-ns-filter
  "Returns (fn [?ns]) -> truthy. Some example patterns:
    \"foo.bar\", \"foo.bar.*\", #{\"foo\" \"bar\"},
    {:whitelist [\"foo.bar.*\"] :blacklist [\"baz.*\"]}"
  [ns-pattern] (enc/compile-ns-filter ns-pattern))

(defn chance "Returns true with 0<`p`<1 probability."
  [p] (< ^double (rand) (double p)))

#?(:clj
   (defn refer-tufte
     "(require '[taoensso.tufte :as tufte :refer [defnp p profiled profile]])"
     [] (require '[taoensso.tufte :as tufte :refer [defnp p profiled profile]])))

(comment (refer-tufte))

(defn merge-pstats
  "Lossless unless data to merge are very large."
  ([       ] nil)
  ([ps0    ] ps0)
  ([ps0 ps1] (impl/merge-pstats ps0 ps1)))

(defn format-pstats
  "Formats given pstats to a string table.
    Accounted < Clock => Some work was done that wasn't tracked by any p forms.
    Accounted > Clock => Nested p forms, and/or parallel threads."
  [ps & [{:keys [columns] :or {columns stats/all-format-columns}}]]
  (when ps
    (let [{:keys [clock stats]} (if (instance? PStats ps) @ps ps)
          sort-fn (fn [id m] (get m :sum))]
      (stats/format-stats (get clock :total) stats sort-fn columns))))

(comment
  ;; [:n-calls :min :p50 :p90 :p95 :p99 :max :mean :mad :clock :total]
  (println
    (str "\n"
      (format-pstats
        (second
          (profiled {}
            (p :foo (Thread/sleep 200))
            (p :bar (Thread/sleep 500))
            (do     (Thread/sleep 800))))
        {:columns [:clock :p50 :p95]}))))

;;;; fnp stuff

(defn- fn-sigs [def? fn-name sigs]
  (let [single-arity? (vector? (first sigs))
        sigs    (if single-arity? (list sigs) sigs)
        prepend (if def? "defn_" "fn_")
        get-id  (if single-arity?
                  (fn [fn-name _params] (keyword (str *ns*) (str prepend (name fn-name))))
                  (fn [fn-name  params] (keyword (str *ns*) (str prepend (name fn-name) \_ (count params)))))
        new-sigs
        (map
          (fn [[params & others]]
            (let [has-prepost-map?      (and (map? (first others)) (next others))
                  [?prepost-map & body] (if has-prepost-map? others (cons nil others))]
              (if ?prepost-map
                `(~params ~?prepost-map (p ~(get-id fn-name params) ~@body))
                `(~params               (p ~(get-id fn-name params) ~@body)))))
          sigs)]
    new-sigs))

(defmacro fnp "Like `fn` but wraps fn bodies with `p` macro."
  {:arglists '([name?  [params*] prepost-map? body]
               [name? ([params*] prepost-map? body)+])}
  [& sigs]
  (let [[?fn-name sigs] (if (symbol? (first sigs)) [(first sigs) (next sigs)] [nil sigs])
        new-sigs        (fn-sigs (not :def) (or ?fn-name (gensym "")) sigs)]
    (if ?fn-name
      `(fn ~?fn-name ~@new-sigs)
      `(fn           ~@new-sigs))))

(comment
  (fn-sigs "foo"       '([x]            (* x x)))
  (macroexpand '(fnp     [x]            (* x x)))
  (macroexpand '(fn      [x]            (* x x)))
  (macroexpand '(fnp bob [x] {:pre [x]} (* x x)))
  (macroexpand '(fn      [x] {:pre [x]} (* x x))))

(defmacro defnp "Like `defn` but wraps fn bodies with `p` macro."
  {:arglists
   '([name doc-string? attr-map?  [params*] prepost-map? body]
     [name doc-string? attr-map? ([params*] prepost-map? body)+ attr-map?])}
  [& sigs]
  (let [[fn-name sigs] (enc/name-with-attrs (first sigs) (next sigs))
        new-sigs       (fn-sigs :def fn-name sigs)]
    `(defn ~fn-name ~@new-sigs)))

(comment
  (defnp foo "Docstring"                [x]   (* x x))
  (macroexpand '(defnp foo "Docstring"  [x]   (* x x)))
  (macroexpand '(defn  foo "Docstring"  [x]   (* x x)))
  (macroexpand '(defnp foo "Docstring" ([x]   (* x x))
                                       ([x y] (* x y))))
  (profiled {} (foo 5)))

;;;; Stats accumulators (experimental)

(deftype StatsAccumulator [pstats_]
  #?@(:clj  [clojure.lang.IDeref  (deref [_] @pstats_)]
      :cljs [             IDeref (-deref [_] @pstats_)])
  #?@(:clj  [clojure.lang.IFn  (invoke [_ ps1] (swap! pstats_ (fn [ps0] (merge-pstats ps0 ps1))))]
      :cljs [             IFn (-invoke [_ ps1] (swap! pstats_ (fn [ps0] (merge-pstats ps0 ps1))))]))

(defn stats-accumulator
  "Experimental, subject to change!
  Small util to help collect (merge) pstats from multiple runs or threads.

  Returns a stateful StatsAccumulator, `acc`:
    - `@acc`           ; Returns current pstats
    - `(acc <pstats>)` ; Synchronously merges given pstats into accumulator

  Note that you may want some kind of async/buffer/serialization mechanism in
  front of merge calls (e.g. an agent)."
  ([init-pstats] (StatsAccumulator. (atom init-pstats)))
  ([           ] (StatsAccumulator. (atom nil))))

(defn accumulate-stats "Experimental, subject to change!"
  [stats-accumulator [result ?pstats]]
  (when ?pstats (stats-accumulator ?pstats))
  result)

(comment
  (enc/qb 1e6 (stats-accumulator)) ; 66.75
  (let [sacc (stats-accumulator)]
    (accumulate-stats sacc (profiled {} (p :p1)))
    (accumulate-stats sacc (profiled {} (p :p2)))
    @@sacc))

;;;;

(comment
  (add-basic-println-handler! {})
  (defn sleepy-threads []
    (dotimes [n 5]
      (Thread/sleep 100) ; Unaccounted
      (p :future/outer @(future (Thread/sleep 500)))
      @(future (p :future/inner (Thread/sleep 500)))
      (p :1ms  (Thread/sleep 1))
      (p :2s   (Thread/sleep 2000))
      (p :50ms (Thread/sleep 50))
      (p :rand (Thread/sleep (if (> 0.5 (rand)) 10 500)))
      (p :10ms (Thread/sleep 10))
      "Result"))

  (profile {:level 2 :id ::sleepy :data "foo"}    (sleepy-threads))
  (profile {:level 2 :id ::sleepy :dynamic? true} (sleepy-threads))
  (p :hello "Hello, this is a result") ; Falls through (no pdata context)

  (defnp arithmetic
    []
    (let [nums (vec (range 1000))]
      (+ (p :fast-sleep (Thread/sleep 1) 10)
         (p :slow-sleep (Thread/sleep 2) 32)
         (p :add  (reduce + nums))
         (p :sub  (reduce - nums))
         (p :mult (reduce * nums))
         (p :div  (reduce / nums)))))

  (profiled {} "foo")
  (profile  {} (dotimes [n 100] (arithmetic)))
  (profile  {} (dotimes [n 1e5] (p :p1 nil)))
  (profile  {} (dotimes [n 1e6] (p :p1 nil)))
  (profiled {} (dotimes [n 1e6] (p :p1 nil)))
  (profiled {:level 2 :when (chance 0.5)} "body")

  @(second (profiled {:nmax 10000 :dynamic? true} (dotimes [n 200] (p :p1 nil))))

  (profile {})
  (profile {:nmax 10}                (dotimes [n 200] (p :p1 nil)))
  (profile {:nmax 10 :dynamic? true} (dotimes [n 200] (p :p1 nil)))
  (profile {}
    (p :foo
      (do       (Thread/sleep 100))
      (p :foo/a (Thread/sleep 120))
      (p :foo/b (Thread/sleep 220))))

  (println "\n" (format-pstats (second (profiled {} (p :p1 (p :p2 (p :p3 "foo")))))))
  (println "\n"
    (time
      (format-pstats
        @(let [[_ ps0] (profiled {} (dotimes [_ 1e6] (p :foo "foo")))
               [_ ps1] (profiled {} (dotimes [_ 1e6] (p :foo "foo")))
               [_ ps2] (profiled {} (dotimes [_ 500] (p :bar "bar")))]
           (reduce (partial impl/merge-pstats 1e4) [ps0 ps1 ps2])))))

  (println
    (format-pstats
      (second
        (profiled {} (p :foo (Thread/sleep 100)))))))
