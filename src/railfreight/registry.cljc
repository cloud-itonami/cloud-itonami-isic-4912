(ns railfreight.registry
  "Pure-function service-schedule + maintenance-coordination record
  construction -- an append-only rail-operations coordination draft
  book-of-record.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a service-schedule or a maintenance-
  coordination record -- every railway/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `railfreight.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real dispatch/interlocking/maintenance-management
  system. It builds the COORDINATION RECORD this actor would keep, not
  a real train-dispatch clearance or a real maintenance-release
  decision -- both of those remain a certified dispatcher/track-safety
  authority's own act, entirely outside this actor's closed op
  allowlist (see `railfreight.governor` ns docstring `SCOPE`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the certified operator's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-service-schedule
  "Validate + construct the SERVICE-SCHEDULE registration DRAFT -- a
  scheduling COORDINATION note, never a real dispatch clearance. Pure
  function -- does not touch any real dispatch/interlocking system; it
  builds the RECORD this actor would keep. `railfreight.governor`
  independently re-verifies the consist's own registration/hazmat-
  handling/safety-concern ground truth, and blocks a double-schedule
  of the same consist, before this is ever allowed to commit."
  [consist-id jurisdiction sequence]
  (when-not (and consist-id (not= consist-id ""))
    (throw (ex-info "service-schedule: consist_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "service-schedule: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "service-schedule: sequence must be >= 0" {})))
  (let [schedule-number (str (str/upper-case jurisdiction) "-SCH-" (zero-pad sequence 6))
        record {"record_id" schedule-number
                "kind" "service-schedule-draft"
                "consist_id" consist-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "schedule_number" schedule-number
     "certificate" (unsigned-certificate "ServiceSchedule" schedule-number schedule-number)}))

(defn register-maintenance-coordination
  "Validate + construct the MAINTENANCE-COORDINATION registration
  DRAFT -- a coordination note, never a real maintenance-release
  decision. Pure function -- does not touch any real maintenance-
  management system; it builds the RECORD this actor would keep.
  `railfreight.governor` independently re-verifies the consist's own
  registration/safety-concern ground truth, and blocks opening a
  second coordination request while one is already open, before this
  is ever allowed to commit."
  [consist-id jurisdiction sequence]
  (when-not (and consist-id (not= consist-id ""))
    (throw (ex-info "maintenance-coordination: consist_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "maintenance-coordination: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "maintenance-coordination: sequence must be >= 0" {})))
  (let [maintenance-number (str (str/upper-case jurisdiction) "-MNT-" (zero-pad sequence 6))
        record {"record_id" maintenance-number
                "kind" "maintenance-coordination-draft"
                "consist_id" consist-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "maintenance_number" maintenance-number
     "certificate" (unsigned-certificate "MaintenanceCoordination" maintenance-number maintenance-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
