(ns lambdacd.testsupport.test-util
  (:require [clojure.test :refer :all]
            [lambdacd.internal.execution :as execution]
            [clojure.core.async :as async]
            [clojure.walk :as w]
            [lambdacd.internal.pipeline-state :as ps])
  (:import (java.util.concurrent TimeoutException)))

(defmacro my-time
  "measure the time a function took to execute"
  [expr]
  `(let [start# (. System (nanoTime))]
     ~expr
     (/ (double (- (. System (nanoTime)) start#)) 1000000.0)))


(defn absolute-difference ^double [^double x ^double y]
  (Math/abs (double (- x y))))

(defn close? [tolerance x y]
  (< (absolute-difference x y) tolerance))

(defmacro with-private-fns [[ns fns] & tests]
  "Refers private fns from ns and runs tests in context."
  `(let ~(reduce #(conj %1 %2 `(ns-resolve '~ns '~%2)) [] fns)
     ~@tests))

(defn history-for-atom [value-atom]
  (let [history-atom (atom [])]
    (add-watch value-atom :foo (fn [_ _ old new]
                                (if (not= old new)
                                  (swap! history-atom conj new))))
    history-atom))

(defmacro atom-history-for [value-atom body]
  `(let [history-atom# (history-for-atom ~value-atom)]
     ~body
     @history-atom#))

(defn get-or-timeout [c & {:keys [timeout]
                           :or   {timeout 10000}}]
  (async/alt!!
    c ([result] result)
    (async/timeout timeout) {:status :timeout}))

(defn slurp-chan [c]
  (Thread/sleep 200) ; FIXME: hack
  (async/close! c)
  (async/<!! (async/into [] c)))

(defn slurp-chan-with-size [size ch]
  (get-or-timeout
    (async/go-loop [collector []]
      (if-let [item (async/<! ch)]
        (let [new-collector (conj collector item)]
          (if (= size (count new-collector))
            new-collector
            (recur new-collector)))))))

(defn result-channel->map [ch]
  (async/<!!
    (async/go-loop [last {}]
      (if-let [[key value] (async/<! ch)]
        (let [new (assoc last key value)]
          (if (#'execution/is-finished key value)
            new
            (recur new)))
        last))))


(defmacro eventually [pred]
  `(loop [count# 0]
     (let [result# ~pred]
        (if (or (not result#) (< count# 10))
          (do
            (Thread/sleep 100)
            (recur (inc count#)))
          result#))))

(defn- tuple? [x]
  (and (vector? x) (= 2 (count x))))

(defn- remove-if-key-value-pair-with-key [k]
  (fn [x]
    (if (and (tuple? x) (= k (first x)))
      nil
      x)))

(defn- remove-with-key
  [k form]
  (w/postwalk (remove-if-key-value-pair-with-key k) form))


(defn without-key [m k]
  (remove-with-key k m))

(defn without-ts
  "strip timestamp information from map to make tests less cluttered with data that's not interesting at the moment"
  [m]
  (-> m
      (without-key :most-recent-update-at)
      (without-key :first-updated-at)))


(defmacro wait-for [predicate]
  `(loop [time-slept# 0]
     (if (> time-slept# 10000)
       (throw (TimeoutException. "waited for too long")))
     (if (not ~predicate)
       (do
         (Thread/sleep 50)
         (recur (+ time-slept# 50))))))

(defmacro start-waiting-for [body]
  `(async/go
     ~body))

(defn start-waiting-for-result [key result-channel]
  (async/go-loop []
    (let [result (async/<! result-channel)
          actual-key (first result)]
      (if (= key actual-key)
        (second result)
        (recur)))))


(defn step-status [{build-number :build-number step-id :step-id pipeline-state-component :pipeline-state-component}]
  (-> (ps/get-all pipeline-state-component)
      (get build-number)
      (get step-id)
      :status))

(defn step-running? [ctx]
  (= :running (step-status ctx)))

(defn step-success? [ctx build-number step-id]
  ;(println "foo" (-> (ps/get-all (:pipeline-state-component ctx))
  ;                   (get build-number)))
  (= :success (step-status (assoc ctx :build-number build-number
                                      :step-id step-id))))
(defn step-finished? [ctx build-number step-id]
  ;(println "foo" (-> (ps/get-all (:pipeline-state-component ctx))
  ;                   (get build-number)))
  (let [status (step-status (assoc ctx :build-number build-number
                                      :step-id step-id))]
    (or (= :success status) (= :failure status))))

(defn step-failure? [ctx build-number step-id]
  (= :failure (step-status (assoc ctx :build-number build-number
                                      :step-id step-id))))
