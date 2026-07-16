(ns railfreight.registry-test
  (:require [clojure.test :refer [deftest is]]
            [railfreight.registry :as r]))

;; ----------------------------- register-service-schedule -----------------------------

(deftest schedule-is-a-draft-not-a-real-dispatch-clearance
  (let [result (r/register-service-schedule "consist-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest schedule-assigns-schedule-number
  (let [result (r/register-service-schedule "consist-1" "JPN" 7)]
    (is (= (get result "schedule_number") "JPN-SCH-000007"))
    (is (= (get-in result ["record" "consist_id"]) "consist-1"))
    (is (= (get-in result ["record" "kind"]) "service-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest schedule-validation-rules
  (is (thrown? Exception (r/register-service-schedule "" "JPN" 0)))
  (is (thrown? Exception (r/register-service-schedule "consist-1" "" 0)))
  (is (thrown? Exception (r/register-service-schedule "consist-1" "JPN" -1))))

;; ----------------------------- register-maintenance-coordination -----------------------------

(deftest maintenance-coordination-is-a-draft-not-a-real-release
  (let [result (r/register-maintenance-coordination "consist-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest maintenance-coordination-assigns-maintenance-number
  (let [result (r/register-maintenance-coordination "consist-1" "JPN" 7)]
    (is (= (get result "maintenance_number") "JPN-MNT-000007"))
    (is (= (get-in result ["record" "consist_id"]) "consist-1"))
    (is (= (get-in result ["record" "kind"]) "maintenance-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest maintenance-coordination-validation-rules
  (is (thrown? Exception (r/register-maintenance-coordination "" "JPN" 0)))
  (is (thrown? Exception (r/register-maintenance-coordination "consist-1" "" 0)))
  (is (thrown? Exception (r/register-maintenance-coordination "consist-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-service-schedule "consist-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-service-schedule "consist-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-SCH-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-SCH-000001" (get-in hist2 [1 "record_id"])))))
