(ns railfreight.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-service-operation`/`:flag-track-safety-
  concern`/`:coordinate-maintenance` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [railfreight.phase :as phase]))

(deftest schedule-service-operation-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real service-schedule coordination"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-service-operation))
          (str "phase " n " must not auto-commit :schedule-service-operation")))))

(deftest flag-track-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: flagging a concern must ALWAYS reach a human -- never in any phase's :auto set"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-track-safety-concern))
          (str "phase " n " must not auto-commit :flag-track-safety-concern")))))

(deftest coordinate-maintenance-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real maintenance coordination"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :coordinate-maintenance))
          (str "phase " n " must not auto-commit :coordinate-maintenance")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-operational-risk-ops
  (testing ":log-shipment-record carries no direct operational/safety risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-shipment-record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-shipment-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-service-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-track-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-maintenance} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-shipment-record} :commit)))))
