(ns railfreight.facts-test
  "`railfreight.facts` used to hold a hardcoded jurisdiction-regulatory
  catalog (real regulator names, legal citations, government URLs) --
  removed per `docs/adr/0002-remove-fabricated-jurisdiction-catalog.md`
  since this actor is an internal operations coordinator, not a
  jurisdiction-facts actor, and must never assert real-world
  regulatory content it cannot verify. What remains is the closed,
  non-regulatory `inspection-results` vocabulary this actor's own
  `:log-inspection-record` op cites."
  (:require [clojure.test :refer [deftest is]]
            [railfreight.facts :as facts]))

(deftest inspection-results-closed-vocabulary
  (is (contains? facts/inspection-results "pass"))
  (is (contains? facts/inspection-results "conditional-pass"))
  (is (contains? facts/inspection-results "fail"))
  (is (contains? facts/inspection-results "pending-review")))

(deftest inspection-result-recognized-predicate
  (is (true? (facts/inspection-result-recognized? "pass")))
  (is (true? (facts/inspection-result-recognized? "fail")))
  (is (false? (facts/inspection-result-recognized? "excellent")))
  (is (false? (facts/inspection-result-recognized? nil)))
  (is (false? (facts/inspection-result-recognized? ""))))
