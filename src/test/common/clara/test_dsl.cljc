#?(:clj
   (ns clara.test-dsl
     (:require [clara.tools.testing-utils :refer [def-rules-test] :as tu]
               [clara.rules :refer [fire-rules
                                    insert
                                    insert-all
                                    insert-all!
                                    insert!
                                    retract
                                    query]]
               [clara.rules.accumulators :as acc]
               [clara.rules.testfacts :refer [->Temperature ->Cold ->WindSpeed
                                              ->ColdAndWindy map->FlexibleFields]]
               [clojure.test :refer [is deftest run-tests testing use-fixtures]]
               [clara.rules.accumulators]
               [schema.test :as st])
     (:import [clara.rules.testfacts
               Temperature
               Cold
               WindSpeed
               ColdAndWindy
               FlexibleFields]))

   :cljs
   (ns clara.test-dsl
     (:require [clara.rules :refer [fire-rules
                                    insert
                                    insert!
                                    insert-all
                                    insert-all!
                                    retract
                                    query]]
               [clara.rules.testfacts :refer [map->FlexibleFields FlexibleFields
                                              ->Temperature Temperature
                                              ->Cold Cold
                                              ->WindSpeed WindSpeed
                                              ->ColdAndWindy ColdAndWindy]]
               [clara.rules.accumulators :as acc]
               [clara.tools.testing-utils :as tu]
               [cljs.test]
               [schema.test :as st])
     (:require-macros [clara.tools.testing-utils :refer [def-rules-test]]
                      [cljs.test :refer [is deftest run-tests testing use-fixtures]])))

;; Tests focused on DSL functionality such as binding visibility in edge cases, fact field access, etc.
;; The distinction between tests here and tests in files focused on the aspects of Clara that the DSL represents
;; may not be clear and there are borderline cases.

(use-fixtures :once st/validate-schemas #?(:clj tu/opts-fixture))

(def side-effect-holder (atom nil))

(def-rules-test test-simple-binding-variable-ordering

  {:rules [rule1 [[[Temperature (< temperature 20) (= ?t temperature)]]
                  (reset! side-effect-holder ?t)]

           rule2 [[[Temperature (= ?t temperature) (< temperature 20)]]
                  (reset! side-effect-holder ?t)]

           rule3 [[[Temperature (< temperature 20) (= temperature ?t)]]
                  (reset! side-effect-holder ?t)]

           rule4 [[[Temperature (= temperature ?t) (< temperature 20)]]
                  (reset! side-effect-holder ?t)]]

   :sessions [rule1-session [rule1] {}
              rule2-session [rule2] {}
              rule3-session [rule3] {}
              rule4-session [rule4] {}]}

  (testing "Unrelated constraint on the field and then a binding with the variable first"
    (reset! side-effect-holder nil)
    
    (-> rule1-session
        (insert (->Temperature 10 "MCI"))
        fire-rules)

    (is (= 10 @side-effect-holder)))

  (testing "A binding with the variable first and then an unrelated constraint on the field"
    (reset! side-effect-holder nil)
    
    (-> rule2-session
        (insert (->Temperature 10 "MCI"))
        fire-rules)

    (is (= 10 @side-effect-holder)))

  (testing "Unrelated constraint on the field and then a binding with the variable second"
    (reset! side-effect-holder nil)
    
    (-> rule3-session
        (insert (->Temperature 10 "MCI"))
        fire-rules)

    (is (= 10 @side-effect-holder)))

  (testing "A binding with the variable second and then an unrelated constraint on the field"
    (reset! side-effect-holder nil)
    
    (-> rule4-session
        (insert (->Temperature 10 "MCI"))
        fire-rules)

    (is (= 10 @side-effect-holder))))

(def-rules-test test-multiple-binding

  {:rules [multi-binding-rule [[[Temperature (< temperature 20) (= ?t temperature ?u ?v)]]
                               (do (swap! side-effect-holder assoc :?t ?t)
                                   (swap! side-effect-holder assoc :?u ?u)
                                   (swap! side-effect-holder assoc :?v ?v))]]

   :sessions [empty-session [multi-binding-rule] {}]}

  (reset! side-effect-holder {})

  (-> empty-session
      (insert (->Temperature 10 "MCI"))
      fire-rules)

  (is (= @side-effect-holder {:?t 10
                              :?u 10
                              :?v 10})))

#?(:clj
   (def-rules-test test-bean-test
     {:queries [tz-offset-query [[:?offset]
                                 [[java.util.TimeZone (= ?offset rawOffset)
                                   (= ?id ID)]]]]

      :sessions [empty-session [tz-offset-query] {}]}

     (let [session (-> empty-session
                       (insert (java.util.TimeZone/getTimeZone "America/Chicago")
                               (java.util.TimeZone/getTimeZone "UTC"))
                       fire-rules)]

       (is (= #{{:?id "America/Chicago" :?offset -21600000}}
              (set (query session tz-offset-query :?offset -21600000))))

       (is (= #{{:?id "UTC" :?offset 0}}
              (set (query session tz-offset-query :?offset 0)))))))

