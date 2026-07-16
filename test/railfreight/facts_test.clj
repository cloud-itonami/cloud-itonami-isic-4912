(ns railfreight.facts-test
  (:require [clojure.test :refer [deftest is]]
            [railfreight.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest all-four-seeded-jurisdictions-have-a-hazmat-spec-basis
  (doseq [iso3 ["JPN" "USA" "GBR" "DEU"]]
    (is (some? (facts/hazmat-spec-basis iso3)) (str iso3 " hazmat-spec-basis"))
    (is (string? (:hazmat-provenance (facts/hazmat-spec-basis iso3))) (str iso3 " hazmat-provenance"))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest unknown-jurisdiction-has-no-hazmat-spec-basis
  (is (nil? (facts/hazmat-spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
