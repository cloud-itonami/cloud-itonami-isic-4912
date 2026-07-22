(ns railfreight.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  invariant under test:

    RailFreight-LLM never proposes a schedule/maintenance-coordination
    the Rail Freight Governor would reject, `:schedule-service-
    operation`/`:flag-track-safety-concern`/`:coordinate-maintenance`
    NEVER auto-commit at any phase, `:log-shipment-record` (no
    operational/safety risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact. Also
    covers the STRUCTURAL checks (`effect-not-propose`/`op-not-
    allowlisted`/`action-not-allowlisted`/`scope-exclusion-violation`)
    directly against hand-crafted adversarial proposals, since the
    well-behaved mock advisor never reaches them on its own (see
    `railfreight.sim` ns docstring)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [railfreight.governor :as governor]
            [railfreight.railfreightllm :as railfreightllm]
            [railfreight.store :as store]
            [railfreight.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :rail-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-shipment-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-shipment-record :subject "consist-1"
                   :patch {:id "consist-1" :carrier "Updated Rail Co"}
                   :spec-basis "operator-submitted-sms-registration-JPN-0001"} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Updated Rail Co" (:carrier (store/consist db "consist-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-service-operation-always-needs-approval
  (testing "schedule is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-service-operation :subject "consist-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/consist db "consist-1"))))))))

(deftest log-shipment-record-without-operator-supplied-spec-basis-is-held
  (testing "a log-shipment-record proposal with no operator-supplied spec-basis -> HOLD, never reaches a human -- this actor never invents one"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-shipment-record :subject "consist-6"
                     :patch {:id "consist-6"}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/consist db "consist-6"))
          "no record created -- HOLD never merged the unverified patch"))))

(deftest schedule-without-registered-record-is-held
  (testing "schedule-service-operation before any :log-shipment-record commit -> HOLD (record-not-verified)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :schedule-service-operation :subject "consist-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) first :basis))))))

(deftest flag-track-safety-concern-without-registered-record-is-held
  (testing "flag-track-safety-concern before any :log-shipment-record commit -> HOLD (record-not-verified) -- 'before ANY action', not only the highest-stakes op"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :flag-track-safety-concern :subject "consist-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) first :basis))))))

(deftest hazmat-handling-protocol-unconfirmed-is-held-and-unoverridable
  (testing "a hazmat consist with no confirmed handling protocol -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :schedule-service-operation :subject "consist-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:hazmat-handling-protocol-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-history db))))))

(deftest open-safety-concern-is-held-and-unoverridable-on-schedule
  (testing "an unresolved track-safety concern -> HOLD on schedule-service-operation, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t7" {:op :schedule-service-operation :subject "consist-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:open-safety-concern} (-> (store/ledger db) last :basis)))
      (is (empty? (store/schedule-history db))))))

(deftest open-safety-concern-is-held-and-unoverridable-on-coordinate-maintenance
  (testing "an unresolved track-safety concern -> HOLD on coordinate-maintenance too, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t8" {:op :coordinate-maintenance :subject "consist-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:open-safety-concern} (-> (store/ledger db) last :basis)))
      (is (empty? (store/maintenance-history db))))))

(deftest coordinate-maintenance-already-open-is-held-and-unoverridable
  (testing "a consist with an already-open maintenance coordination -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :coordinate-maintenance :subject "consist-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:already-coordinating} (-> (store/ledger db) last :basis)))
      (is (= 0 (count (store/maintenance-history db)))
          "no NEW draft record -- the pre-existing open one is untouched by this actor"))))

(deftest schedule-service-operation-always-escalates-then-human-decides
  (testing "a clean, fully-registered, hazmat-clear, no-concern consist still ALWAYS interrupts for human approval -- coordination/schedule-service is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t10" {:op :schedule-service-operation :subject "consist-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, service-schedule record drafted"
        (let [r2 (approve! actor "t10")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:scheduled? (store/consist db "consist-1"))))
          (is (= 1 (count (store/schedule-history db))) "one draft service-schedule record"))))))

(deftest coordinate-maintenance-always-escalates-then-human-decides
  (testing "a clean, fully-registered, no-concern, not-already-open consist still ALWAYS interrupts for human approval -- coordination/coordinate-maintenance is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t11" {:op :coordinate-maintenance :subject "consist-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, maintenance-coordination record drafted"
        (let [r2 (approve! actor "t11")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:maintenance-open? (store/consist db "consist-1"))))
          (is (= 1 (count (store/maintenance-history db)))))))))

(deftest flag-track-safety-concern-always-escalates-then-human-decides
  (testing "flag-track-safety-concern ALWAYS interrupts for human approval, even for a clean/registered consist -- it is the highest-caution op in this domain"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t12" {:op :flag-track-safety-concern :subject "consist-1"
                                   :note "unusual brake-shoe wear reported"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, concern recorded on the consist"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:safety-concern-raised? (store/consist db "consist-1"))))
          (is (false? (:safety-concern-resolved? (store/consist db "consist-1")))))))))