(def-rules-test test-destructured-args
  {:queries [cold-query [[]
                         [[Temperature
                           [{temp-arg :temperature}] (< temp-arg 20) (= ?t temp-arg)]]]]

   :sessions [empty-session [cold-query] {}]}

  (let [session (-> empty-session
                    (insert (->Temperature 15 "MCI"))
                    (insert (->Temperature 10 "MCI"))
                    (insert (->Temperature 80 "MCI"))
                    fire-rules)]

    (is (= (frequencies [{:?t 15} {:?t 10}])
           (frequencies (query session cold-query))))))

(def-rules-test test-general-map

  {:queries [cold-query [[]
                         [[:temperature [{temp :value}] (< temp 20) (= ?t temp)]]]]

   :sessions [empty-session [cold-query] {:fact-type-fn :type}]}

  (let [session (-> empty-session
                    (insert {:type :temperature :value 15 :location "MCI"}
                            {:type :temperature :value 10 :location "MCI"}
                            {:type :windspeed :value 5 :location "MCI"}
                            {:type :temperature :value 80 :location "MCI"})
                    fire-rules)

        retracted-session (-> empty-session
                              (insert {:type :temperature :value 15 :location "MCI"}
                                      {:type :temperature :value 10 :location "MCI"}
                                      {:type :windspeed :value 5 :location "MCI"}
                                      {:type :temperature :value 80 :location "MCI"})
                              (retract {:type :temperature :value 15 :location "MCI"})
                              fire-rules)]

    (is (= (frequencies [{:?t 15} {:?t 10}])
           (frequencies (query session cold-query))))

    (is (= {{:?t 10} 1}
           (frequencies (query retracted-session cold-query))))))

(defrecord RecordWithDash [test-field])

(def-rules-test test-record-with-dash-in-field

  {:queries [q [[] [[RecordWithDash (= ?f test-field)]]]]

   :sessions [empty-session [q] {}]}

  (let [session (-> empty-session
                    (insert (->RecordWithDash 15))
                    fire-rules)]

    (is (= {{:?f 15} 1}
           (frequencies (query session q))))))

