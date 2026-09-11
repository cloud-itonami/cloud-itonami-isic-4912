(ns railfreight.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-service-operation`/`:flag-track-safety-
  concern`/`:coordinate-maintenance`/`:register-hazmat-transport-
  scope`/`:release-rolling-stock-from-maintenance` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [railfreight.phase :as phase]))

(def ^:private never-auto-ops
  #{:schedule-service-operation :flag-track-safety-concern :coordinate-maintenance
    :register-hazmat-transport-scope :release-rolling-stock-from-maintenance})

(deftest never-auto-ops-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits any of these five ops -- each ALWAYS needs a human"
    (doseq [op never-auto-ops
            [n {:keys [auto]}] phase/phases]
      (is (not (contains? auto op))
          (str "phase " n " must not auto-commit " op)))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-operational-risk-ops
  (testing "only pure data-logging ops (no operational/safety authorization weight of their own) are auto-eligible in phase 3"
    (is (= #{:log-shipment-record :log-inspection-record :log-reconciliation-record}
           (:auto (get phase/phases 3))))))

(deftest auto-set-is-always-a-subset-of-writes
  (testing "structural invariant: a phase can never auto-commit an op it doesn't even allow as a write"
    (doseq [[n {:keys [writes auto]}] phase/phases]
      (is (set/subset? auto writes) (str "phase " n)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-shipment-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-service-operation} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-track-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :coordinate-maintenance} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-shipment-record} :commit)))))