(deftest schedule-service-operation-double-schedule-is-held
  (testing "scheduling the same consist twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t13a" {:op :schedule-service-operation :subject "consist-1"} operator)
          _ (approve! actor "t13a")
          res (exec-op actor "t13" {:op :schedule-service-operation :subject "consist-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/schedule-history db))) "still only the one earlier schedule"))))

(deftest coordinate-maintenance-double-coordination-is-held
  (testing "opening a second maintenance-coordination request for the same consist -> HOLD"
    (let [[db actor] (fresh)
          _ (exec-op actor "t14a" {:op :coordinate-maintenance :subject "consist-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :coordinate-maintenance :subject "consist-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-coordinating} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/maintenance-history db))) "still only the one earlier coordination"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-shipment-record :subject "consist-1"
                          :patch {:id "consist-1" :carrier "Local Freight Rail Co"}
                          :spec-basis "operator-submitted-sms-registration-JPN-0001"} operator)
      (exec-op actor "b" {:op :log-shipment-record :subject "consist-6"
                          :patch {:id "consist-6"}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

;; ----------------------- register-hazmat-transport-scope -----------------------

(deftest hazmat-transport-scope-with-no-evidence-is-held
  (testing "'hazmat transport cannot commit without a valid hazmat-transport-scope record' -- no evidence -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "hz1" {:op :register-hazmat-transport-scope :subject "consist-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:hazmat-scope-evidence-missing} (-> (store/ledger db) last :basis)))
      (is (false? (:hazmat-handling-confirmed? (store/consist db "consist-1")))))))

(deftest hazmat-transport-scope-on-unregistered-consist-is-held
  (testing "record-not-verified applies to hazmat-transport-scope confirmation too -- before ANY action"
    (let [[db actor] (fresh)
          res (exec-op actor "hz2" {:op :register-hazmat-transport-scope :subject "consist-2"
                                    :evidence "operator-submitted-evidence"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:record-not-verified} (-> (store/ledger db) last :basis))))))

(deftest hazmat-transport-scope-with-evidence-always-escalates-then-commits
  (testing "with operator-supplied evidence, escalates for human sign-off, then commits and confirms hazmat-handling"
    (let [[db actor] (fresh)
          r1 (exec-op actor "hz3" {:op :register-hazmat-transport-scope :subject "consist-3"
                                   :evidence "operator-submitted-hazmat-handling-protocol-ack"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "hz3")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:hazmat-handling-confirmed? (store/consist db "consist-3"))))
        (is (= "operator-submitted-hazmat-handling-protocol-ack"
               (:hazmat-handling-evidence (store/consist db "consist-3"))))))))

(deftest hazmat-transport-scope-never-auto-at-any-phase
  (testing "structural invariant: confirming a hazmat-transport scope must ALWAYS reach a human"
    (let [[_db actor] (fresh)
          r1 (exec-op actor "hz4" {:op :register-hazmat-transport-scope :subject "consist-1"
                                   :evidence "operator-submitted-evidence"} operator)]
      (is (= :interrupted (:status r1))))))

;; ----------------------- log-inspection-record -----------------------

(deftest inspection-record-with-unrecognized-result-is-held
  (testing "an unrecognized inspection-result code -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "in1" {:op :log-inspection-record :subject "consist-1"
                                    :inspection-result "excellent"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:inspection-record-invalid} (-> (store/ledger db) last :basis)))
      (is (nil? (:last-inspection-result (store/consist db "consist-1")))))))

(deftest inspection-record-on-unregistered-consist-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "in2" {:op :log-inspection-record :subject "consist-2"
                                  :inspection-result "pass"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:record-not-verified} (-> (store/ledger db) last :basis)))))

(deftest inspection-record-with-recognized-result-auto-commits
  (testing "a recognized inspection-result code from a registered consist is pure data logging -- auto-commits, no human approval needed"
    (let [[db actor] (fresh)
          res (exec-op actor "in3" {:op :log-inspection-record :subject "consist-1"
                                    :inspection-result "pass"} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= "pass" (:last-inspection-result (store/consist db "consist-1")))))))

;; ----------------------- release-rolling-stock-from-maintenance -----------------------

(deftest release-with-no-open-maintenance-is-held
  (testing "'track/rolling-stock cannot be marked serviceable' with nothing to release -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "rl1" {:op :release-rolling-stock-from-maintenance :subject "consist-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:maintenance-not-open} (-> (store/ledger db) last :basis))))))

(deftest release-with-no-completed-inspection-is-held
  (testing "'track/rolling-stock cannot be marked serviceable without a completed inspection record present' -- an open maintenance request but no inspection on file -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "rl2" {:op :release-rolling-stock-from-maintenance :subject "consist-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:maintenance-release-uninspected} (-> (store/ledger db) last :basis)))
      (is (true? (:maintenance-open? (store/consist db "consist-5"))) "still open -- HOLD never released it"))))