;; FIXME: This doesn't pass in ClojureScript and it should.
#?(:clj
   (def-rules-test test-variable-visibility

     {:rules [temps-for-locations [[[:location (= ?loc (:loc this))]

                                    [Temperature
                                     (= ?temp temperature)
                                     ;; TODO: This comment was copied from clara.test-rules, is it still accurate?
                                     ;;
                                     ;; This can only have one binding right
                                     ;; now due to work that needs to be done
                                     ;; still in clara.rules.compiler/extract-from-constraint
                                     ;; around support multiple bindings in a condition.
                                     (contains? #{?loc} location)]]
                                   (insert! (->Cold ?temp))]]

      :queries [find-cold [[] [[?c <- Cold]]]]

      :sessions [empty-session [temps-for-locations find-cold] {}]}

     (let [session  (-> empty-session
                        (insert ^{:type :location} {:loc "MCI"})
                        (insert (->Temperature 10 "MCI"))
                        fire-rules)]

       (is (= {{:?c (->Cold 10)} 1}
              (frequencies (query session find-cold)))))))

(def-rules-test test-nested-binding

  {:queries [same-wind-and-temp [[]
                                 [[Temperature (= ?t temperature)]
                                  [WindSpeed (or (= ?t windspeed)
                                                 (= "MCI" location))]]]]

   :sessions [empty-session [same-wind-and-temp] {}]}

  ;; Matches because temperature and windspeed match.
  (is (= [{:?t 10}]
         (-> empty-session
             (insert (->Temperature 10  "MCI")
                     (->WindSpeed 10  "SFO"))
             (fire-rules)
             (query same-wind-and-temp))))

  ;; Matches because cities match.
  (is (= [{:?t 10}]
         (-> empty-session
             (insert (->Temperature 10  "MCI")
                     (->WindSpeed 20  "MCI"))
             (fire-rules)
             (query same-wind-and-temp))))

  ;; No match because neither city nor temperature/windspeed match.
  (is (empty? (-> empty-session
                  (insert (->Temperature 10  "MCI")
                          (->WindSpeed 20  "SFO"))
                  (fire-rules)
                  (query same-wind-and-temp)))))

;; FIXME: Make this pass in ClojureScript
#?(:clj
   ;; Test for: https://github.com/cerner/clara-rules/issues/97
   (def-rules-test test-nested-binding-with-disjunction

     {:rules [any-cold [[[Temperature (= ?t temperature)]
                         [:or
                          [Cold (< temperature ?t)]
                          [Cold (< temperature 5)]]]

                        (insert! ^{:type :found-cold} {:found true})]]

      :queries [found-cold [[] [[?f <- :found-cold]]]]

      :sessions [empty-session [any-cold found-cold] {}]}

     (let [session (-> empty-session
                       (insert (->Temperature 10 "MCI")
                               (->Cold 5))
                       fire-rules)

           results (query session found-cold)]

       (is (= 1 (count results)))

       (is (= ^{:type found-cold} {:?f {:found true}}
              (first results))))))

(def locals-shadowing-tester
         "Used to demonstrate local shadowing works in `test-rhs-locals-shadowing-vars` below."
         :bad)

;; FIXME: This doesn't work in ClojureScript and it should; it gives a compilation error.
#?(:clj
   (def-rules-test test-rhs-locals-shadowing-vars

     {:rules [r1 [[[:test]]
                  (let [{:keys [locals-shadowing-tester]} {:locals-shadowing-tester :good}]
                    (insert! ^{:type :result}
                             {:r :r1
                              :v locals-shadowing-tester}))]

              r2 [[[:test]]
                  (let [locals-shadowing-tester :good]
                    (insert! ^{:type :result}
                             {:r :r2
                              :v locals-shadowing-tester}))]

              r3 [[[:test]]
                  (let [[locals-shadowing-tester] [:good]]
                    (insert! ^{:type :result}
                             {:r :r3
                              :v locals-shadowing-tester}))]

              r4 [[[:test]]
                  (insert-all! (for [_ (range 1)
                                     :let [locals-shadowing-tester :good]]
                                 ^{:type :result}
                                 {:r :r4
                                  :v locals-shadowing-tester}))]]

      :queries [q [[] [[?r <- :result]]]]

      :sessions [empty-session [r1 r2 r3 r4 q] {}]}

     (is (= (frequencies [{:r :r1
                           :v :good}
                          {:r :r2
                           :v :good}
                          {:r :r3
                           :v :good}
                          {:r :r4
                           :v :good}])
            (->> (-> empty-session
                     (insert ^{:type :test} {})
                     fire-rules
                     (query q))
                 (map :?r)
                 frequencies)))))

