(ns railfreight.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-4920`'s
  own `freightops.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [railfreight.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/consist s "consist-1"))))
      (is (true? (:registered? (store/consist s "consist-1"))))
      (is (false? (:registered? (store/consist s "consist-2"))))
      (is (true? (:hazmat? (store/consist s "consist-3"))))
      (is (false? (:hazmat-handling-confirmed? (store/consist s "consist-3"))))
      (is (true? (:safety-concern-raised? (store/consist s "consist-4"))))
      (is (false? (:safety-concern-resolved? (store/consist s "consist-4"))))
      (is (true? (:maintenance-open? (store/consist s "consist-5"))))
      (is (false? (:scheduled? (store/consist s "consist-1"))))
      (is (nil? (:last-inspection-result (store/consist s "consist-1"))))
      (is (= "pass" (:last-inspection-result (store/consist s "consist-7"))))
      (is (true? (:maintenance-open? (store/consist s "consist-7"))))
      (is (nil? (:hazmat-handling-evidence (store/consist s "consist-1"))))
      (is (= ["consist-1" "consist-2" "consist-3" "consist-4" "consist-5" "consist-7"]
             (mapv :id (store/all-consists s))))
      (is (= [] (store/ledger s)))
      (is (= [] (store/schedule-history s)))
      (is (= [] (store/maintenance-history s))
          "the pre-seeded consist-5 flag is not itself a committed-history entry")
      (is (zero? (store/next-schedule-sequence s "JPN")))
      (is (zero? (store/next-maintenance-sequence s "JPN")))
      (is (false? (store/consist-already-scheduled? s "consist-1")))
      (is (true? (store/consist-maintenance-already-open? s "consist-5"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:action :consist/log :path ["consist-1"]
                                 :value {:patch {:id "consist-1" :carrier "Updated Rail Co"}
                                        :spec-basis "https://example.test/spec" :legal-basis "Test Act"}})
        (is (= "Updated Rail Co" (:carrier (store/consist s "consist-1"))))
        (is (= "Yard A" (:origin (store/consist s "consist-1"))) "unrelated field preserved")
        (is (true? (:registered? (store/consist s "consist-1")))))
      (testing "safety-concern flag commits"
        (store/commit-record! s {:action :consist/flag-safety-concern :path ["consist-1"]
                                 :value {:note "reported hot-box alarm"}})
        (is (true? (:safety-concern-raised? (store/consist s "consist-1"))))
        (is (false? (:safety-concern-resolved? (store/consist s "consist-1")))))
      (testing "service schedule drafts a record and advances the schedule sequence"
        (store/commit-record! s {:action :consist/mark-scheduled :path ["consist-1"]})
        (is (= "JPN-SCH-000000" (get (first (store/schedule-history s)) "record_id")))
        (is (= "service-schedule-draft" (get (first (store/schedule-history s)) "kind")))
        (is (true? (:scheduled? (store/consist s "consist-1"))))
        (is (= 1 (count (store/schedule-history s))))
        (is (= 1 (store/next-schedule-sequence s "JPN")))
        (is (true? (store/consist-already-scheduled? s "consist-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s)))))
      (testing "hazmat-transport-scope confirmation commits"
        (store/commit-record! s {:action :consist/confirm-hazmat-handling :path ["consist-1"]
                                 :value {:evidence "operator-submitted-hazmat-handling-protocol-ack"}})
        (is (true? (:hazmat-handling-confirmed? (store/consist s "consist-1"))))
        (is (= "operator-submitted-hazmat-handling-protocol-ack"
               (:hazmat-handling-evidence (store/consist s "consist-1")))))
      (testing "inspection record commits"
        (store/commit-record! s {:action :consist/log-inspection :path ["consist-1"]
                                 :value {:inspection-result "pass"}})
        (is (= "pass" (:last-inspection-result (store/consist s "consist-1")))))
      (testing "maintenance coordination drafts a record and opens the coordination"
        (store/commit-record! s {:action :consist/mark-maintenance-coordinated :path ["consist-1"]})
        (is (= "JPN-MNT-000000" (get (first (store/maintenance-history s)) "record_id")))
        (is (true? (:maintenance-open? (store/consist s "consist-1"))))
        (is (true? (store/consist-maintenance-already-open? s "consist-1"))))
      (testing "maintenance release commits and closes the open coordination"
        (store/commit-record! s {:action :consist/mark-maintenance-released :path ["consist-1"]})
        (is (false? (:maintenance-open? (store/consist s "consist-1")))))
      (testing "reconciliation record commits"
        (store/commit-record! s {:action :consist/log-reconciliation :path ["consist-1"]
                                 :value {:evidence "operator-submitted-invoice-ref-0001" :amount 15000}})
        (is (= "operator-submitted-invoice-ref-0001" (:last-reconciliation-evidence (store/consist s "consist-1"))))
        (is (= 15000 (:last-reconciliation-amount (store/consist s "consist-1"))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/consist s "nope")))
    (is (= [] (store/all-consists s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/schedule-history s)))
    (is (= [] (store/maintenance-history s)))
    (is (zero? (store/next-schedule-sequence s "JPN")))
    (is (zero? (store/next-maintenance-sequence s "JPN")))
    (store/with-consists s {"x" {:id "x" :carrier "c" :origin "o" :destination "d"
                                 :cargo-manifest ["1x boxcar"] :hazmat? false
                                 :registered? true :spec-basis "https://example.test/spec" :legal-basis "Test Act"
                                 :hazmat-handling-confirmed? false
                                 :safety-concern-raised? false :safety-concern-resolved? false
                                 :scheduled? false :schedule-number nil
                                 :maintenance-open? false :maintenance-number nil
                                 :jurisdiction "JPN" :status :intake}})
    (is (= "c" (:carrier (store/consist s "x"))))))