(deftest release-with-open-safety-concern-is-held
  (testing "an unresolved track-safety concern blocks maintenance release too, not only scheduling/coordination"
    ;; consist-4 has an open concern but no open maintenance/inspection either;
    ;; open-safety-concern fires independently of those two other HARD checks.
    (let [[db actor] (fresh)
          res (exec-op actor "rl3" {:op :release-rolling-stock-from-maintenance :subject "consist-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:open-safety-concern} (-> (store/ledger db) last :basis))))))

(deftest release-with-open-maintenance-and-completed-inspection-always-escalates-then-commits
  (testing "consist-7 is pre-seeded with an open maintenance request AND a completed passing inspection -- clean release still ALWAYS needs a human"
    (let [[db actor] (fresh)
          r1 (exec-op actor "rl4" {:op :release-rolling-stock-from-maintenance :subject "consist-7"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (let [r2 (approve! actor "rl4")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (false? (:maintenance-open? (store/consist db "consist-7"))) "release closes the open maintenance request")))))

;; ----------------------- log-reconciliation-record -----------------------

(deftest reconciliation-record-with-no-evidence-is-held
  (testing "docs/business-model.md Trust Control: 'reconciliation records require verified evidence' -- no evidence -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "rc1" {:op :log-reconciliation-record :subject "consist-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:reconciliation-evidence-missing} (-> (store/ledger db) last :basis)))
      (is (nil? (:last-reconciliation-evidence (store/consist db "consist-1")))))))

(deftest reconciliation-record-on-unregistered-consist-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "rc2" {:op :log-reconciliation-record :subject "consist-2"
                                  :evidence "operator-submitted-invoice-ref"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:record-not-verified} (-> (store/ledger db) last :basis)))))

(deftest reconciliation-record-with-evidence-auto-commits
  (testing "verified evidence from a registered consist is pure data logging -- auto-commits"
    (let [[db actor] (fresh)
          res (exec-op actor "rc3" {:op :log-reconciliation-record :subject "consist-1"
                                    :evidence "operator-submitted-invoice-ref-0001" :amount 15000} operator)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (= "operator-submitted-invoice-ref-0001" (:last-reconciliation-evidence (store/consist db "consist-1"))))
      (is (= 15000 (:last-reconciliation-amount (store/consist db "consist-1")))))))

;; ----------------------- structural checks (hand-crafted proposals) -----------------------

(deftest effect-not-propose-is-a-hard-permanent-block
  (testing "a proposal that does not carry the literal :effect :propose is hard-blocked, unconditionally"
    (let [[db _actor] (fresh)
          bad {:summary "s" :rationale "r" :cites [] :effect :execute
               :action :consist/mark-scheduled :value {:consist-id "consist-1"}
               :stake :coordination/schedule-service :confidence 0.99}
          verdict (governor/check {:op :schedule-service-operation :subject "consist-1"} operator bad db)]
      (is (:hard? verdict))
      (is (some #{:effect-not-propose} (mapv :rule (:violations verdict))))
      (is (not (:ok? verdict))))))

(deftest op-not-in-allowlist-is-a-hard-permanent-block
  (testing "an op outside the four-member closed allowlist is hard-blocked, unconditionally -- e.g. a hypothetical direct-dispatch-authority op can never even be represented"
    (let [[db _actor] (fresh)
          proposal {:summary "s" :rationale "r" :cites [] :effect :propose
                     :action :consist/mark-scheduled :value {} :stake nil :confidence 0.99}
          verdict (governor/check {:op :dispatch/clear-for-departure :subject "consist-1"} operator proposal db)]
      (is (:hard? verdict))
      (is (some #{:op-not-allowlisted} (mapv :rule (:violations verdict)))))))

(deftest action-not-in-allowlist-is-a-hard-permanent-block
  (testing "an :action outside the four-member closed allowlist is hard-blocked, unconditionally -- structurally excludes any track/dispatch-safety-authority-finalizing action"
    (let [[db _actor] (fresh)
          proposal {:summary "s" :rationale "r" :cites [] :effect :propose
                     :action :track/clear-for-departure :value {} :stake nil :confidence 0.99}
          verdict (governor/check {:op :schedule-service-operation :subject "consist-1"} operator proposal db)]
      (is (:hard? verdict))
      (is (some #{:action-not-allowlisted} (mapv :rule (:violations verdict)))))))

(deftest scope-exclusion-phrase-in-rationale-is-a-hard-permanent-block
  (testing "a proposal whose OWN rationale/summary names a forbidden finalization action is hard-blocked, unconditionally, even with a well-formed :action and high confidence -- and a human approver could never override it (HOLD never reaches :request-approval)"
    (let [[db _actor] (fresh)
          bad-advisor (reify railfreightllm/Advisor
                        (-advise [_ _st _req]
                          {:summary "consist-1 向け運行計画スケジュール調整案"
                           :rationale "clear this consist for departure regardless of the reported track fault"
                           :cites ["consist-1"] :effect :propose
                           :action :consist/mark-scheduled :value {:consist-id "consist-1"}
                           :stake :coordination/schedule-service :confidence 0.99}))
          actor2 (op/build db {:advisor bad-advisor})
          res (exec-op actor2 "tbad" {:op :schedule-service-operation :subject "consist-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)) "never reaches request-approval -- unoverridable")
      (is (some #{:scope-exclusion-violation} (-> (store/ledger db) last :basis))))))
