(ns clara.test
  (:require-macros [cljs.test :as test])
  (:require [clara.test-rules]
            [cljs.test]
            [clara.test-salience]
            [clara.test-complex-negation]
            [clara.test-common]
            [clara.test-testing-utils]
            [clara.test-accumulators]
            [clara.test-exists]
            [clara.tools.test-tracing]
            [clara.test-truth-maintenance]
            [clara.test-dsl]
            [clara.test-accumulation]
            [clara.test-memory]))

(enable-console-print!)

(def ^:dynamic *successful?* nil)

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (do
      (println "Success!")
      (reset! *successful?* true))
    (do
      (println "FAIL")
      (reset! *successful?* false))))

(defn ^:export run []
  (binding [*successful?* (atom nil)]
    (test/run-tests 'clara.test-rules
                    'clara.test-common
                    'clara.test-salience
                    'clara.test-testing-utils
                    'clara.test-complex-negation
                    'clara.test-accumulators
                    'clara.test-exists
                    'clara.tools.test-tracing
                    'clara.test-truth-maintenance
                    'clara.test-dsl
                    'clara.test-accumulation
                    'clara.test-memory)
    @*successful?*))