#?(:clj
   (def-rules-test test-qualified-java-introp

     {:queries [find-string-substring [[]
                                       [[?s <- String (and (<= 2 (count this))
                                                           (.. this (substring 2) toString))]]]]

      :sessions [empty-session [find-string-substring] {}]}

     (let [session (-> empty-session
                       (insert "abc")
                       fire-rules)]

       (is (= [{:?s "abc"}]
              (query session find-string-substring))))))

(def-rules-test record-fields-with-munged-names
  {:queries [q [[]
                [[?ff <- FlexibleFields (= ?it-works? it-works?)
                  (= ?a->b a->b)
                  (= ?x+y x+y)
                  (= ?bang! bang!)]]]]

   :sessions [empty-session [q] {}]}

  (let [ff (map->FlexibleFields {:it-works? true
                                 :a->b {:a :b}
                                 :x+y [:x :y]
                                 :bang! :bang!})

        res (-> empty-session
                (insert ff)
                fire-rules
                (query q)
                set)]

    (is (= #{{:?ff ff
              :?it-works? true
              :?a->b {:a :b}
              :?x+y [:x :y]
              :?bang! :bang!}}
           res))))

;; Test for https://github.com/cerner/clara-rules/issues/267
;; This test has a counterpart of the same name in test-rules for
;; and error-checking case; once we land on a strategy for error-checking
;; test cases in ClojureScript we can move that test case here and eliminate the
;; test there.
(def-rules-test test-local-scope-visible-in-join-filter

  {:queries [check-local-binding [[] [[WindSpeed (= ?w windspeed)]
                                      [Temperature (= ?t temperature)
                                       (tu/join-filter-equals ?w ?t 10)]]]

             check-local-binding-accum [[] [[WindSpeed (= ?w windspeed)]
                                            [?results <- (acc/all) :from [Temperature (= ?t temperature)
                                                                          (tu/join-filter-equals ?w ?t 10)]]]]

             check-reuse-previous-binding [[] [[WindSpeed (= ?t windspeed) (= ?w windspeed)]
                                               [Temperature (= ?t temperature)
                                                (tu/join-filter-equals ?w ?t 10)]]]

             check-accum-result-previous-binding [[]
                                                  [[?t <- (acc/min :temperature) :from [Temperature]]
                                                   [ColdAndWindy (= ?t temperature) (tu/join-filter-equals ?t windspeed)]]]]

   :sessions [check-local-binding-session [check-local-binding] {}
              check-local-binding-accum-session [check-local-binding-accum] {}
              check-reuse-previous-binding-session [check-reuse-previous-binding] {}
              check-accum-result-previous-binding-session [check-accum-result-previous-binding] {}]}

  (is (= [{:?w 10 :?t 10}]
         (-> check-local-binding-session
             (insert (->WindSpeed 10 "MCI") (->Temperature 10 "MCI"))
             (fire-rules)
             (query check-local-binding))))

  (is (= [{:?w 10 :?t 10 :?results [(->Temperature 10 "MCI")]}]
         (-> check-local-binding-accum-session
             (insert (->WindSpeed 10 "MCI") (->Temperature 10 "MCI"))
             (fire-rules)
             (query check-local-binding-accum))))

  (is (= [{:?w 10 :?t 10}]
         (-> check-reuse-previous-binding-session
             (insert (->WindSpeed 10 "MCI") (->Temperature 10 "MCI"))
             (fire-rules)
             (query check-reuse-previous-binding))))

  (is (empty? (-> check-accum-result-previous-binding-session
                  (insert (->Temperature -10 "MCI"))
                  (insert (->ColdAndWindy -20 -20))
                  fire-rules
                  (query check-accum-result-previous-binding)))
      "Validate that the ?t binding from the previous accumulator result is used, rather
         than the binding in the ColdAndWindy condition that would create a ?t binding if one were
         not already present"))
