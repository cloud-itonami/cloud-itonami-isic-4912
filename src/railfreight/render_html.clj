(ns railfreight.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had NO
  demo page and no generator at all. This namespace drives the REAL
  actor stack -- `railfreight.operation` (a langgraph-clj StateGraph)
  -> `railfreight.railfreightllm` (the contained advisor) ->
  `railfreight.governor` (the independent censor) -> `railfreight.phase`
  (the rollout gate) -> `railfreight.store` -- and renders the page
  from the RESULTING store, ledger and draft-record histories.

  NOTHING on the page is hand-typed telemetry. Every consist id,
  carrier, route, cargo line, schedule number, maintenance number,
  inspection code, hold rule and hold detail string is read back out of
  the store/ledger this run actually produced; the action-gate table is
  derived from `railfreight.phase/phases` and
  `railfreight.governor/allowed-ops`/`high-stakes` rather than
  transcribed. If a governor check is renamed or a phase's `:auto` set
  changes, this page changes with it.

  DETERMINISM. Nothing in this actor's write path reads a clock or a
  RNG: `railfreight.registry` builds jurisdiction-scoped sequence
  numbers (`JPN-SCH-000000`) from a counter in the store, the ledger is
  an append-only vector, and `store/all-consists` sorts by `:id`. The
  page therefore carries no timestamp and no generated id, and two runs
  from the same seed are byte-identical (verify by diffing two
  consecutive runs). Sets read out of the source namespaces are sorted
  explicitly before rendering, because set iteration order is not part
  of any contract.

  BUILD-TIME INVARIANT. `-main` refuses to write the file if the run
  produced ZERO `:governor-hold` facts. The whole point of this console
  is to show that a HARD hold is un-overridable and never reaches a
  human, so a scenario that quietly stopped producing holds would make
  the page a lie -- that failure is a build failure here, not a
  convention.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [railfreight.store :as store]
            [railfreight.governor :as governor]
            [railfreight.phase :as phase]
            [railfreight.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  "The same operator context `railfreight.sim` uses -- phase 3
  (supervised-auto), so the page shows the MOST permissive rollout
  phase this actor has and the holds below are still un-overridable."
  {:actor-id "op-1" :actor-role :rail-operations-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario reaching every
  disposition this actor can produce, adapted from this repo's own
  `railfreight.sim` demo driver (`clojure -M:dev:run`, run BEFORE
  writing this file to confirm the seeded consist ids and hold reasons
  below are the ones the actor really emits).

  HARD holds first, each against a fixture whose state the hold itself
  never mutates (a hold never reaches `:commit`), so ordering cannot
  smuggle in an accidental state dependency:

    consist-2  `:log-shipment-record` with no operator-supplied
               spec-basis                     -> :no-spec-basis
    consist-2  `:schedule-service-operation` on a record that was
               therefore never registered     -> :record-not-verified
    consist-3  `:schedule-service-operation` on a hazmat consist whose
               transport scope is unconfirmed -> :hazmat-handling-protocol-unconfirmed
    consist-4  `:schedule-service-operation` with an unresolved
               track-safety concern on file   -> :open-safety-concern
    consist-5  `:coordinate-maintenance` while one is already open
                                              -> :already-coordinating
    consist-5  `:release-rolling-stock-from-maintenance` with no
               completed inspection on file   -> :maintenance-release-uninspected
    consist-4  `:release-rolling-stock-from-maintenance` -- three
               independent checks fire at once
                  -> :maintenance-not-open, :maintenance-release-uninspected,
                     :open-safety-concern
    consist-1  `:log-inspection-record` citing a code outside the
               closed vocabulary              -> :inspection-record-invalid
    consist-1  `:register-hazmat-transport-scope` with no evidence
                                              -> :hazmat-scope-evidence-missing
    consist-1  `:log-reconciliation-record` with no evidence
                                              -> :reconciliation-evidence-missing

  Then the clean paths, including two holds that a LATER, correct
  operation resolves -- so the console shows the block and the way
  through it, not just the block:

    consist-1  full lifecycle: record logging (auto-commit -- pure data
               logging is the only phase-3 `:auto` class), service
               schedule (escalate -> approve -> commit), a repeat
               schedule (-> :already-scheduled HARD hold), maintenance
               coordination (escalate -> approve -> commit), inspection
               log and reconciliation record (auto-commit),
               track-safety-concern flag (escalate -> approve ->
               commit)
    consist-2  the same record re-logged WITH the operator's citation
               (auto-commit) then scheduled (escalate -> approve ->
               commit) -- the :no-spec-basis / :record-not-verified
               pair above, resolved
    consist-3  hazmat transport scope registered on operator evidence
               (escalate -> approve -> commit) then scheduled
               (escalate -> approve -> commit) -- the
               :hazmat-handling-protocol-unconfirmed hold, resolved
    consist-7  released from maintenance on its pre-seeded passing
               inspection (escalate -> approve -> commit)

  consist-4 (open safety concern) and consist-5 (open maintenance, no
  inspection) are deliberately left blocked, so the page shows standing
  blocks and not only resolved ones.

  Returns the resulting store."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; ---------------------- HARD holds (no state mutation) ----------------------
    (exec! actor "h1" {:op :log-shipment-record :subject "consist-2"
                       :patch {:id "consist-2" :carrier "Local Freight Rail Co"}})
    (exec! actor "h2" {:op :schedule-service-operation :subject "consist-2"})
    (exec! actor "h3" {:op :schedule-service-operation :subject "consist-3"})
    (exec! actor "h4" {:op :schedule-service-operation :subject "consist-4"})
    (exec! actor "h5" {:op :coordinate-maintenance :subject "consist-5"})
    (exec! actor "h6" {:op :release-rolling-stock-from-maintenance :subject "consist-5"})
    (exec! actor "h7" {:op :release-rolling-stock-from-maintenance :subject "consist-4"})
    (exec! actor "h8" {:op :log-inspection-record :subject "consist-1"
                       :inspection-result "excellent"})
    (exec! actor "h9" {:op :register-hazmat-transport-scope :subject "consist-1"})
    (exec! actor "h10" {:op :log-reconciliation-record :subject "consist-1"})

    ;; ---------------------- clean lifecycle (consist-1) ----------------------
    (exec! actor "c1-log" {:op :log-shipment-record :subject "consist-1"
                           :patch {:id "consist-1" :carrier "Local Freight Rail Co"}
                           :spec-basis "operator-submitted-sms-registration-JPN-0001"
                           :legal-basis "operator-submitted"})

    (exec! actor "c1-sched" {:op :schedule-service-operation :subject "consist-1"})
    (approve! actor "c1-sched")

    ;; the same schedule again -- a HARD double-actuation hold
    (exec! actor "c1-sched-again" {:op :schedule-service-operation :subject "consist-1"})

    (exec! actor "c1-mnt" {:op :coordinate-maintenance :subject "consist-1"})
    (approve! actor "c1-mnt")

    (exec! actor "c1-insp" {:op :log-inspection-record :subject "consist-1"
                            :inspection-result "pass"})
    (exec! actor "c1-recon" {:op :log-reconciliation-record :subject "consist-1"
                             :evidence "operator-submitted-invoice-ref-0001"
                             :amount 15000})

    (exec! actor "c1-concern" {:op :flag-track-safety-concern :subject "consist-1"
                               :note "reported wheel-bearing overheat alarm"})
    (approve! actor "c1-concern")

    ;; ---------------- the :no-spec-basis hold, resolved (consist-2) ----------------
    (exec! actor "c2-log" {:op :log-shipment-record :subject "consist-2"
                           :patch {:id "consist-2" :carrier "Local Freight Rail Co"}
                           :spec-basis "operator-submitted-sms-registration-JPN-0002"
                           :legal-basis "operator-submitted"})
    (exec! actor "c2-sched" {:op :schedule-service-operation :subject "consist-2"})
    (approve! actor "c2-sched")

    ;; ------------- the hazmat-scope hold, resolved on evidence (consist-3) -------------
    (exec! actor "c3-hazmat" {:op :register-hazmat-transport-scope :subject "consist-3"
                              :evidence "operator-submitted-hazmat-handling-protocol-ack-0003"})
    (approve! actor "c3-hazmat")
    (exec! actor "c3-sched" {:op :schedule-service-operation :subject "consist-3"})
    (approve! actor "c3-sched")

    ;; ---------------- maintenance release on a passing inspection (consist-7) ----------------
    (exec! actor "c7-release" {:op :release-rolling-stock-from-maintenance :subject "consist-7"})
    (approve! actor "c7-release")

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [v] (if (keyword? v) (name v) (str v)))

(defn- ok   [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- crit [s] (str "<span class=\"critical\">" s "</span>"))
(defn- mute [s] (str "<span class=\"muted\">" s "</span>"))

(defn- hard-hold?
  "A `:governor-hold` fact whose basis is not the approver's own
  rejection -- i.e. a hold a human could never have overridden."
  [f]
  (and (= :governor-hold (:t f))
       (not (some #{:approver-rejected} (:basis f)))))

(defn- last-fact-for [ledger id]
  (last (filter #(= (:subject %) id) ledger)))

(defn- last-op-cell [ledger id]
  (let [f (last-fact-for ledger id)]
    (cond
      (nil? f) (mute "no activity")
      (= :committed (:t f)) (ok (str "committed &middot; " (esc (kw-name (:op f)))))
      (hard-hold? f) (crit (str "HARD hold &middot; "
                                (esc (str/join ", " (map kw-name (:basis f))))))
      (= :governor-hold (:t f)) (warn "held")
      :else (mute (esc (kw-name (:t f)))))))

(defn- yes-no [v yes-label no-label yes-fn no-fn]
  (if v (yes-fn yes-label) (no-fn no-label)))

(defn- consist-row
  [ledger {:keys [id carrier origin destination cargo-manifest hazmat?
                  registered? hazmat-handling-confirmed? last-inspection-result
                  scheduled? schedule-number maintenance-open? maintenance-number
                  safety-concern-raised? safety-concern-resolved?
                  last-reconciliation-amount jurisdiction]}]
  (let [concern-open? (and safety-concern-raised? (not safety-concern-resolved?))]
    (str "        <tr>"
         "<td><code>" (esc id) "</code></td>"
         "<td>" (esc carrier) "</td>"
         "<td>" (esc origin) " &rarr; " (esc destination) "</td>"
         "<td>" (esc (str/join "; " cargo-manifest)) "</td>"
         "<td>" (if hazmat? (warn "hazmat") (mute "general")) "</td>"
         "<td>" (yes-no registered? "verified" "unregistered" ok crit) "</td>"
         "<td>" (cond
                  (not hazmat?) (mute "n/a")
                  hazmat-handling-confirmed? (ok "confirmed")
                  :else (crit "unconfirmed")) "</td>"
         "<td>" (if scheduled?
                  (ok (str "<code>" (esc schedule-number) "</code>"))
                  (mute "not scheduled")) "</td>"
         "<td>" (if maintenance-open?
                  (warn (str "open" (when maintenance-number
                                      (str " &middot; <code>" (esc maintenance-number) "</code>"))))
                  (mute "none open")) "</td>"
         "<td>" (if last-inspection-result
                  (if (= "pass" last-inspection-result)
                    (ok (esc last-inspection-result))
                    (warn (esc last-inspection-result)))
                  (mute "none")) "</td>"
         "<td>" (if concern-open? (crit "open") (mute "clear")) "</td>"
         "<td>" (if last-reconciliation-amount
                  (str "<span class=\"num\">" (esc last-reconciliation-amount) "</span>")
                  (mute "&mdash;")) "</td>"
         "<td>" (esc jurisdiction) "</td>"
         "<td>" (last-op-cell ledger id) "</td>"
         "</tr>")))

(defn- gate-rows
  "The action gate, DERIVED from `railfreight.phase/phases` at the
  operator's own phase and from `railfreight.governor/allowed-ops` --
  not transcribed. `:writes` says the op may write at all in this
  phase; `:auto` says it may commit without a human when the governor
  is clean. The five coordination ops are absent from every phase's
  `:auto` set as a structural fact, so they read as always-human here
  because the source data says so."
  [phase-n]
  (let [{:keys [label writes auto]} (get phase/phases phase-n)]
    (for [o (sort-by kw-name governor/allowed-ops)]
      (str "        <tr>"
           "<td><code>" (esc o) "</code></td>"
           "<td>" (yes-no (contains? writes o)
                          (str "writable in phase " phase-n " (" (esc label) ")")
                          (str "disabled in phase " phase-n)
                          ok crit) "</td>"
           "<td>" (if (contains? auto o)
                    (ok "may auto-commit when the governor is clean")
                    (warn "ALWAYS human approval &middot; never in any phase&rsquo;s :auto set")) "</td>"
           "</tr>"))))

(defn- hold-reason-rows
  "One row per DISTINCT hold rule this run actually produced, carrying
  the governor's own detail string verbatim (first occurrence), in
  ledger order."
  [ledger]
  (let [violations (->> ledger (filter hard-hold?) (mapcat :violations))]
    (->> violations
         (reduce (fn [acc {:keys [rule detail]}]
                   (if (contains? (:seen acc) rule)
                     (update-in acc [:counts rule] inc)
                     (-> acc
                         (update :seen conj rule)
                         (update :order conj [rule detail])
                         (assoc-in [:counts rule] 1))))
                 {:seen #{} :order [] :counts {}})
         ((fn [{:keys [order counts]}]
            (for [[rule detail] order]
              (str "        <tr>"
                   "<td><code>" (esc rule) "</code></td>"
                   "<td><span class=\"num\">" (get counts rule) "</span></td>"
                   "<td>" (esc detail) "</td>"
                   "</tr>")))))))

(defn- ledger-row [{:keys [t op subject basis summary] :as f}]
  (str "        <tr>"
       "<td>" (cond
                (= :committed t) (ok "committed")
                (hard-hold? f) (crit "HARD hold")
                (= :governor-hold t) (warn "hold")
                :else (esc (kw-name t))) "</td>"
       "<td><code>" (esc op) "</code></td>"
       "<td><code>" (esc subject) "</code></td>"
       "<td>" (esc (str/join ", " (map kw-name basis))) "</td>"
       "<td>" (esc (or summary "")) "</td>"
       "</tr>"))

(defn- draft-rows [records]
  (for [r records]
    (str "        <tr>"
         "<td><code>" (esc (get r "record_id")) "</code></td>"
         "<td>" (esc (get r "kind")) "</td>"
         "<td><code>" (esc (get r "consist_id")) "</code></td>"
         "<td>" (esc (get r "jurisdiction")) "</td>"
         "<td>" (if (get r "immutable") (ok "immutable") (mute "mutable")) "</td>"
         "</tr>")))

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        consists (store/all-consists db)
        commits (filter #(= :committed (:t %)) ledger)
        holds (filter hard-hold? ledger)
        phase-n (:phase operator)]
    (str
     "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-4912 &middot; community freight rail operations coordination</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Freight rail transport (ISIC 4912) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; scheduling / safety-concern / maintenance / hazmat-scope / release always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>This run</h2>\n"
     "    <p class=\"muted\">Build-time generated from <code>railfreight.store</code> by <code>railfreight.render-html</code> (<code>clojure -M:dev:render-html</code>), driving the real <code>railfreight.operation</code> StateGraph through <code>railfreight.governor</code> at rollout phase <span class=\"num\">" phase-n "</span> (<code>" (esc (:label (get phase/phases phase-n))) "</code>). No timestamps, no generated ids &mdash; two runs from the same seed are byte-identical.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Measure</th><th>Count</th></tr></thead>\n"
     "      <tbody>\n"
     "        <tr><td>Ledger facts</td><td><span class=\"num\">" (count ledger) "</span></td></tr>\n"
     "        <tr><td>Committed operations</td><td><span class=\"num\">" (count commits) "</span></td></tr>\n"
     "        <tr><td>HARD governor holds (never reached a human)</td><td><span class=\"num\">" (count holds) "</span></td></tr>\n"
     "        <tr><td>Distinct hold rules exercised</td><td><span class=\"num\">" (count (distinct (mapcat :basis holds))) "</span></td></tr>\n"
     "        <tr><td>Draft service-schedule records</td><td><span class=\"num\">" (count (store/schedule-history db)) "</span></td></tr>\n"
     "        <tr><td>Draft maintenance-coordination records</td><td><span class=\"num\">" (count (store/maintenance-history db)) "</span></td></tr>\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Consists under coordination</h2>\n"
     "    <p class=\"muted\">Every cell below is read back out of the store this run produced. <code>consist-4</code> (unresolved track-safety concern) and <code>consist-5</code> (open maintenance, no completed inspection) stay blocked deliberately &mdash; a standing block is as real an outcome as a resolved one.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Consist</th><th>Carrier</th><th>Route</th><th>Cargo manifest</th><th>Cargo class</th><th>Record</th><th>Hazmat scope</th><th>Service schedule</th><th>Maintenance</th><th>Last inspection</th><th>Safety concern</th><th>Reconciled</th><th>Jurisdiction</th><th>Last op</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial consist-row ledger) consists)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Rail Freight Governor + rollout phase)</h2>\n"
     "    <p class=\"muted\">Derived from <code>railfreight.governor/allowed-ops</code> and <code>railfreight.phase/phases</code>, not transcribed. The closed <code>:action</code> allowlist has <span class=\"num\">" (count governor/allowed-actions) "</span> members and the scope-exclusion scanner carries <span class=\"num\">" (count governor/scope-exclusion-actions) "</span> finalization-action phrases; a track/dispatch-safety-authority decision is not representable in either, so it is blocked structurally rather than by policy. Confidence floor <span class=\"num\">" governor/confidence-floor "</span>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Write gate</th><th>Commit gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (gate-rows phase-n)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds this run</h2>\n"
     "    <p class=\"muted\">A HARD governor violation is un-overridable: the run never reaches the approval node at all, so no human ever sees it. Detail text is the governor&rsquo;s own.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Times</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hold-reason-rows ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log &mdash; every hold and every commit, in order. Basis is the cited evidence for a commit and the violated rules for a hold.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Disposition</th><th>Op</th><th>Consist</th><th>Basis</th><th>Summary</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Draft coordination records</h2>\n"
     "    <p class=\"muted\">Built by <code>railfreight.registry</code> &mdash; a jurisdiction-scoped coordination DRAFT, never a real dispatch clearance or maintenance-release decision. Every certificate this actor emits is unsigned; signature is the certified operator&rsquo;s act.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record</th><th>Kind</th><th>Consist</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (concat (draft-rows (store/schedule-history db))
                            (draft-rows (store/maintenance-history db)))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "</main>\n"
     "<footer>\n"
     "  <p>This actor coordinates freight-rail operations. It is not the dispatcher, not the track-safety authority, and not rolling-stock control &mdash; see <code>railfreight.governor</code> ns docstring <code>SCOPE</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        ledger (vec (store/ledger db))
        holds (filterv #(= :governor-hold (:t %)) ledger)]
    ;; Build-time invariant, not a convention: this console exists to show
    ;; that a HARD hold is un-overridable and never reaches a human. A run
    ;; that produced no hold at all would render a page making a claim its
    ;; own evidence does not support, so refuse to write it.
    (when (zero? (count holds))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced ZERO :governor-hold ledger facts, so the"
                           " console cannot honestly show a HARD hold. Fix railfreight.render-html/run-demo!"
                           " (or the governor) before regenerating.")
                      {:out out :ledger-facts (count ledger) :holds 0})))
    (spit out (render db))
    (println "wrote" out "-" (count ledger) "ledger facts,"
             (count holds) "governor holds,"
             (count (filter #(= :committed (:t %)) ledger)) "commits,"
             (count (store/schedule-history db)) "schedule drafts,"
             (count (store/maintenance-history db)) "maintenance drafts")))
