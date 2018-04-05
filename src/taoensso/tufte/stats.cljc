(ns taoensso.tufte.stats
  "Basic stats utils. Private, subject to change."
  (:require [taoensso.encore :as enc]
   #?(:cljs [goog.array])))

(defn long-percentiles "Returns [min p50 p90 p95 p99 max]"
  [longs]
  #?(:cljs
     (let [a (to-array longs)
           max-idx (dec (alength a))]

       (assert (>= max-idx 0))
       (goog.array/sort a)

       [(aget a 0)
        (aget a (Math/round (* 0.50 max-idx)))
        (aget a (Math/round (* 0.90 max-idx)))
        (aget a (Math/round (* 0.95 max-idx)))
        (aget a (Math/round (* 0.99 max-idx)))
        (aget a                     max-idx)])

     :clj
     (let [a (long-array longs)
           max-idx (dec (alength a))]

       (assert (>= max-idx 0))
       (java.util.Arrays/sort a)

       [(aget a 0)
        (aget a (Math/round (* 0.50 max-idx)))
        (aget a (Math/round (* 0.90 max-idx)))
        (aget a (Math/round (* 0.95 max-idx)))
        (aget a (Math/round (* 0.99 max-idx)))
        (aget a                     max-idx)])))

(comment
  (long-percentiles [])
  (defn rand-vs [n & [max]] (take n (repeatedly (partial rand-int (or max Integer/MAX_VALUE)))))
  (def v1 (rand-vs 1e5))
  (enc/qb 100 (long-percentiles v1)) ; 1505.91
  )

(defn stats
  "Given a collection of longs, returns map with keys:
  #{:n :min :max :sum :mean :mad-sum :mad :p50 :p90 :p95 :p99}, or nil if
  collection is empty."
  ;; ([longs {:keys [incl-percentiles?]}])
  [longs]
  (when longs
    (let [vs longs
          nv (count vs)]
      (when (pos? nv)
        (let [sum     (reduce (fn [^long acc in] (+ acc (long in))) 0 vs)
              ;; vmin (reduce (fn [^long acc ^long in] (if (< in acc) in acc)) enc/max-long vs)
              ;; vmax (reduce (fn [^long acc ^long in] (if (> in acc) in acc)) enc/min-long vs)
              mean    (/ (double sum) (double nv))
              mad-sum (reduce (fn [^double acc in] (+ acc (Math/abs (- (double in) mean)))) 0.0 vs)
              mad     (/ (double mad-sum) (double nv))

              [vmin p50 p90 p95 p99 vmax] (long-percentiles vs)]

          {:n nv :min vmin :max vmax :sum sum :mean mean
           :mad-sum mad-sum :mad mad
           :p50 p50 :p90 p90 :p95 p95 :p99 p99})))))

(comment (enc/qb 100 (stats v1)) 1410.6)

(defn merge-stats
  "`(merge-stats (stats c0) (stats c1))` is a basic approximation of `(stats (into c0 c1)))`."
  [m0 m1]
  (if m0
    (if m1
      (let [_ (assert (get m0 :n))
            _ (assert (get m1 :n))

            {^long   n0       :n
             ^long   min0     :min
             ^long   max0     :max
             ^long   sum0     :sum
             ^double mad-sum0 :mad-sum
             ^long   p50-0    :p50
             ^long   p90-0    :p90
             ^long   p95-0    :p95
             ^long   p99-0    :p99} m0

            {^long   n1       :n
             ^long   min1     :min
             ^long   max1     :max
             ^long   sum1     :sum
             ^double mad-sum1 :mad-sum
             ^long   p50-1    :p50
             ^long   p90-1    :p90
             ^long   p95-1    :p95
             ^long   p99-1    :p99} m1

            _ (assert (pos? n0))
            _ (assert (pos? n1))

            n2       (+ n1 n0)
            n0-ratio (/ (double n0) (double n2))
            n1-ratio (/ (double n1) (double n2))

            sum2  (+ sum0 sum1)
            mean2 (/ (double sum2) (double n2))
            min2  (if (< min0 min1) min0 min1)
            max2  (if (> max0 max1) max0 max1)

            ;; Batched "online" MAD calculation here is better= the standard
            ;; Knuth/Welford method, Ref. http://goo.gl/QLSfOc,
            ;;                            http://goo.gl/mx5eSK.
            ;;
            ;; Note that there's empirically no advantage in using `mean2` here
            ;; asap, i.e. to reducing (- v1_i mean2).
            mad-sum2 (+ mad-sum0 ^double mad-sum1)

            ;;; These are pretty rough approximations. More sophisticated
            ;;; approaches not worth the extra cost/effort in our case.
            p50-2 (Math/round (+ (* n0-ratio (double p50-0)) (* n1-ratio (double p50-1))))
            p90-2 (Math/round (+ (* n0-ratio (double p90-0)) (* n1-ratio (double p90-1))))
            p95-2 (Math/round (+ (* n0-ratio (double p95-0)) (* n1-ratio (double p95-1))))
            p99-2 (Math/round (+ (* n0-ratio (double p99-0)) (* n1-ratio (double p99-1))))

            mad2 (/ (double mad-sum2) (double n2))]

        {:n n2 :min min2 :max max2 :sum sum2 :mean mean2
         :mad-sum mad-sum2 :mad mad2
         :p50 p50-2 :p90 p90-2 :p95 p95-2 :p99 p99-2})
      m0)
    m1))

(comment
  (def v2 [1 2 2 3 2 1])
  (def v3 [1 3 5 2 1 6])
  (def v4 (into v2 v3))

  (stats v2) {:min 1, :mean 1.8333333333333333, :mad-sum 3.333333333333333,  :p99 3, :n 6,  :p90 3, :max 3, :mad 0.5555555555555555, :p50 2, :sum 11, :p95 3}
  (stats v3) {:min 1, :mean 3.0,                :mad-sum 10.0,               :p99 6, :n 6,  :p90 6, :max 6, :mad 1.6666666666666667, :p50 3, :sum 18, :p95 6}
  (stats v4) {:min 1, :mean 2.4166666666666665, :mad-sum 14.666666666666666, :p99 6, :n 12, :p90 5, :max 6, :mad 1.222222222222222,  :p50 2, :sum 29, :p95 5}

  (merge-stats (stats v2) (stats v3))
  {:min 1, :mean 2.4166666666666665, :mad-sum 13.333333333333332, :p99 5, :n 12, :p90 5, :max 6, :mad 1.111111111111111, :p50 3, :sum 29, :p95 5}

  (stats (stats v2) v3)
  {:min 1, :mean 2.4166666666666665, :mad-sum 13.333333333333332, :p99 5, :n 12, :p90 5, :max 6, :mad 1.111111111111111, :p50 3, :sum 29, :p95 5}

  (merge-stats (stats v2) (stats v2))
  {:min 1, :mean 1.8333333333333333, :mad-sum 6.666666666666666, :p99 3, :n 12, :p90 3, :max 3, :mad 0.5555555555555555, :p50 2, :sum 22, :p95 3}

  (let [v1 (rand-vs 1e5 80)
        v2 (rand-vs 1e5 20)
        v3 (into v1 v2)]
    (mapv :mad
      [(stats v1)
       (stats v2)
       (stats v3)
       (merge-stats (stats v1) (stats v2))
       (stats (stats v1) v2)]))

  [19.943705799999858 5.015891904000014 18.906570458826117 12.479798851999936 12.479798851999936]
  [20.033054674800002 5.013648978000108 18.914174079741983 12.523351826400054 12.523351826400054])

;;;; Formatting

(defn- perc [n d] (str (Math/round (* (/ (double n) (double d)) 100.0)) "%"))
(comment [(perc 1 1) (perc 1 100) (perc 12 44)])

(let [round2 #?(:cljs enc/round2 :clj (fn [n] (format "%.2f" n)))]
  (defn- fmt [nanosecs]
    (let [ns (double nanosecs)]
      (cond
        (>= ns 6e10) (str (round2 (/ ns 6e10)) "m ")
        (>= ns 1e9)  (str (round2 (/ ns 1e9))  "s ")
        (>= ns 1e6)  (str (round2 (/ ns 1e6))  "ms")
        (>= ns 1e3)  (str (round2 (/ ns 1e3))  "μs")
        :else        (str (round2    ns)       "ns")))))

(comment
  (format "%.2f" 40484.005)
  (fmt 2387387870))

(defn format-stats
  "Returns a formatted table string for given `{<id> <stats>}` map.
  Assumes nanosecond clock, stats based on profiling id'd nanosecond times."
  ([clock-total id-stats        ] (format-stats clock-total id-stats (fn [id m] (get m :sum))))
  ([clock-total id-stats sort-fn]
   (when id-stats
     (let [clock-total (long clock-total)
           ^long accounted-total
           (reduce-kv
             (fn [^long acc _id s]
               (+ acc (long (get s :sum))))
             0 id-stats)

           sorted-ids
           (sort-by
             (fn [id] (sort-fn id (get id-stats id)))
             enc/rcompare
             (keys id-stats))

           ^long max-id-width
           (reduce-kv
             (fn [^long acc k v]
               (let [c (count (str k))]
                 (if (> c acc) c acc)))
             #=(count "Accounted Time")
             id-stats)]

       #?(:cljs ; Simplified output w/o table
          (let [sb
                (reduce
                  (fn [acc id]
                    (let [s     (get id-stats id)
                          sum   (get s :sum)
                          mean  (get s :mean)]
                      (enc/sb-append acc
                        (str
                          {:id    id
                           :n-calls    (get s :n)
                           :min   (fmt (get s :min))
                           :p50   (fmt (get s :p50))
                           :p90   (fmt (get s :p90))
                           :p95   (fmt (get s :p95))
                           :p99   (fmt (get s :p99))
                           :max   (fmt (get s :max))
                           :mean  (fmt mean)
                           :mad   (str "±" (perc (get s :mad) mean))
                           :total (fmt  sum)
                           :clock (perc sum clock-total)}
                          "\n"))))
                  (enc/str-builder)
                  sorted-ids)]

            (enc/sb-append sb "\n")
            (enc/sb-append sb (str "Accounted: (" (perc accounted-total clock-total) ") " (fmt accounted-total) "\n"))
            (enc/sb-append sb (str "Clock: (100%) " (fmt clock-total) "\n"))
            (str           sb))

          :clj
          (let [n-pattern (str "%" max-id-width "s %,11d %10s %10s %10s %10s %10s %10s %10s %5s %11s %7s" "\n")
                s-pattern (str "%" max-id-width  "s %11s %10s %10s %10s %10s %10s %10s %10s %5s %11s %7s" "\n")
                sb
                (reduce
                  (fn [acc id]
                    (let [s    (get id-stats id)
                          sum  (get s :sum)
                          mean (get s :mean)]
                      (enc/sb-append acc
                        (format n-pattern id
                               (get s :n)
                          (fmt (get s :min))
                          (fmt (get s :p50))
                          (fmt (get s :p90))
                          (fmt (get s :p95))
                          (fmt (get s :p99))
                          (fmt (get s :max))
                          (fmt mean)
                          (str "±" (perc (get s :mad) mean))
                          (fmt  sum)
                          (perc sum clock-total)))))

                  ;; (enc/str-builder (str (format s-pattern "pId" "nCalls" "Min" "p50" "p90" "p95" "p99" "Max" "Mean" "MAD" "Total" "Clock" ) "\n"))
                  (enc/str-builder (str (format s-pattern "pId" "nCalls" "Min" "50% <=" "90% <=" "95% <=" "99% <=" "Max" "Mean" "MAD" "Total" "Clock" ) "\n"))
                  sorted-ids)]

            (enc/sb-append sb "\n")
            (enc/sb-append sb (format s-pattern "Accounted" "" "" "" "" "" "" "" "" "" (fmt accounted-total) (perc accounted-total clock-total)))
            (enc/sb-append sb (format s-pattern "Clock"     "" "" "" "" "" "" "" "" "" (fmt clock-total)     "100%"))
            (str sb)))))))

(comment
  (println
    (format-stats (* 1e6 30)
      {:foo (stats (rand-vs 1e4 20))
       :bar (stats (rand-vs 1e2 50))
       :baz (stats (rand-vs 1e5 30))}) "\n"))