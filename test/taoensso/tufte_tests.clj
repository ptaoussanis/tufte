(ns taoensso.tufte-tests
  (:require
   [clojure.test    :as test  :refer [is]]
   [clojure.string  :as str]
   [taoensso.encore :as enc]
   [taoensso.tufte  :as tufte :refer [profiled profile p]]
   [taoensso.tufte.impl :as impl])
  (:import [taoensso.tufte.impl PState PData PStats TimeSpan]))

(comment
  (remove-ns      'taoensso.tufte-tests)
  (test/run-tests 'taoensso.tufte-tests))

(defn ps? [x] (instance? PStats x))

(defmacro looped "Like `dotimes` but returns final body result."
  [n & body]
  `(let [n# (long ~n)]
     (when (> n# 0)
       (loop [i# 1]
         (let [result# (do ~@body)]
           (if (< i# n#)
             (recur (inc i#))
             result#))))))

(comment (looped 10 (println "x") "x"))

(test/deftest profiled-basics
  (test/testing "Profiled/basics"
    (let [[r ps] (profiled {})]                     (is (= r  nil))  (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))
    (let [[r ps] (profiled {:dynamic? true})]       (is (= r  nil))  (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))
    (let [[r ps] (profiled {}               "foo")] (is (= r "foo")) (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))
    (let [[r ps] (profiled {:dynamic? true} "foo")] (is (= r "foo")) (is (ps? ps)) (is (:clock @ps)) (is (nil? (:stats @ps))))

    (let [[r ps] (profiled {:when false}                "foo")] (is (= r "foo")) (is (nil? ps)))
    (let [[r ps] (profiled {:when false :dynamic? true} "foo")] (is (= r "foo")) (is (nil? ps)))))

(test/deftest capture-basics
  (test/testing "Capture/basics"

    (is (= (p :foo "foo")          "foo"))
    (is (= (p :foo (p :bar "bar")) "bar"))

    (let [[r ps] (profiled {:when false}                (p :foo "foo"))] (is (= r "foo")) (is (nil? ps)))
    (let [[r ps] (profiled {:when false :dynamic? true} (p :foo "foo"))] (is (= r "foo")) (is (nil? ps)))

    (let [[r ps] (profiled {}               (p :foo "foo"))] (is (= r "foo")) (is (ps? ps)) (is (= (get-in @ps [:stats :foo :n]) 1)))
    (let [[r ps] (profiled {:dynamic? true} (p :foo "foo"))] (is (= r "foo")) (is (ps? ps)) (is (= (get-in @ps [:stats :foo :n]) 1)))

    (let [[r ps]
          (profiled {}
            (looped 100 (p :foo "foo"))
            (looped 50  (p :bar "bar"))
            (looped 10  (p :foo "foo")))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 50)))

    (let [[r ps]
          (profiled {:dynamic? true}
            (looped 100 (p :foo "foo"))
            (looped  50 (p :bar "bar"))
            (looped  10 (p :foo "foo")))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 50)))))

(test/deftest capture-nested
  (test/testing "Capture/nested"

    (let [[r ps] (profiled {:when false}                (p :foo (p :bar (p :baz "baz"))))] (is (= r "baz") (is (nil? ps))))
    (let [[r ps] (profiled {:when false :dynamic? true} (p :foo (p :bar (p :baz "baz"))))] (is (= r "baz") (is (nil? ps))))

    (let [[r ps]
          (profiled {}
            (looped 100 (p :foo (p :bar "bar")))
            (looped  10 (p :bar (p :bar "bar")))
            (looped   5 (p :foo (p :bar (p :foo "foo")))))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 125)))

    (let [[r ps]
          (profiled {:dynamic? true}
            (looped 100 (p :foo (p :bar "bar")))
            (looped  10 (p :bar (p :bar "bar")))
            (looped   5 (p :foo (p :bar "bar" (p :foo "foo")))))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 110))
      (is (= (get-in @ps [:stats :bar :n]) 125)))))

(test/deftest capture-threaded
  (test/testing "Capture/threaded"

    (let [[r ps]
          (profiled {}
            (future (p :foo))
            (future (p :bar))
            (Thread/sleep 100)
            (p :foo "foo"))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 1))
      (is (= (get-in @ps [:stats :bar :n]) nil)))

    (let [[r ps]
          (profiled {:dynamic? true}
            (future (p :foo))
            (future (p :bar))
            (Thread/sleep 100)
            (p :foo "foo"))]

      (is (= r "foo"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 2))
      (is (= (get-in @ps [:stats :bar :n]) 1)))))

(test/deftest merging-basics
  (test/testing "Merging/basics"

    ;; Note mixed dynamic/non-dynamic
    (let [[_ ps0] (profiled {:dynamic? true} (looped 100 (p :foo) (p :bar)))
          [_ ps1] (profiled {}               (looped  20 (p :foo)))
          [_ ps2] (profiled {:dynamic? true} (looped  30 (p :baz)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]
      (is (ps? ps3))
      (is (= (get-in @ps3 [:stats :foo :n]) 120))
      (is (= (get-in @ps3 [:stats :bar :n]) 100))
      (is (= (get-in @ps3 [:stats :baz :n])  30)))

    ;; Invert dynamic/non-dynamic
    (let [[_ ps0] (profiled {}               (looped 100 (p :foo) (p :bar)))
          [_ ps1] (profiled {:dynamic? true} (looped  20 (p :foo)))
          [_ ps2] (profiled {}               (looped  30 (p :baz)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]
      (is (ps? ps3))
      (is (= (get-in @ps3 [:stats :foo :n]) 120))
      (is (= (get-in @ps3 [:stats :bar :n]) 100))
      (is (= (get-in @ps3 [:stats :baz :n])  30))))

  (test/testing "ps1 starting before ps0 should sum clock total correctly, Ref. #48"
    (let [f2 (future                    (profiled {} (p :p1 (Thread/sleep 1500)))) ; Starts first, ends after
          f1 (future (Thread/sleep 800) (profiled {} (p :p2 (Thread/sleep  500))))
          m  @(tufte/merge-pstats (second @f1) (second @f2))]
      (is (>= (-> (long (get-in m [:clock :total])) (/ 1e8) (Math/round) (* 100)) 1400 )))))

(test/deftest compaction-capture
  (test/testing "Compaction/capture"

    (let [[r ps]
          (profiled {:nmax 10}
            (looped 100 (p :foo))
            (looped  50 (p :foo (p :bar (p :baz))))
            (looped   2 (p :qux "qux")))]

      (is (= r "qux"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 150))
      (is (= (get-in @ps [:stats :bar :n])  50))
      (is (= (get-in @ps [:stats :baz :n])  50))
      (is (= (get-in @ps [:stats :tufte/compaction :n]) (/ 250 10)))
      (is (= (count (.-acc ^PState @(.-pstate_ ^PData (.-pd ^PStats ps)))) 2)))

    (let [[r ps]
          (profiled {:nmax 10 :dynamic? true}
            (looped 100 (p :foo))
            (looped  50 (p :foo (p :bar (p :baz))))
            (looped   2 (p :qux "qux")))]

      (is (= r "qux"))
      (is (ps? ps))
      (is (= (get-in @ps [:stats :foo :n]) 150))
      (is (= (get-in @ps [:stats :bar :n])  50))
      (is (= (get-in @ps [:stats :baz :n])  50))
      (is (= (get-in @ps [:stats :tufte/compaction :n]) (/ 250 10)))
      (is (= (count @(.-acc ^PState @(.-pstate_ ^PData (.-pd ^PStats ps)))) 2)))))

(test/deftest compaction-merge
  (test/testing "Compaction/merge"

    ;; Note mixed dynamic/non-dynamic
    (let [[_ ps0] (profiled {:dynamic? true :nmax 10} (looped 100 (p :foo)) (looped 50 (p :foo (p :bar))))
          [_ ps1] (profiled {}                        (looped  20 (p :foo)))
          [_ ps2] (profiled {:dynamic? true :nmax 5}  (looped  10 (p :bar)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]

      (is (= (get-in @ps0 [:stats :tufte/compaction :n]) 19))
      (is (= (get-in @ps1 [:stats :tufte/compaction :n]) nil))
      (is (= (get-in @ps2 [:stats :tufte/compaction :n]) 1))

      (is (= (get-in @ps3 [:stats :foo :n]) 170))
      (is (= (get-in @ps3 [:stats :bar :n])  60))
      (is (= (get-in @ps3 [:stats :tufte/compaction :n]) 20)) ; Merging does uncounted compaction
      )

    ;; Invert dynamic/non-dynamic
    (let [[_ ps0] (profiled {:nmax 10}       (looped 100 (p :foo)) (looped 50 (p :foo (p :bar))))
          [_ ps1] (profiled {:dynamic? true} (looped  20 (p :foo)))
          [_ ps2] (profiled {:nmax 5}        (looped  10 (p :bar)))
          ps3 (reduce tufte/merge-pstats [nil ps0 nil nil ps1 ps2 nil])]

      (is (= (get-in @ps0 [:stats :tufte/compaction :n]) 19))
      (is (= (get-in @ps1 [:stats :tufte/compaction :n]) nil))
      (is (= (get-in @ps2 [:stats :tufte/compaction :n]) 1))

      (is (= (get-in @ps3 [:stats :foo :n]) 170))
      (is (= (get-in @ps3 [:stats :bar :n])  60))
      (is (= (get-in @ps3 [:stats :tufte/compaction :n]) 20)) ; Merging does uncounted compaction
      )

    ;; [#54] merging PStats with times still (only) in accumulator
    (let [[_ ps0] (profiled {:nmax 10} (p :foo))
          ps (enc/reduce-n (fn [ps _] (tufte/merge-pstats ps ps0)) nil 1000)]

      (is (<= (count (:foo (.-id_times ^PState (.-pstate_ ^PData (.-pd ^PStats ps))))) 10))
      (is (<= (count (:foo (.-id_stats ^PState (.-pstate_ ^PData (.-pd ^PStats ps))))) 10)))))

(defn- pstats-tspan [t0 t1]
  (let [pd (PData. 8e5 t0 (PState. nil nil nil))
        time-span [(TimeSpan. t0 t1)]]
    (PStats. pd t1 time-span (delay (#'impl/deref-pstats pd t1 time-span)))))

(test/deftest merge-time-spans
  (test/testing "Merge discrete time-spans"
    (is (= 13 (get-in @(tufte/merge-pstats (pstats-tspan 0 6) (pstats-tspan 10 17)) [:clock :total])))
    (is (=  5 (get-in @(tufte/merge-pstats (pstats-tspan 1 3) (pstats-tspan  3  6)) [:clock :total]))))

  (test/testing "Merge overlapping time-spans"
    (is (=  9 (get-in @(tufte/merge-pstats (pstats-tspan 1 10) (pstats-tspan 3  6)) [:clock :total])))
    (is (= 11 (get-in @(tufte/merge-pstats (pstats-tspan 0 10) (pstats-tspan 7 11)) [:clock :total])))
    (is (= 16
          (get-in
            @(reduce tufte/merge-pstats
               [(pstats-tspan 10 14)
                (pstats-tspan  4 18)
                (pstats-tspan 19 20)
                (pstats-tspan 19 20)
                (pstats-tspan 13 20)])
            [:clock :total]))))

  (test/testing "Accurate clock time even when merging non-increasing t0s" ; #52
    (let [sacc (tufte/stats-accumulator)
          _    (do                        (sacc :g1 (second (profiled {:id :foo} (p :p1))))) ; 1st-t0
          f1   (future (Thread/sleep 800) (sacc :g1 (second (profiled {:id :foo} (p :p1))))) ; 3rd-t0
          f2   (future                    (sacc :g1 (second (profiled {:id :foo} (p :p1 (Thread/sleep 1000)))))) ; 2nd-t0
          ]

      @f1
      @f2
      (is (>= (/ (double (get-in @(:g1 @sacc) [:clock :total])) 1e6) 1000)))))

(defn add-test-handler! []
  (let [p (promise)]
    (tufte/add-handler! :testing (fn [x] (p x)))
    p))

(test/deftest profile-basics
  (test/testing "Profile/basics"

    (let [pr (add-test-handler!)
          r  (profile {} "foo")
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "foo"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (nil? (:stats @ps))))

    (let [pr (add-test-handler!)
          r  (profile {:dynamic? true} "foo")
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "foo"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (nil? (:stats @ps))))

    (let [pr (add-test-handler!)
          r  (profile {} (future (p :foo)) (Thread/sleep 100) (p :bar "bar"))
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "bar"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (= (get-in @ps [:stats :foo :n]) nil))
      (is (= (get-in @ps [:stats :bar :n]) 1))
      (is (string? @(:pstats-str_ m))))

    (let [pr (add-test-handler!)
          r  (profile {:dynamic? true} (future (p :foo)) (Thread/sleep 100) (p :bar "bar"))
          m  (deref pr 1000 nil)
          ps (:pstats m)]

      (is (= r "bar"))
      (is (ps? ps))
      (is (:clock @ps))
      (is (= (get-in @ps [:stats :foo :n]) 1))
      (is (= (get-in @ps [:stats :bar :n]) 1))
      (is (string? @(:pstats-str_ m))))

    (tufte/remove-handler! :testing)))

(test/deftest profiled-nesting
  (test/testing "Profiled/nesting"

    ;; Local, local
    (let [[[r ps1] ps0]
          (profiled {}
            (p :foo)
            (p :bar
              (profiled {}
                (p :foo)
                (p :baz)
                "qux")))]

      (is (= r "qux"))
      (is (ps? ps0))
      (is (= (get-in @ps0 [:stats :foo :n]) 1))
      (is (= (get-in @ps0 [:stats :baz :n]) nil))

      (is (ps? ps1))
      (is (= (get-in @ps1 [:stats :foo :n]) 1))
      (is (= (get-in @ps1 [:stats :baz :n]) 1)))

    ;; Dynamic, dynamic
    (let [[[r ps1] ps0]
          (profiled {:dynamic? true}
            (p :foo)
            (p :bar
              (profiled {:dynamic? true}
                (p :foo)
                (p :baz)
                "qux")))]

      (is (= r "qux"))
      (is (ps? ps0))
      (is (= (get-in @ps0 [:stats :foo :n]) 1))
      (is (= (get-in @ps0 [:stats :baz :n]) nil))

      (is (ps? ps1))
      (is (= (get-in @ps1 [:stats :foo :n]) 1))
      (is (= (get-in @ps1 [:stats :baz :n]) 1)))

    ;; Dynamic, local
    (let [[[r ps1] ps0]
          (profiled {:dynamic? true}
            (p :foo)
            (p :bar
              (profiled {}
                (p :foo)
                (p :baz)
                "qux")))]

      (is (= r "qux"))
      (is (ps? ps0))
      (is (= (get-in @ps0 [:stats :foo :n]) 2))
      (is (= (get-in @ps0 [:stats :baz :n]) 1))

      (is (ps? ps1))
      (is (= (get-in @ps1 [:stats :foo :n]) nil))
      (is (= (get-in @ps1 [:stats :baz :n]) nil)))

    ;; Local, dynamic
    (let [[[r ps1] ps0]
          (profiled {}
            (p :foo)
            (p :bar
              (profiled {:dynamic? true}
                (p :foo)
                (p :baz)
                "qux")))]

      (is (= r "qux"))
      (is (ps? ps0))
      (is (= (get-in @ps0 [:stats :foo :n]) 1))
      (is (= (get-in @ps0 [:stats :baz :n]) nil))

      (is (ps? ps1))
      (is (= (get-in @ps1 [:stats :foo :n]) 1))
      (is (= (get-in @ps1 [:stats :baz :n]) 1)))))

(test/deftest advanced
  (test/testing "Advanced"

    ;; Capture in `profiled`
    (let [[_ ps] (profiled {} (tufte/capture-time! :foo 100))]
      (is (= (get-in @ps [:stats :foo :n]) 1)))

    ;; Capture to local pdata
    (let [n  (long 2e6)
          pd (tufte/new-pdata)
          _  (looped n (tufte/capture-time! pd :foo 100))]
      (is (= (get-in @@pd [:stats :foo :n]) n)))

    ;; Capture to dynamic pdata from separate threads
    (let [n  (long 100)
          pd (tufte/new-pdata {:dynamic? true :nmax 88})
          f1 (future (looped n (tufte/capture-time! pd :foo 100)))
          f2 (future (looped n (tufte/capture-time! pd :foo 100)))
          f3 (future (looped n (tufte/capture-time! pd :foo 100)))
          f4 (future (looped n (tufte/capture-time! pd :foo 100)))]

      (do @f1 @f2 @f3 @f4)
      (is (= (get-in @@pd [:stats :foo :n]) (* 4 n))))

    ;; local `p`s against local pdata
    (let [pd (tufte/new-pdata         {:dynamic? false})
          _  (tufte/with-profiling pd {:dynamic? false}
               (p :foo (Thread/sleep 100))
               (p :bar (Thread/sleep 200))
               (tufte/capture-time! :baz 100))]

      (is (= (get-in @@pd [:stats :foo :n]) 1))
      (is (= (get-in @@pd [:stats :bar :n]) 1))
      (is (= (get-in @@pd [:stats :baz :n]) 1)))

    ;; local `p`s against dynamic pdata
    (let [pd (tufte/new-pdata         {:dynamic? true})
          _  (tufte/with-profiling pd {:dynamic? false}
               (p :foo (Thread/sleep 100))
               (p :bar (Thread/sleep 200))
               (tufte/capture-time! :baz 100))]

      (is (= (get-in @@pd [:stats :foo :n]) 1))
      (is (= (get-in @@pd [:stats :bar :n]) 1))
      (is (= (get-in @@pd [:stats :baz :n]) 1)))

    ;; dynamic `p`s against dynamic pdata
    (let [pd (tufte/new-pdata         {:dynamic? true})
          _  (tufte/with-profiling pd {:dynamic? true}
               (future (p :foo (Thread/sleep 100)))
                       (p :bar (Thread/sleep 200))
               (tufte/capture-time! :baz 100))]

      (Thread/sleep 100)

      (is (= (get-in @@pd [:stats :foo :n]) 1))
      (is (= (get-in @@pd [:stats :bar :n]) 1))
      (is (= (get-in @@pd [:stats :baz :n]) 1)))))

(test/deftest format-stats-test
  (test/testing "Basic format-stats"
    (let [data {:clock {:total 15}
                :stats {:foo {:n 10000
                              :min 1
                              :p50 2
                              :p90 3
                              :p95 4
                              :p99 5
                              :max 6
                              :mean 7
                              :mad 5.062294599999648
                              :sum 15}}}]

      (test/is
        (= ["pId           nCalls        Min      50% ≤      90% ≤      95% ≤      99% ≤        Max       Mean   MAD      Clock  Total"
            ":foo          10,000     1.00ns     2.00ns     3.00ns     4.00ns     5.00ns     6.00ns     7.00ns  ±72%    15.00ns   100%"
            "Accounted                                                                                                  15.00ns   100%"
            "Clock                                                                                                      15.00ns   100%"]
          (->>
            (tufte/format-pstats data)
            (str/split-lines)
            (remove empty?))))

      (test/is
        (= ["pId           nCalls       Mean      Clock  Total"
            ":foo          10,000     7.00ns    15.00ns   100%"
            "Accounted                          15.00ns   100%"
            "Clock                              15.00ns   100%"]
          (->>
            (tufte/format-pstats data {:columns [:n-calls :mean :clock :total]})
            (str/split-lines)
            (remove empty?))))))

  (test/testing "format-stats with namespaced symbols"
    (let [data {:clock {:total 15}
                :stats {:foo/bar {:n 10000
                                  :min 1
                                  :p50 2
                                  :p90 3
                                  :p95 4
                                  :p99 5
                                  :max 6
                                  :mean 7
                                  :mad 5.062294599999648
                                  :sum 15}}}]

      (test/is
        (= ["pId           nCalls       Mean      Clock  Total"
            ":foo/bar      10,000     7.00ns    15.00ns   100%"
            "Accounted                          15.00ns   100%"
            "Clock                              15.00ns   100%"]
          (->>
            (tufte/format-pstats data {:columns [:n-calls :mean :clock :total]})
            (str/split-lines)
            (remove empty?))))))

  (test/testing "Format seconds"
    (let [data {:clock {:total 2e9}
                :stats {:foo {:n 1 :mean 2e9 :sum 2e9}
                        :bar {:n 1 :mean 15  :sum 15}}}]

      (test/is
        (= ["pId           nCalls       Mean"
            ":foo               1     2.00s "
            ":bar               1    15.00ns"]
          (->>
            (tufte/format-pstats data {:columns [:n-calls :mean]})
            (str/split-lines)
            (remove empty?)
            (take 3))))))

  (test/testing "Format seconds only"
    (let [data {:clock {:total 2e9}
                :stats {:foo {:n 1 :mean 2e9 :sum 2e9}
                        :bar {:n 1 :mean 1e9 :sum 1e9}}}]

      (test/is
        (= ["pId           nCalls       Mean" ; TODO: It would be better if seconds aligned to Mean properly
            ":foo               1     2.00s " ; Current behaviour looks a little strange.
            ":bar               1     1.00s "]
          (->>
            (tufte/format-pstats data {:columns [:n-calls :mean]})
            (str/split-lines)
            (remove empty?)
            (take 3))))))

  (test/testing "Format id fn"
    (let [data {:clock {:total 2e9}
                :stats {:example.hello/foo {:n 1 :mean 2e9 :sum 2e9}}}]
      (test/is
        (= ["pId           nCalls       Mean"
            "foo                1     2.00s "]
           (->>
             (tufte/format-pstats data {:columns [:n-calls :mean]
                                        :format-id-fn name})
             (str/split-lines)
             (remove empty?)
             (take 2)))))))

(test/deftest format-id-abbr-test
  (test/testing "Format id abbr test"
    (test/is (= "foo"               ((tufte/format-id-abbr)   :foo)))
    (test/is (= "e.hello/foo"       ((tufte/format-id-abbr)   :example.hello/defn_foo)))
    (test/is (= "e.hello/foo"       ((tufte/format-id-abbr 1) :example.hello/defn_foo)))
    (test/is (= "e.h.world/foo"     ((tufte/format-id-abbr 1) :example.hello.world/defn_foo)))
    (test/is (= "e.hello.world/foo" ((tufte/format-id-abbr 2) :example.hello.world/defn_foo)))))
