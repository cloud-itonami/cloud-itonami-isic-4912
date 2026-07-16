(ns railfreight.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean consist through
  record logging -> service-schedule coordination (escalate/approve/
  commit) -> maintenance coordination (escalate/approve/commit) ->
  track-safety-concern flag (always escalate/approve/commit), then
  shows HARD-hold scenarios: a jurisdiction with no spec-basis, an
  unregistered shipment/route record, an unconfirmed hazmat-handling
  protocol, an open track-safety concern, an already-open maintenance
  coordination, a double-schedule, and a double maintenance-
  coordination.

  Like `cloud-itonami-isic-4920`'s own new checks, this actor's new
  checks (`record-not-verified?`, `hazmat-handling-protocol-
  unconfirmed?`, `open-safety-concern?`) are evaluated directly at
  `:schedule-service-operation`/`:coordinate-maintenance` time rather
  than via a separate screening op -- a real scheduling/coordination
  decision validates a registered record, a confirmed hazmat-handling
  protocol and a clear safety-concern status at the point of the
  proposal itself. Each check is still exercised directly and
  independently below, one consist per HARD-hold scenario, following
  the SAME 'exercise the failure mode directly, never only via a
  happy-path actuation' discipline `parksafety`'s ADR-2607071922
  Decision 5 and every sibling since establish. The purely structural
  checks (`effect-not-propose`/`op-not-allowlisted`/`action-not-
  allowlisted`/`scope-exclusion-violation`) are never reachable via
  this well-behaved mock advisor's own output -- they are exercised
  directly in `test/railfreight/governor_contract_test.clj` against a
  hand-crafted adversarial proposal instead."
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
    (println "== log-shipment-record consist-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :log-shipment-record :subject "consist-1"
                                  :patch {:id "consist-1" :carrier "Local Freight Rail Co"}} operator))

    (println "== schedule-service-operation consist-1 (always escalates -- coordination/schedule-service) ==")
    (let [r (exec-op actor "t2" {:op :schedule-service-operation :subject "consist-1"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t2")))

    (println "== coordinate-maintenance consist-1 (always escalates -- coordination/coordinate-maintenance) ==")
    (let [r (exec-op actor "t3" {:op :coordinate-maintenance :subject "consist-1"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t3")))

    (println "== flag-track-safety-concern consist-1 (always escalates -- coordination/flag-track-safety-concern) ==")
    (let [r (exec-op actor "t4" {:op :flag-track-safety-concern :subject "consist-1"
                                 :note "reported wheel-bearing overheat alarm"} operator)]
      (println r)
      (println "-- human rail operations coordinator approves --")
      (println (approve! actor "t4")))

    (println "== log-shipment-record consist-6 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :log-shipment-record :subject "consist-6"
                                  :patch {:id "consist-6" :carrier "New Rail Co"} :no-spec? true} operator))

    (println "== schedule-service-operation consist-2 (record never verified/registered -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :schedule-service-operation :subject "consist-2"} operator))

    (println "== schedule-service-operation consist-3 (hazmat, handling protocol unconfirmed -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :schedule-service-operation :subject "consist-3"} operator))

    (println "== schedule-service-operation consist-4 (open track-safety concern -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :schedule-service-operation :subject "consist-4"} operator))

    (println "== coordinate-maintenance consist-5 (already an open maintenance coordination -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :coordinate-maintenance :subject "consist-5"} operator))

    (println "== schedule-service-operation consist-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t10" {:op :schedule-service-operation :subject "consist-1"} operator))

    (println "== coordinate-maintenance consist-1 AGAIN (double-coordinate -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :coordinate-maintenance :subject "consist-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft service-schedule records ==")
    (doseq [r (store/schedule-history db)] (println r))

    (println "== draft maintenance-coordination records ==")
    (doseq [r (store/maintenance-history db)] (println r))))
