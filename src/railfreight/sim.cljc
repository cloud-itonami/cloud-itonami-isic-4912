(ns railfreight.sim
  "Demo driver -- `clojure -M:dev:run`. Walks the HARD-hold scenarios
  first (each against a fixture whose state the hold itself never
  mutates, so ordering can't smuggle in an accidental state
  dependency), THEN a clean consist through the full happy-path
  lifecycle: record logging -> service-schedule coordination (escalate/
  approve/commit, + a rejected double-schedule) -> maintenance
  coordination (escalate/approve/commit, + a rejected double-
  coordinate) -> track-safety-concern flag (always escalate/approve/
  commit) -> track/rolling-stock inspection logging (auto-commit) ->
  booking/reconciliation record (auto-commit) -> hazmat-transport-scope
  confirmation on a separate hazmat consist (escalate/approve/commit)
  -> maintenance release on a pre-seeded, already-inspected consist
  (escalate/approve/commit).

  HARD-hold scenarios covered: a `:log-shipment-record` with no
  operator-supplied spec-basis, an unregistered shipment/route record,
  an unconfirmed hazmat-transport scope, an open track-safety concern
  (on `:schedule-service-operation`), an already-open maintenance
  coordination, a maintenance-release attempt with no open maintenance
  request, a maintenance-release attempt with no completed inspection
  on file, a hazmat-transport-scope confirmation with no evidence, an
  inspection record with an unrecognized result code, and a
  reconciliation record with no evidence.

  Like `cloud-itonami-isic-4920`'s own checks, this actor's checks are
  evaluated directly at the relevant op's own proposal time rather
  than via a separate screening op. Each check is exercised directly
  and independently below, following the SAME 'exercise the failure
  mode directly, never only via a happy-path actuation' discipline
  `parksafety`'s ADR-2607071922 Decision 5 and every sibling since
  establish. The purely structural checks (`effect-not-propose`/`op-
  not-allowlisted`/`action-not-allowlisted`/`scope-exclusion-
  violation`) are never reachable via this well-behaved mock advisor's
  own output -- they are exercised directly in
  `test/railfreight/governor_contract_test.clj` against a hand-crafted
  adversarial proposal instead."
  (:require [langgraph.graph :as g]
            [railfreight.store :as store]
            [railfreight.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :rail-operations-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; ------------------------- HARD-hold scenarios -------------------------
    ;; None of these mutate consist state (governor rejects before :commit),
    ;; so they can run in any order ahead of the happy path below.

    (println "== schedule-service-operation consist-2 (record never verified/registered -> HARD hold) ==")
    (println (exec-op actor "h1" {:op :schedule-service-operation :subject "consist-2"} operator))

    (println "== schedule-service-operation consist-3 (hazmat, transport-scope not yet confirmed -> HARD hold) ==")
    (println (exec-op actor "h2" {:op :schedule-service-operation :subject "consist-3"} operator))

    (println "== schedule-service-operation consist-4 (open track-safety concern -> HARD hold) ==")
    (println (exec-op actor "h3" {:op :schedule-service-operation :subject "consist-4"} operator))

    (println "== coordinate-maintenance consist-5 (already an open maintenance coordination -> HARD hold) ==")
    (println (exec-op actor "h4" {:op :coordinate-maintenance :subject "consist-5"} operator))

    (println "== release-rolling-stock-from-maintenance consist-4 (no open maintenance -> HARD hold) ==")
    (println (exec-op actor "h5" {:op :release-rolling-stock-from-maintenance :subject "consist-4"} operator))

    (println "== release-rolling-stock-from-maintenance consist-5 (open maintenance but NO completed inspection on file -> HARD hold) ==")
    (println (exec-op actor "h6" {:op :release-rolling-stock-from-maintenance :subject "consist-5"} operator))

    (println "== log-shipment-record consist-6 (no operator-supplied spec-basis -> HARD hold) ==")
    (println (exec-op actor "h7" {:op :log-shipment-record :subject "consist-6"
                                  :patch {:id "consist-6" :carrier "New Rail Co"}} operator))

    (println "== log-inspection-record consist-1 with an unrecognized result code (-> HARD hold) ==")
    (println (exec-op actor "h8" {:op :log-inspection-record :subject "consist-1"
                                  :inspection-result "excellent"} operator))

    (println "== register-hazmat-transport-scope consist-1 with no evidence (-> HARD hold) ==")
    (println (exec-op actor "h9" {:op :register-hazmat-transport-scope :subject "consist-1"} operator))

    (println "== log-reconciliation-record consist-1 with no evidence (-> HARD hold) ==")
    (println (exec-op actor "h10" {:op :log-reconciliation-record :subject "consist-1"} operator))

    ;; ------------------------- happy path (consist-1) -------------------------

    (println "\n== log-shipment-record consist-1 (JPN, operator-supplied spec-basis, clean) ==")
    (println (exec-op actor "t1" {:op :log-shipment-record :subject "consist-1"
                                  :patch {:id "consist-1" :carrier "Local Freight Rail Co"}
                                  :spec-basis "operator-submitted-sms-registration-JPN-0001"
                                  :legal-basis "operator-submitted"} operator))

    (println "== schedule-service-operation consist-1 (always escalates -- coordination/schedule-service) ==")
    (let [r (exec-op actor "t2" {:op :schedule-service-operation :subject "consist-1"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t2")))

    (println "== schedule-service-operation consist-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t3" {:op :schedule-service-operation :subject "consist-1"} operator))

    (println "== coordinate-maintenance consist-1 (always escalates -- coordination/coordinate-maintenance) ==")
    (let [r (exec-op actor "t4" {:op :coordinate-maintenance :subject "consist-1"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t4")))

    (println "== coordinate-maintenance consist-1 AGAIN (double-coordinate -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :coordinate-maintenance :subject "consist-1"} operator))

    (println "== flag-track-safety-concern consist-1 (always escalates -- coordination/flag-track-safety-concern) ==")
    (let [r (exec-op actor "t6" {:op :flag-track-safety-concern :subject "consist-1"
                                 :note "reported wheel-bearing overheat alarm"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t6")))

    (println "== log-inspection-record consist-1 (auto-commits -- pure data logging, still fine with an open concern) ==")
    (println (exec-op actor "t7" {:op :log-inspection-record :subject "consist-1"
                                  :inspection-result "pass"} operator))

    (println "== log-reconciliation-record consist-1 (auto-commits -- pure data logging, evidence present) ==")
    (println (exec-op actor "t8" {:op :log-reconciliation-record :subject "consist-1"
                                  :evidence "operator-submitted-invoice-ref-0001" :amount 15000} operator))

    ;; ------------------------- happy path (consist-3, hazmat) -------------------------

    (println "== register-hazmat-transport-scope consist-3 (always escalates -- coordination/register-hazmat-transport-scope) ==")
    (let [r (exec-op actor "t9" {:op :register-hazmat-transport-scope :subject "consist-3"
                                 :evidence "operator-submitted-hazmat-handling-protocol-ack-0003"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t9")))

    ;; ------------------------- happy path (consist-7, pre-seeded ready-to-release) -------------------------

    (println "== release-rolling-stock-from-maintenance consist-7 (already inspected -- always escalates) ==")
    (let [r (exec-op actor "t10" {:op :release-rolling-stock-from-maintenance :subject "consist-7"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t10")))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft service-schedule records ==")
    (doseq [r (store/schedule-history db)] (println r))

    (println "== draft maintenance-coordination records ==")
    (doseq [r (store/maintenance-history db)] (println r))))
