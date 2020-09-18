<a href="https://www.taoensso.com" title="More stuff by @ptaoussanis at www.taoensso.com">
<img src="https://www.taoensso.com/taoensso-open-source.png" alt="Taoensso open-source" width="400"/></a>

**[CHANGELOG]** | [API] | current [Break Version]:

```clojure
[com.taoensso/tufte "2.2.0"] ; See CHANGELOG for details
```

<!-- ![build status](https://github.com/ptaoussanis/tufte/workflows/build/badge.svg?branch=master) -->

> See [here](https://taoensso.com/clojure/backers) if you're interested in helping support my open-source work, thanks! - Peter Taoussanis

# Tufte

### Simple profiling and performance monitoring for Clojure/Script

![Hero]

> Charles Joseph Minard's _Carte Figurative_, one of [Edward Tufte][]'s favourite examples of good data visualization.

## 10-second example

```clojure
(require '[taoensso.tufte :as tufte :refer (defnp p profiled profile)])

;; We'll request to send `profile` stats to `println`:
(tufte/add-basic-println-handler! {})

;;; Let's define a couple dummy fns to simulate doing some expensive work
(defn get-x [] (Thread/sleep 500)             "x val")
(defn get-y [] (Thread/sleep (rand-int 1000)) "y val")

;; How do these fns perform? Let's check:

(profile ; Profile any `p` forms called during body execution
  {} ; Profiling options; we'll use the defaults for now
  (dotimes [_ 5]
    (p :get-x (get-x))
    (p :get-y (get-y))))

;; The following will be printed to *out*:
;;
;;       pId  nCalls       Min     50% ≤     90% ≤     95% ≤     99% ≤       Max      Mean  MAD  Clock Total
;;
;;    :get-y       5   94.01ms  500.99ms  910.14ms  910.14ms  910.14ms  910.14ms  580.49ms ±45%  2.90s   53%
;;    :get-x       5  503.05ms  504.68ms  504.86ms  504.86ms  504.86ms  504.86ms  504.37ms  ±0%  2.52s   46%
;;
;; Accounted                                                                                     5.42s  100%
;;     Clock                                                                                     5.43s  100%
```

## Features

 * Small, **fast**, cross-platform Clojure/Script codebase
 * **Tiny**, flexible API: `p`, `profiled`, `profile`
 * Great **compile-time elision** and **runtime filtering** support
 * Arbitrary Clojure/Script **form-level** profiling
 * Full support for **thread-local** and **multi-threaded** profiling
 * **Stats are just Clojure maps**: aggregate, **analyse**, log, serialize to db, ...
 * Ideal for **ongoing application performance monitoring** in staging, production, etc. ([example](https://github.com/ptaoussanis/tufte/tree/master/examples/clj)).

## Quickstart

Add the necessary dependency to your project:

```clojure
[com.taoensso/tufte "2.2.0"]
```

And setup your namespace imports:

```clojure
(ns my-clj-ns ; Clojure namespace
  (:require [taoensso.tufte :as tufte :refer (defnp p profiled profile)]))

(ns my-cljs-ns ; ClojureScript namespace
  (:require [taoensso.tufte :as tufte :refer-macros (defnp p profiled profile)]))
```

### Step 1: identify forms you'd like to [sometimes] profile

Just wrap them with `p` and give them an id (namespaced keyword):

```clojure
(defn get-customer-info []
  (let [raw-customer-map (p ::get-raw-customer (fetch-from-db))]
    (p ::enrich-raw-customer
      (do-some-work raw-customer-map))))
```

Tufte will record the execution times of these `p` forms whenever profiling is active. 

**NB**: whether or not profiling is active, a `p` form **always returns its normal body result**.

> This last point is important: you never need to worry about Tufte messing with your return values.

### Step 2: activate profiling of `p` forms

Activate profiling with `profiled` or `profile`:

API        | Return value                | Effect                                      |
---------- | --------------------------- | ------------------------------------------- |
`profiled` | `[<body-result> <?pstats>]` | None                                        |
`profile`  | `<body-result>`             | Sends `<?pstats>` to registered handlers[1] |

**[1]** Register handlers using `(tufte/add-handler! <handler-id> <ns-pattern> <handler-fn>)`

> Handler ideas: save to a db, log, `put!` to a `core.async` channel, filter, aggregate, use for a realtime analytics dashboard, examine for outliers or unexpected behaviour, feed into your other performance/analytics systems, ...

 * Use `profiled` to handle pstats yourself **directly at the callsite**.
 * Use `profile` to queue pstats for handling **later/elsewhere**.

Between the two, you have great flexibility for a wide range of use cases in production and during development/debugging.

## Conditional profiling

Tufte offers extensive facilities to control if and when profiling happens.

Both **compile-time elision** and **runtime filtering** are supported. When both present, they stack.

### Method 1/3: namespace filtering

`p`, `profiled`, and `profile` forms can be elided or filtered based on the **namespace in which they occur**.

Namespace filter | Control with                                                                                                       |
---------------- | ------------------------------------------------------------------------------------------------------------------ |
Runtime          | `tufte/*ns-filter*` (reset with `alter-var-root!`, or rebind with `binding`)                                       |
Compile-time     | `taoensso.tufte.ns-pattern` JVM property or `TAOENSSO_TUFTE_NS_PATTERN` environment variable, both read **as edn** |

`*ns-filter*` can be an arbitrary `(fn may-profile? [ns])`, or a namespace pattern like:

```clojure
"foo.bar.baz"
"foo.bar.*"
#{"foo.bar.*" "some.lib.*"}
{:allow #{"foo.bar.*"} :deny #{"noisy.lib.*"}}
```

See `*ns-filter*` docstring for more info.

### Method 2/3: profiling levels

`p`, `profiled`, and `profile` forms can take an optional **profiling level** ∈ `#{0 1 2 3 4 5}`, e.g.:

```clojure
(profiled {:level 3} ...) ; Only activates profiling when (>= 3 *min-level*)
```

These form levels will be checked against `tufte/*min-level*` which can be an constant integer, or a `[[<ns-pattern> <min-level-int>] ... ["*" <default-min-level-int>]]` for namespace-specific levels.

See `*min-level*` docstring for more info.

Min level    | Control with                                                                                                     |
------------ | ---------------------------------------------------------------------------------------------------------------- |
Runtime      | `tufte/*min-level*` (reset with `alter-var-root!`, or rebind with `binding`)                                     |
Compile-time | `taoensso.tufte.min-level` JVM property or `TAOENSSO_TUFTE_MIN_LEVEL` environment variable, both read **as edn** |

### Method 3/3: arbitrary runtime conditions

Finally, `profiled` and `profile` both support an optional arbitrary test expression:

```clojure
(profiled {:when my-cond?} ...) ; Only activates profiling when `my-cond?` is truthy
```

This can be used for a wide range of sophisticated behaviour including smart, **application-aware rate limiting**.

As one simpler example, we can get **sampled profiling** like this:

```clojure
(profiled {:when (tufte/chance 0.5)} ...) ; Only activates profiling with 50% probability
```

## Format pstats options

`tufte/format-pstats` takes an optional map as a second parameter to control formatting:

```clojure
{;; Vector of ordered columns to include in output (all columns by default):
 :columns [:n-calls :min :p50 :p90 :p95 :p99 :max :mean :mad :clock :total]

 ;; Function called on each form id (pid), allowing format customization:
 :format-id-fn #_str (tufte/format-id-abbr) ; For abbreviated ids, see docstring for details

 ;; Allows for custom sorting of results:
 :sort-fn (fn [m] (get m :sum))}
```

If you're using `tufte/add-basic-println-handler!`, you can control formatting
through the `:format-pstats-opts` option:

```clojure
(tufte/add-basic-println-handler!
  {:format-pstats-opts {:columns [:n-calls :p50 :mean :clock :total]
                        :format-id-fn name}})

(defnp get-x [] (Thread/sleep 500)             "x val")
(defnp get-y [] (Thread/sleep (rand-int 1000)) "y val")

(profile
  {}
  (dotimes [_ 5]
    (get-x)
    (get-y)))

; How does this output look?

;; pId            nCalls      50% ≤       Mean      Clock  Total
;;
;; defn_get-y          5   572.09ms   567.82ms     2.84s     53%
;; defn_get-x          5   500.08ms   500.13ms     2.50s     47%
;;
;; Accounted                                       5.34s    100%
;; Clock                                           5.34s    100%
```

## Example: monitoring Clojure application performance

Please see the `add-accumulating-handler!` docstring for an example of one common/convenient way to do this.

There's also an [example project](https://github.com/ptaoussanis/tufte/tree/master/examples/clj).

## FAQ

### How to use this for ongoing application performance monitoring?

The `pstats` objects generated from Tufte's `profiled` or `profile` calls are **~losslessly mergeable**.

This gives you a lot of flexibility re: integrating Tufte as an **ongoing performance monitoring tool**.

As one example, suppose you have an HTTP application that you'd like to monitor+optimize for response times:

- Wrap each endpoint with a sampling call to `profile`. (E.g. with a Ring middleware).
- Your `profile` handler can accumulate (merge) pstats into a buffer.
- Every n minutes, drain the buffer and log endpoint performance to a db.
- Trigger alarms if any performance info (e.g. 95th percentile response times) are out of spec. The accumulated pstats info will also be helpful in quickly diagnosing a cause.

### How's the performance in production?

Tufte's designed specifically to support ongoing use in production, and is **highly optimized**: its overhead is on the order of a couple nanoseconds per wrapping.

If something's remotely worth profiling, Tufte's overhead should be completely insignificant.

Also, keep in mind that Tufte's **conditional profiling** gives you complete control over if and when you do pay (however little) for profiling.

### Why not just use [YourKit], [JProfiler], or [VisualVM]?

The traditional recommendation for Clojure profiling has usually been to use a standard JVM profiling tool like one of the above.

And they can certainly do the job, but they also tend to be a little hairy: requiring special effort to use, and often producing gobs of information that can be difficult or time-consuming to meaningfully interpret.

In contrast, Tufte offers some interesting benefits:

 * A **cross-platform API** that works seamlessly between your server (Clj) and client (Cljs) applications
 * Arbitrary **application-aware, form-level** profiling; measure [just] what you care about at the application level
 * Simple **thread-local or multi-threaded semantics**
 * During dev/debug: check performance **right from within your REPL**
 * During production: **ongoing, application-aware** conditional profiling, logging, and analysis (stats are just **Clojure maps**)

Note that JVM profiling tools can still be very handy. Tufte doesn't offer memory profiling for example, and it's not well suited to forensic or very low-level profiling.

If you want to know `clojure.lang.Numbers$LongOps.lt(Number, Number)` or memory stats, you'll want a JVM tool. If you want to know `my-fn` stats, or you want ongoing stats in production - Tufte could be a good fit.

### How does Tufte compare to the profiling in [@ptaoussanis/Timbre]?

Actually, I developed Tufte one weekend while refactoring Timbre's profiling. It's basically a refinement of the ideas from there.

Decided that I could make some worthwhile improvements with some breaking API changes and a new set of dedicated docs. Tufte's implementation is cross-platform, considerably faster, and its API more flexible.

With the release of Tufte, I'll be **deprecating Timbre's profiling features**.

Note that Tufte's a feature **superset** of Timbre's profiling, so porting should be straightforward:

API        | Timbre              | Tufte                              |
---------- | ------------------- | ---------------------------------- |
`p`        | `[id & body]`       | `[id & body]`, `[opts & body]`     |
`profile`  | `[level id & body]` | `[opts & body]`                    |
`profiled` | `[level id & body]` | `[opts & body]`                    |
`profile`  | Output -> log       | Output -> arbitrary handler-fn [1] |

**[1]** See `tufte.timbre/add-timbre-logging-handler!` for directing Tufte's `profile` output to Timbre.

### How does Tufte compare to [@hugoduncan/Criterium]?

Basically, they serve different use cases: **benchmarking** for Criterium, and **profiling** for Tufte.

Benchmarking measures performance from the outside. Profiling measures performance from the inside (using some kind of instrumentation).

Essentially: benchmarking is about measuring how long something takes, while profiling is about measuring how long something takes, **and understanding why**.

Both can be used for performance measurement + comparison, and both can be used for performance optimization. The main tradeoff is: profiling generally provides deeper information at the cost of increased setup effort (instrumentation).

| Library   | Measures                 | Use for                       | During    | Emphasis           |
| --------- | ------------------------ | ----------------------------- | --------- | ------------------ |
| Criterium | 1 Clojure form           | Benchmarking                  | Dev       | Accuracy           |
| Tufte     | >=1 Clojure/Script forms | Profiling, basic benchmarking | Dev, prod | Flexibility, speed |

So Criterium produces very accurate stats for a _single_ Clojure expression while Tufte produces _combined stats_ for an _arbitrary_ number of Clojure/Script expressions, possibly over time.

For example:

 * Use **Criterium** for a one-off measurement or comparison of the performance of two libraries.
 * Use **Tufte** to measure or monitor the performance of various parts of your system and how they relate.

### What's the difference between thread-local and dynamic (multi-threaded) profiling?

If you don't already know the difference, you probably want **thread-local profiling** (the default). It's faster and conceptually simpler: it literally just profiles what happens sequentially on the current thread.

Work being done concurrently in futures and agents will be ignored.

In contrast, **dynamic profiling** works across thread boundaries using Clojure's standard `^:dynamic` binding conveyance as in:

```clojure
(def ^:dynamic *my-dynamic-var* nil)
(binding [*my-dynamic-var* "foobar!"] ; This val will be available across Clojure threads
  (future (println [:thread1 *my-dynamic-var*]))
  (future (println [:thread2 *my-dynamic-var*]))
  (println [:thread3 *my-dynamic-var*])
  (Thread/sleep 100))
  
;; %> "foobar!", "foobar!", "foobar!"
```

### How do I get dynamic (multi-threaded) profiling?

`profiled` and `profile` have a `:dynamic?` option:

```clojure
(profiled {:dynamic? true} ...) ; Activates dynamic (multi-threaded) profiling
```

This works through Clojure's standard `^:dynamic` binding conveyance.

If you really want to get fancy, you can also do _manual_ multi-threaded profiling using `tufte/stats-accumulator`.

### What's the difference between Clock Time and Accounted Time?

> This question refers to the values reported by the `format-pstats` util

**Clock time** is just the total real-world time that elapsed between the start and end of a `profiled` or `profile` call. This is the amount of time that you'd have seen pass on a stopwatch in your hand.

**Accounted time** is the total execution time tracked by all `p` forms during the same period. It can be:

Outcome                    | Meaning                                                 |
-------------------------- | ------------------------------------------------------- |
`(< accounted clock-time)` | Some work was done that wasn't tracked by any `p` forms |
`(> accounted clock-time)` | Nested `p` forms, and/or multi-threaded profiling[1]    |

**[1]** For example: if you're doing concurrent work on 6 threads, then you can do 6ms of work for each 1ms of clock time.

### What if I want to time something across a promise / async handler / etc.?

A low-level util (`capture-time!`) is provided for this and similar use cases. See its docstring for more info.

## Contacting me / contributions

Please use the project's [GitHub issues page] for all questions, ideas, etc. **Pull requests welcome**. See the project's [GitHub contributors page] for a list of contributors.

Otherwise, you can reach me at [Taoensso.com]. Happy hacking!

\- [Peter Taoussanis]

## License

Distributed under the [EPL v1.0] \(same as Clojure).  
Copyright &copy; 2016-2020 [Peter Taoussanis].

<!--- Standard links -->
[Taoensso.com]: https://www.taoensso.com
[Peter Taoussanis]: https://www.taoensso.com
[@ptaoussanis]: https://www.taoensso.com
[More by @ptaoussanis]: https://www.taoensso.com
[Break Version]: https://github.com/ptaoussanis/encore/blob/master/BREAK-VERSIONING.md

<!--- Standard links (repo specific) -->
[CHANGELOG]: https://github.com/ptaoussanis/tufte/releases
[API]: http://ptaoussanis.github.io/tufte/
[GitHub issues page]: https://github.com/ptaoussanis/tufte/issues
[GitHub contributors page]: https://github.com/ptaoussanis/tufte/graphs/contributors
[EPL v1.0]: https://raw.githubusercontent.com/ptaoussanis/tufte/master/LICENSE
[Hero]: https://raw.githubusercontent.com/ptaoussanis/tufte/master/hero.png "Title"

<!--- Unique links -->
[Edward Tufte]: https://en.wikipedia.org/wiki/Edward_Tufte
[YourKit]: https://www.yourkit.com/
[JProfiler]: http://www.ej-technologies.com/products/jprofiler/overview.html
[VisualVM]: http://docs.oracle.com/javase/6/docs/technotes/guides/visualvm/index.html
[@ptaoussanis/Timbre]: https://github.com/ptaoussanis/timbre
[@hugoduncan/Criterium]: https://github.com/hugoduncan/criterium
