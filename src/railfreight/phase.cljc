(ns railfreight.phase
  "Phase 0->3 staged rollout for the community-freight-rail-operations-
  coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-logging   -- shipment/route record logging
                                    allowed, every write needs human
                                    approval.
    Phase 2  assisted-coord     -- adds service-scheduling / track-
                                    safety-concern flagging / maintenance
                                    coordination writes, still approval.
    Phase 3  supervised auto    -- governor-clean, high-confidence
                                    `:log-shipment-record` (no
                                    operational/safety weight, pure
                                    data logging) may auto-commit.
                                    `:schedule-service-operation`/
                                    `:flag-track-safety-concern`/
                                    `:coordinate-maintenance` NEVER
                                    auto-commit, at any phase.

  `:schedule-service-operation`/`:flag-track-safety-concern`/
  `:coordinate-maintenance` are deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. This actor coordinates freight-rail
  operations; it is never the dispatcher, the track-safety authority,
  or rolling-stock/locomotive control (see `railfreight.governor` ns
  docstring `SCOPE`), and flagging a track-safety concern in
  particular must ALWAYS reach a human -- `railfreight.governor`'s own
  `high-stakes` gate enforces the same invariant independently for all
  three ops. Two layers, not one, agree on this. Like every prior
  sibling's own real-actuation phase-3 `:auto` set, this domain has
  only ONE member (`:log-shipment-record`) -- no separate no-
  operational-risk 'file' lifecycle distinct from the consist itself.")

(def read-ops  #{})
(def write-ops #{:log-shipment-record :schedule-service-operation
                  :flag-track-safety-concern :coordinate-maintenance})

;; NOTE the invariant: `:schedule-service-operation`/`:flag-track-
;; safety-concern`/`:coordinate-maintenance` are members of `write-ops`
;; (governor-gated like any write) but are NEVER members of any
;; phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                     :auto #{}}
   1 {:label "assisted-logging"    :writes #{:log-shipment-record}                 :auto #{}}
   2 {:label "assisted-coordination" :writes write-ops                             :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-shipment-record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-service-operation`/`:flag-track-safety-concern`/
    `:coordinate-maintenance` are never auto-eligible at any phase, so
    they always escalate once the governor clears them (or hold if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Rail Freight Governor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
