(ns railfreight.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack -- `railfreight.operation/build` (a
  compiled langgraph-clj StateGraph) over the REAL seeded store
  (`railfreight.store/seed-db`), through the REAL Rail Freight Governor
  (`railfreight.governor/check`) and the REAL rollout phase gate
  (`railfreight.phase/gate`) -- and renders whatever those produced.
  Nothing on the page is hand-written domain content:

    - every consist row is read back out of the store after the run
      (`store/all-consists`, `store/ledger`, `store/schedule-history`,
      `store/maintenance-history`),
    - every HARD-hold rule name and every violation detail string is
      the governor's own `:violations` entry off the ledger fact --
      never a literal in this namespace,
    - the phase-gate table is derived from `railfreight.phase/phases`
      and the governor-configuration table from the public vars of
      `railfreight.governor` / `railfreight.facts`.

  The ONLY hand-written text is each scenario's `:exercises` note (a
  static description of the fixed op-gate contract this run is meant to
  demonstrate) and the prose in the section headers -- both are
  explicitly labelled where they appear.

  Subject provenance (the demo may not invent subjects): every
  `:subject` driven below is a consist id actually present in
  `railfreight.store/demo-data` -- `consist-1` `consist-2` `consist-3`
  `consist-4` `consist-5` `consist-7`. `railfreight.sim`'s own
  `consist-6` scenario is deliberately NOT reused here: that id is not
  in the seed, so its hold is about a missing operator citation on a
  consist that does not exist. The same `:no-spec-basis` rule is
  exercised here against the real, deliberately-unregistered
  `consist-2` instead.

  Deterministic: no clock, no randomness, no network, no timestamp in
  the page content. Re-running writes a byte-identical file.

  Run: `clojure -M:dev:render-html [out-file]`
  (default out-file `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [railfreight.facts :as facts]
            [railfreight.governor :as governor]
            [railfreight.operation :as op]
            [railfreight.phase :as phase]
            [railfreight.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  {:actor-id "op-1" :actor-role :rail-operations-coordinator
   :phase phase/default-phase})

(def ^:private scenarios
  "One entry = one coordination request driven through the real actor.
  `:approval`, when present, is the human decision handed back to the
  paused graph (`interrupt-before #{:request-approval}`); it is only
  ever offered when the graph actually paused, so a HARD hold never
  reaches it.

  ORDER MATTERS. Governor HOLDs never mutate the SSoT, so they can sit
  anywhere; commits do, so the ordering below is deliberate:
  `consist-1`'s double-schedule hold must follow its first schedule
  commit, `consist-3`'s unconfirmed-hazmat hold must precede its
  hazmat-transport-scope commit, and `consist-1`'s track-safety-concern
  flag is last of its coordination ops because committing it raises a
  concern that would then block them.

  `:exercises` is the one piece of hand-written prose on the rendered
  page: a static description of this actor's own fixed op-gate
  contract, NOT runtime telemetry."
  [{:tid "t01"
    :exercises "Shipment/route record logging with an operator-supplied spec-basis. Governor-clean and :log-shipment-record is one of the three pure data-logging ops in phase 3's :auto set -> auto-commits with no human."
    :request {:op :log-shipment-record :subject "consist-1"
              :patch {:id "consist-1" :carrier "Local Freight Rail Co"}
              :spec-basis "operator-submitted-sms-registration-JPN-0001"
              :legal-basis "operator-submitted"}}

   {:tid "t02"
    :exercises "The same op with NO operator-supplied citation. This actor never invents a spec-basis to fill the gap, so the proposal carries none and the governor HARD-holds it."
    :request {:op :log-shipment-record :subject "consist-2"
              :patch {:id "consist-2" :carrier "Local Freight Rail Co"}}}

   {:tid "t03"
    :exercises "Service scheduling against consist-2, whose shipment/route record was never independently verified/registered. Every non-registration op requires that first. HARD hold."
    :request {:op :schedule-service-operation :subject "consist-2"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t04"
    :exercises "Service scheduling for a hazmat consist whose hazmat-transport-scope record is not on file. The governor re-reads the consist's own :hazmat-handling-confirmed? fact rather than believing the advisor. HARD hold."
    :request {:op :schedule-service-operation :subject "consist-3"}}

   {:tid "t05"
    :exercises "Service scheduling for a consist carrying an unresolved track-safety concern. Un-overridable: no approver can clear it. HARD hold."
    :request {:op :schedule-service-operation :subject "consist-4"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t06"
    :exercises "A SECOND maintenance-coordination request while one is already open, guarded off a dedicated :maintenance-open? boolean, never a :status value. HARD hold."
    :request {:op :coordinate-maintenance :subject "consist-5"}}

   {:tid "t07"
    :exercises "Releasing rolling stock whose maintenance IS open but which has no completed inspection record on file. Track/rolling stock is never marked serviceable uninspected. HARD hold."
    :request {:op :release-rolling-stock-from-maintenance :subject "consist-5"}}

   {:tid "t08"
    :exercises "Releasing consist-1, which has neither an open maintenance request nor an inspection record yet. Two independent checks fire on the one proposal. HARD hold."
    :request {:op :release-rolling-stock-from-maintenance :subject "consist-1"}}

   {:tid "t09"
    :exercises "An inspection record citing a result code outside railfreight.facts/inspection-results. An unrecognized code is not a plausible observation. HARD hold."
    :request {:op :log-inspection-record :subject "consist-1"
              :inspection-result "excellent"}}

   {:tid "t10"
    :exercises "A booking/reconciliation record with no verified evidence -- docs/business-model.md's own Trust Control. HARD hold."
    :request {:op :log-reconciliation-record :subject "consist-1"}}

   {:tid "t11"
    :exercises "A hazmat-transport-scope confirmation with no operator-submitted evidence. Hazmat transport cannot commit without a valid scope record. HARD hold."
    :request {:op :register-hazmat-transport-scope :subject "consist-1"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t12"
    :exercises "An op outside the closed allowlist -- a train-dispatch clearance this actor must never be able to represent. Both the op allowlist and the action allowlist reject it; it never reaches a human. HARD hold."
    :request {:op :clear-consist-for-departure :subject "consist-1"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t13"
    :exercises "Service scheduling on a clean consist. Governor-clean, but :schedule-service-operation is in no phase's :auto set at any phase -> escalates; the human rail operations coordinator approves and the schedule number is minted."
    :request {:op :schedule-service-operation :subject "consist-1"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t14"
    :exercises "The SAME consist scheduled twice, guarded off a dedicated :scheduled? boolean. HARD hold."
    :request {:op :schedule-service-operation :subject "consist-1"}}

   {:tid "t15"
    :exercises "Maintenance coordination on the same clean consist. Never auto-eligible -> escalates; approved, and the maintenance number is minted."
    :request {:op :coordinate-maintenance :subject "consist-1"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t16"
    :exercises "A robotics-assisted inspection record with a recognized result code. Pure data logging -> auto-commits; it is what later unlocks a maintenance release."
    :request {:op :log-inspection-record :subject "consist-1"
              :inspection-result "pass"}}

   {:tid "t17"
    :exercises "A booking/reconciliation record WITH operator-submitted evidence. Pure data logging -> auto-commits."
    :request {:op :log-reconciliation-record :subject "consist-1"
              :evidence "operator-submitted-invoice-ref-0001" :amount 15000}}

   {:tid "t18"
    :exercises "Flagging a track-safety concern. Always escalates -- this actor never resolves or dismisses a concern itself. Approved, which raises the concern on consist-1 and thereby blocks its own later scheduling/maintenance ops."
    :request {:op :flag-track-safety-concern :subject "consist-1"
              :note "reported wheel-bearing overheat alarm"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t19"
    :exercises "Maintenance coordination re-attempted on consist-1 AFTER t18 raised a concern on it. The identical request that committed at t15 now HARD-holds on TWO independent checks at once -- the open track-safety concern and the double-coordination guard."
    :request {:op :coordinate-maintenance :subject "consist-1"}}

   {:tid "t20"
    :exercises "Hazmat-transport-scope confirmation for the hazmat consist, WITH operator-submitted evidence. Always escalates; approved, which is what would clear t04's hold."
    :request {:op :register-hazmat-transport-scope :subject "consist-3"
              :evidence "operator-submitted-hazmat-handling-protocol-ack-0003"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t21"
    :exercises "Releasing consist-7 -- open maintenance AND a passing inspection already on file. Always escalates; approved."
    :request {:op :release-rolling-stock-from-maintenance :subject "consist-7"}
    :approval {:status :approved :by "op-1"}}

   {:tid "t22"
    :exercises "A governor-CLEAN maintenance coordination the human VETOES. Distinct from a HARD hold: the governor cleared it, a person did not."
    :request {:op :coordinate-maintenance :subject "consist-7"}
    :approval {:status :rejected :by "op-1"}}])

(defn- drive!
  "Runs one scenario through the real compiled graph and returns the
  scenario enriched with what the graph actually did."
  [actor {:keys [tid request approval] :as scenario}]
  (let [r1 (g/run* actor {:request request :context coordinator} {:thread-id tid})
        paused? (= :interrupted (:status r1))
        r2 (when (and approval paused?)
             (g/run* actor {:approval approval} {:thread-id tid :resume? true}))
        final (:state (or r2 r1))
        audit (:audit final [])]
    (assoc scenario
           :verdict (:verdict final)
           :paused? paused?
           :escalation (first (filter #(= :approval-requested (:t %)) audit))
           :human (when r2 (:status approval))
           :disposition (:disposition final))))

(defn run-demo!
  "Seeds a MemStore, builds the real actor, drives every scenario above
  through `langgraph.graph/run*` exactly as `railfreight.sim` does, and
  returns {:db store :runs [..]}. Every number, row and status the
  renderer below shows is read back out of this store or out of these
  run results -- none of it is typed by hand."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    {:db db :runs (mapv #(drive! actor %) scenarios)}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- fmt
  "Render a stored value, or an em dash when the domain model carries no
  value for that field on that record."
  [v]
  (if (nil? v) "—" (esc v)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- flag [v]
  (if (true? v)
    "<span class=\"ok\">true</span>"
    (str "<span class=\"muted\">" (if (nil? v) "—" (esc v)) "</span>")))

(defn- codes
  "Render a SEQUENCE of keywords in the order the code produced it --
  used for `:basis`, whose order is the governor's own evaluation
  order."
  [coll]
  (str/join " " (map code coll)))

(defn- kw-codes
  "Render a SET of keywords/strings. Sorted, because a set has no order
  and an unsorted render would make the output non-deterministic."
  [coll]
  (str/join " " (map code (sort-by str coll))))

(defn- tr [& cells] (str "<tr>" (apply str (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead><tbody>\n"
       (str/join "\n" rows)
       "\n</tbody></table>"))

(defn- card [title note body]
  (str "<section class=\"card\"><h2>" (esc title) "</h2>"
       (when note (str "<p class=\"muted\">" note "</p>"))
       body "</section>"))

;; ----------------------------- sections -----------------------------

(defn- ledger-of [db] (vec (store/ledger db)))

(defn- holds [db]
  (filterv #(= :governor-hold (:t %)) (ledger-of db)))

(defn- summary-section [db runs]
  (let [led (ledger-of db)
        n (fn [t] (count (filter #(= t (:t %)) led)))]
    (card "Run summary"
          (str "Every number below is a count over the actor's own append-only ledger "
               "after driving " (count runs) " requests through "
               (code "railfreight.operation/build") ".")
          (table ["Measure" "Count"]
                 [(tr "requests driven" (str "<span class=\"num\">" (count runs) "</span>"))
                  (tr "ledger facts" (str "<span class=\"num\">" (count led) "</span>"))
                  (tr "commits" (str "<span class=\"num ok\">" (n :committed) "</span>"))
                  (tr "governor HARD holds"
                      (str "<span class=\"num critical\">" (n :governor-hold) "</span>"))
                  (tr "human rejections"
                      (str "<span class=\"num critical\">" (n :approval-rejected) "</span>"))
                  (tr "escalations offered to a human"
                      (str "<span class=\"num warn\">"
                           (count (filter :paused? runs)) "</span>"))
                  (tr "service-schedule drafts minted"
                      (str "<span class=\"num\">" (count (store/schedule-history db)) "</span>"))
                  (tr "maintenance-coordination drafts minted"
                      (str "<span class=\"num\">" (count (store/maintenance-history db)) "</span>"))]))))

(defn- verdict-cell [{:keys [verdict]}]
  (cond
    (nil? verdict) "<span class=\"muted\">—</span>"
    (:hard? verdict)
    (str "<span class=\"critical\">HARD</span> "
         (codes (map :rule (:violations verdict))))
    (:escalate? verdict)
    (str "<span class=\"warn\">escalate</span>"
         (when (:high-stakes? verdict) " <span class=\"muted\">high-stakes</span>"))
    :else (str "<span class=\"ok\">clean</span> <span class=\"muted\">conf "
               (esc (:confidence verdict)) "</span>")))

(defn- human-cell [{:keys [approval human paused?]}]
  (cond
    (= :approved human) "<span class=\"ok\">approved</span>"
    (= :rejected human) "<span class=\"critical\">rejected</span>"
    (and approval (not paused?))
    "<span class=\"muted\">never offered (no interrupt)</span>"
    :else "<span class=\"muted\">—</span>"))

(defn- disposition-cell [{:keys [disposition]}]
  (case disposition
    :commit "<span class=\"ok\">commit</span>"
    :hold "<span class=\"critical\">hold</span>"
    :escalate "<span class=\"warn\">escalate</span>"
    (str "<span class=\"muted\">" (fmt disposition) "</span>")))

(defn- timeline-section [runs]
  (card "Request timeline"
        (str "One row = one <code>langgraph.graph/run*</code> over the compiled actor. "
             "The governor column is the verdict map the governor itself returned; the human "
             "column is the decision handed back to the graph while it was paused at "
             (code ":request-approval")
             ". The rightmost column is this page's only hand-written domain prose — a static "
             "description of the fixed op-gate contract each request exercises, not telemetry.")
        (table ["Thread" "Op" "Subject" "Governor" "Human" "Final" "What this exercises"]
               (for [{:keys [tid request escalation exercises] :as r} runs]
                 (tr (code tid)
                     (code (:op request))
                     (code (:subject request))
                     (verdict-cell r)
                     (human-cell r)
                     (str (disposition-cell r)
                          (when-let [reason (:reason escalation)]
                            (str " <span class=\"muted\">after escalation "
                                 (code reason) "</span>")))
                     (str "<span class=\"muted\">" (esc exercises) "</span>"))))))

(defn- holds-section [db]
  (let [hs (holds db)]
    (card "Governor HARD holds"
          (str "Each row is one <code>:violations</code> entry of a <code>:governor-hold</code> "
               "fact on the append-only ledger. The rule name and the detail text are the "
               "governor's own output — this page holds no rule text of its own. A HARD hold "
               "is un-overridable: it never reaches a human at all.")
          (table ["Rule" "Op" "Subject" "Confidence" "Governor's own detail"]
                 (for [h hs
                       v (:violations h)]
                   (tr (str "<span class=\"critical\">" (esc (:rule v)) "</span>")
                       (code (:op h))
                       (code (:subject h))
                       (str "<span class=\"num\">" (fmt (:confidence h)) "</span>")
                       (esc (:detail v))))))))

(defn- rejections-section [db]
  (let [rs (filterv #(= :approval-rejected (:t %)) (ledger-of db))]
    (when (seq rs)
      (card "Human rejections"
            (str "A governor-clean proposal a person declined. Written to the ledger by the same "
                 (code ":hold") " node, but with basis " (code ":approver-rejected") " — not a "
                 "compliance violation.")
            (table ["Op" "Subject" "Basis" "Confidence"]
                   (for [r rs]
                     (tr (code (:op r)) (code (:subject r))
                         (codes (:basis r))
                         (str "<span class=\"num\">" (fmt (:confidence r)) "</span>"))))))))

(defn- phase-section []
  (let [ph phase/default-phase
        {:keys [label writes auto]} (get phase/phases ph)]
    (card (str "Rollout phase gate — phase " ph " (" label ")")
          (str "Derived from " (code "railfreight.phase/phases") ". A governor HOLD always stays a "
               "HOLD; an op that may write but is not auto-eligible escalates to a human even when "
               "the governor is clean.")
          (table ["Op" "May write in this phase" "May auto-commit when governor-clean"]
                 (for [o (sort-by str governor/allowed-ops)]
                   (tr (code o)
                       (if (contains? writes o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"critical\">no — HOLD (:phase-disabled)</span>")
                       (if (contains? auto o)
                         "<span class=\"ok\">yes</span>"
                         "<span class=\"warn\">no — always human approval</span>")))))))

(defn- governor-section []
  (card "Governor configuration"
        (str "Read straight off the public vars of " (code "railfreight.governor") " and "
             (code "railfreight.facts") ".")
        (table ["Setting" "Value"]
               [(tr "confidence floor" (code governor/confidence-floor))
                (tr "allowed ops" (kw-codes governor/allowed-ops))
                (tr "allowed proposal actions" (kw-codes governor/allowed-actions))
                (tr "always-human stakes" (kw-codes governor/high-stakes))
                (tr "recognized inspection results" (kw-codes facts/inspection-results))
                (tr "citation policy" (code (:policy facts/citation-policy)))
                (tr "scope-exclusion action phrases"
                    (str/join "<br>" (map #(str "<code>" (esc %) "</code>")
                                          (sort governor/scope-exclusion-actions))))])))

(defn- last-fact-for [led subject]
  (last (filter #(= subject (:subject %)) led)))

(defn- subject-status [led subject]
  (let [f (last-fact-for led subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"critical\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> " (codes (:basis f)))
      :else (str "<span class=\"muted\">" (esc (:t f)) "</span>"))))

(defn- consists-section [db]
  (let [led (ledger-of db)]
    (card "Consists under coordination"
          (str "Read back from " (code "railfreight.store/all-consists") " AFTER the run — this "
               "is the SSoT as the commits above left it, not the seed. A field the record does "
               "not carry shows as —. " (code ":spec-basis") " / " (code ":legal-basis") " are "
               "OPERATOR-SUPPLIED opaque strings: this actor asserts no regulatory fact of its "
               "own (docs/adr/0002).")
          (table ["Consist" "Carrier" "Origin" "Destination" "hazmat?" "registered?"
                  "hazmat scope confirmed?" "scheduled?" "Schedule no."
                  "maintenance open?" "Maintenance no." "Last inspection"
                  "Reconciliation evidence" "Amount" "safety concern?" "Ledger status"]
                 (for [c (store/all-consists db)]
                   (tr (code (:id c)) (fmt (:carrier c)) (fmt (:origin c)) (fmt (:destination c))
                       (flag (:hazmat? c)) (flag (:registered? c))
                       (flag (:hazmat-handling-confirmed? c))
                       (flag (:scheduled? c)) (fmt (:schedule-number c))
                       (flag (:maintenance-open? c)) (fmt (:maintenance-number c))
                       (fmt (:last-inspection-result c))
                       (fmt (:last-reconciliation-evidence c))
                       (str "<span class=\"num\">" (fmt (:last-reconciliation-amount c)) "</span>")
                       (if (and (true? (:safety-concern-raised? c))
                                (not (true? (:safety-concern-resolved? c))))
                         (str "<span class=\"critical\">open</span> "
                              "<span class=\"muted\">" (fmt (:safety-concern-note c)) "</span>")
                         "<span class=\"muted\">none</span>")
                       (subject-status led (:id c))))))))

(defn- cargo-section [db]
  (card "Cargo manifests"
        (str "The seeded consist/cargo-manifest lines, read back from "
             (code "railfreight.store/all-consists") ". This actor logs and coordinates them; "
             "it never clears a train for departure and never decides a hazmat-handling protocol.")
        (table ["Consist" "hazmat?" "Cargo manifest lines"]
               (for [c (store/all-consists db)]
                 (tr (code (:id c)) (flag (:hazmat? c))
                     (if (seq (:cargo-manifest c))
                       (str/join "<br>" (map esc (:cargo-manifest c)))
                       "<span class=\"muted\">—</span>"))))))

(defn- schedules-section [db]
  (let [hist (store/schedule-history db)]
    (card "Service-schedule drafts"
          (str "Committed drafts from " (code "railfreight.store/schedule-history")
               ". The schedule number is minted by "
               (code "railfreight.registry/register-service-schedule") " at commit time. Each is "
               "a scheduling COORDINATION note — nothing here dispatches a train.")
          (if (seq hist)
            (table ["Record id" "Kind" "Consist" "Jurisdiction" "immutable"]
                   (for [r hist]
                     (tr (code (get r "record_id")) (fmt (get r "kind"))
                         (code (get r "consist_id")) (fmt (get r "jurisdiction"))
                         (flag (get r "immutable")))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- maintenance-section [db]
  (let [hist (store/maintenance-history db)]
    (card "Maintenance-coordination drafts"
          (str "Committed drafts from " (code "railfreight.store/maintenance-history")
               ", minted by " (code "railfreight.registry/register-maintenance-coordination")
               ". A coordination note, never a real maintenance-release decision.")
          (if (seq hist)
            (table ["Record id" "Kind" "Consist" "Jurisdiction" "immutable"]
                   (for [r hist]
                     (tr (code (get r "record_id")) (fmt (get r "kind"))
                         (code (get r "consist_id")) (fmt (get r "jurisdiction"))
                         (flag (get r "immutable")))))
            "<p class=\"muted\">none committed in this run</p>"))))

(defn- ledger-section [db]
  (card "Audit ledger (append-only)"
        (str "The full ledger, in append order, exactly as "
             (code "railfreight.store/ledger") " returns it. Note that "
             (code ":approval-requested") " / " (code ":approval-granted")
             " are emitted to the graph's in-memory " (code ":audit")
             " channel only — " (code "railfreight.operation")
             " never appends them to the store ledger, so an approved request is visible here "
             "as the " (code ":committed") " fact it produced.")
        (table ["#" "Fact" "Op" "Subject" "Actor" "Disposition" "Basis"]
               (map-indexed
                (fn [i f]
                  (tr (str "<span class=\"num\">" (esc (inc i)) "</span>")
                      (let [cls (case (:t f)
                                  :committed "ok"
                                  :governor-hold "critical"
                                  :approval-rejected "critical"
                                  "muted")]
                        (str "<span class=\"" cls "\">" (esc (:t f)) "</span>"))
                      (code (:op f)) (code (:subject f)) (fmt (:actor f))
                      (fmt (:disposition f)) (codes (:basis f))))
                (ledger-of db)))))

;; ----------------------------- page -----------------------------

(defn render
  "The whole page, from the post-run store and the run log. Every row,
  number and status below is derived from real store/ledger output."
  [{:keys [db runs]}]
  (str "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
       "<meta name=\"color-scheme\" content=\"light\">"
       "<title>Operator console — cloud-itonami-isic-4912 (railfreight)</title>"
       "<style>" (jp-go-dds.skin/dds+skin) "</style></head>\n<body>\n"
       "<header class=\"bar\">"
       "<h1>Community freight rail transport — operator console</h1>"
       "</header>\n"
       "<p class=\"subtitle\"><span class=\"badge\">ISIC 4912</span> "
       "<span class=\"badge\">railfreight</span> "
       "governor <code>rail-freight-governor</code> · actor "
       (esc (:actor-id coordinator)) " · role " (esc (:actor-role coordinator))
       " · phase " (esc (:phase coordinator)) "</p>\n"
       "<main>\n"
       (str/join "\n"
                 (remove nil?
                         [(summary-section db runs)
                          (timeline-section runs)
                          (holds-section db)
                          (rejections-section db)
                          (phase-section)
                          (governor-section)
                          (consists-section db)
                          (cargo-section db)
                          (schedules-section db)
                          (maintenance-section db)
                          (ledger-section db)]))
       "\n</main>\n<footer>"
       "Generated at build time by <code>railfreight.render-html</code> "
       "(<code>clojure -M:dev:render-html</code>) by driving the real "
       "<code>railfreight.operation</code> actor graph over the real "
       "<code>railfreight.store</code> seed. Deterministic — no clock, no randomness, no network. "
       "No usage, revenue or performance metric is claimed anywhere on this page."
       "</footer>\n</body>\n</html>\n"))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        hs (holds db)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count (store/ledger db))
                       :requests (count runs)})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count (store/ledger db)) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count runs) " requests)"))))
