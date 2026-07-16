(ns railfreight.governor
  "Rail Freight Governor -- the independent compliance layer that earns
  the RailFreight-LLM the right to commit. The LLM has no notion of
  jurisdictional rail-carrier-safety/hazmat-by-rail law, whether a
  consist's own shipment/route record has actually been independently
  verified/registered, whether a hazmat-carrying consist's handling
  protocol has actually been confirmed, whether an open track-safety
  concern has actually been resolved, or when an act stops being a
  draft coordination note and becomes something this actor must NEVER
  be allowed to represent (a real track/dispatch-safety-authority
  decision), so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  ================================================================
  SCOPE, stated as a structural invariant, not a policy preference
  ================================================================
  This actor is a freight-rail OPERATIONS COORDINATION actor. It is
  NOT the dispatcher, NOT the track-safety authority, and NOT rolling-
  stock/locomotive control. Every proposal it can ever produce has a
  literal `:effect :propose` (never an actuation) and an `:action`
  drawn from a FOUR-MEMBER closed allowlist
  (`railfreight.governor/allowed-actions`) that maps 1:1 to the FOUR
  ops in `railfreight.governor/allowed-ops`
  (`:log-shipment-record`/`:schedule-service-operation`/`:flag-track-
  safety-concern`/`:coordinate-maintenance`). A proposal to directly
  finalize a track/dispatch-safety-authority decision (clearing a
  train for departure after a reported track fault, overriding a
  hazmat-handling protocol, etc.) is not merely disallowed by policy --
  it CANNOT be represented in this closed allowlist at all, so
  `action-allowlist-violations` hard-blocks it structurally even if an
  advisor somehow proposed one. `scope-exclusion-violations` is a
  SECOND, independent layer: it text-scans the proposal's own
  rationale/summary for a small set of finalization/execution ACTION
  phrases (never a bare noun -- see that check's own docstring for
  why) so a proposal that merely NAMES a forbidden finalization act in
  its prose (without setting a matching `:action`) is caught too. Two
  independent layers, matching the two-layer discipline every prior
  sibling actor's own real-actuation gate uses (see `high-stakes`
  below and `railfreight.phase`).

  Ten checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / high-stakes), and the human may
  approve -- but see `railfreight.phase`: `:schedule-service-
  operation`/`:flag-track-safety-concern`/`:coordinate-maintenance`
  are NEVER in any phase's `:auto` set either. Two independent layers
  agree that these three ops always need a human.

    1.  Effect-not-:propose      -- structural: every proposal this
                                     actor emits must carry the literal
                                     `:effect :propose` -- it never
                                     actuates.
    2.  Op not allowlisted       -- the request's `:op` must be one of
                                     the four closed-allowlist ops.
    3.  Action not allowlisted   -- the proposal's `:action` must be
                                     one of the four closed-allowlist
                                     actions -- structurally excludes
                                     any track/dispatch-safety-
                                     authority-finalizing action (see
                                     SCOPE above).
    4.  Scope-exclusion          -- the proposal's own rationale/
                                     summary text must not name a
                                     finalization/execution ACTION this
                                     actor must never perform (SECOND,
                                     independent layer to #3).
    5.  Spec-basis               -- for `:log-shipment-record`, did the
                                     advisor cite an OFFICIAL source
                                     (`railfreight.facts`), or invent
                                     one?
    6.  Record not verified      -- for `:schedule-service-operation`/
                                     `:flag-track-safety-concern`/
                                     `:coordinate-maintenance`, has the
                                     subject consist's own shipment/
                                     route record actually been
                                     independently verified/registered
                                     (via a committed `:log-shipment-
                                     record`)? The HARD invariant this
                                     vertical's own README states:
                                     'a shipment/route record must be
                                     independently verified/registered
                                     before any action' -- applied to
                                     ALL THREE non-registration ops,
                                     not only the highest-stakes one.
    7.  Hazmat-handling protocol
        unconfirmed               -- for `:schedule-service-
                                     operation`, INDEPENDENTLY verify
                                     that if the consist's cargo-
                                     manifest carries hazmat, its own
                                     `:hazmat-handling-confirmed?` fact
                                     is true. Never trust the advisor's
                                     self-reported confidence alone.
    8.  Open safety concern      -- for `:schedule-service-operation`/
                                     `:coordinate-maintenance`, an
                                     unresolved track-safety concern on
                                     file for the subject consist
                                     (`:safety-concern-raised? true`
                                     AND `:safety-concern-resolved?
                                     false`) is a HARD, un-overridable
                                     hold.
    9.  Already scheduled        -- for `:schedule-service-operation`,
                                     refuses to double-schedule the
                                     SAME consist, off a dedicated
                                     `:scheduled?` fact (never a
                                     `:status` value).
    10. Already coordinating     -- for `:coordinate-maintenance`,
                                     refuses to open a SECOND
                                     maintenance-coordination request
                                     while one is already open, off a
                                     dedicated `:maintenance-open?`
                                     fact (never a `:status` value)."
  (:require [clojure.string :as str]
            [railfreight.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed op allowlist -- see this ns docstring `SCOPE`. Nothing
  outside this four-member set is a valid `:op`, structurally."
  #{:log-shipment-record :schedule-service-operation
    :flag-track-safety-concern :coordinate-maintenance})

(def allowed-actions
  "The closed `:action` allowlist -- 1:1 with `allowed-ops`. A
  track/dispatch-safety-authority-finalizing action (e.g. clearing a
  train for departure, overriding a hazmat-handling protocol) is not a
  member of this set and can therefore never be represented as a
  proposal `:action` this governor would let through."
  #{:consist/log :consist/mark-scheduled
    :consist/flag-safety-concern :consist/mark-maintenance-coordinated})

(def high-stakes
  "Stakes grave enough to always require a human, even when the
  governor is otherwise clean. Scheduling a real service operation,
  flagging a track-safety concern, and coordinating maintenance are
  the three ops this actor never auto-commits, at any phase --
  `:log-shipment-record` (pure data logging, no operational/safety
  weight) is the ONLY auto-eligible op, matching every sibling
  actor's own 'only ONE member' phase-3 `:auto` set discipline."
  #{:coordination/schedule-service :coordination/flag-track-safety-concern
    :coordination/coordinate-maintenance})

;; ------------------------- scope-exclusion terms -------------------------

(def scope-exclusion-actions
  "Finalization/execution ACTION phrases (never a bare noun) naming a
  track/dispatch-safety-authority decision this actor must NEVER
  finalize. This fleet has independently rediscovered, in multiple
  sibling repos, the SAME bug class: a scope-exclusion term list
  phrased as a bare noun (e.g. \"safety\", \"dispatch\", \"clearance\")
  accidentally matches inside this actor's OWN default mock-advisor's
  disclaimer text for a legitimate, allowed proposal (every disclaimer
  in `railfreight.railfreightllm` says things like 'this proposal does
  not clear a train for departure' -- a bare noun like \"clear\" or
  \"departure\" would match that sentence and self-block the happy
  path). Phrasing each term as the FULL finalization-action phrase
  avoids this: a disclaimer that merely DENIES having the authority
  ('...does not clear a train for departure...') never contains the
  literal action phrase itself ('clear THIS train for departure') as
  a contiguous substring. `railfreight.governor-self-trip-test`
  exercises every default proposal this actor's advisor can produce
  and asserts NONE of them trip this check -- that test, not careful
  wording alone, is the real guarantee."
  ["clear this consist for departure"
   "clear the train for departure"
   "override the hazmat-handling protocol"
   "finalize the track-safety override"
   "authorize departure after a reported track fault"
   "grant final dispatch clearance"
   "issue final dispatch clearance"
   "approve departure over the reported track fault"])

;; ----------------------------- checks -----------------------------

(defn- effect-not-propose-violations
  "Every proposal this actor emits must carry the literal `:effect
  :propose` -- it never actuates. Evaluated UNCONDITIONALLY, on every
  op."
  [_request proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "提案の:effectが:proposeではありません(" (:effect proposal) ")")}]))

(defn- op-allowlist-violations
  "The request's `:op` must be one of the four closed-allowlist ops.
  Evaluated UNCONDITIONALLY."
  [{:keys [op]} _proposal]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowlisted
      :detail (str op " は許可された操作(op)一覧に含まれません")}]))

(defn- action-allowlist-violations
  "The proposal's `:action` must be one of the four closed-allowlist
  actions -- structurally excludes any track/dispatch-safety-
  authority-finalizing action. Evaluated UNCONDITIONALLY."
  [_request proposal]
  (when-not (contains? allowed-actions (:action proposal))
    [{:rule :action-not-allowlisted
      :detail (str (:action proposal) " は許可されたaction一覧に含まれません -- 軌道/運行安全権限の確定操作は決して許可されない")}]))

(defn- scope-exclusion-violations
  "The proposal's own rationale/summary text must not name a
  finalization/execution ACTION this actor must never perform -- see
  `scope-exclusion-actions` docstring for why these are phrased as
  full action phrases, never bare nouns. Evaluated UNCONDITIONALLY."
  [_request proposal]
  (let [text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))]
    (when (some #(str/includes? text (str/lower-case %)) scope-exclusion-actions)
      [{:rule :scope-exclusion-violation
        :detail "提案文言が軌道/運行安全権限の確定行為に該当する表現を含みます -- 恒久的にブロック"}])))

(defn- spec-basis-violations
  "A `:log-shipment-record` proposal with no spec-basis citation is a
  HARD violation -- never invent a jurisdiction's consist-registration/
  carrier-safety/hazmat-by-rail requirements."
  [{:keys [op]} proposal]
  (when (= op :log-shipment-record)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- record-not-verified-violations
  "For `:schedule-service-operation`/`:flag-track-safety-concern`/
  `:coordinate-maintenance`, the subject consist's own shipment/route
  record must have ALREADY been independently verified/registered (a
  committed `:log-shipment-record`) -- the HARD invariant this
  vertical's own README states applies before ANY of these three ops,
  not only the highest-stakes one."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-service-operation :flag-track-safety-concern :coordinate-maintenance} op)
    (let [c (store/consist st subject)]
      (when-not (true? (:registered? c))
        [{:rule :record-not-verified
          :detail (str subject " の編成/貨物マニフェスト/経路記録が未登録・未検証の状態での提案")}]))))

(defn- hazmat-handling-protocol-violations
  "For `:schedule-service-operation`, INDEPENDENTLY verify that if the
  consist's cargo-manifest carries hazmat, its own `:hazmat-handling-
  confirmed?` fact is true. Evaluated UNCONDITIONALLY (every schedule
  proposal for a hazmat consist needs a confirmed handling protocol)."
  [{:keys [op subject]} st]
  (when (= op :schedule-service-operation)
    (let [c (store/consist st subject)]
      (when (and (:hazmat? c) (not (true? (:hazmat-handling-confirmed? c))))
        [{:rule :hazmat-handling-protocol-unconfirmed
          :detail (str subject " は危険物貨物を含むが、取扱手順の確認が未完了 -- 運行計画提案は進められない")}]))))

(defn- open-safety-concern-violations
  "An unresolved track-safety concern -- already on file for the
  subject consist (`:safety-concern-raised? true` AND `:safety-
  concern-resolved? false`) -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY across `:schedule-service-operation` and
  `:coordinate-maintenance`."
  [{:keys [op subject]} st]
  (when (contains? #{:schedule-service-operation :coordinate-maintenance} op)
    (let [c (store/consist st subject)]
      (when (and (true? (:safety-concern-raised? c)) (not (true? (:safety-concern-resolved? c))))
        [{:rule :open-safety-concern
          :detail (str subject " は未解決の軌道安全懸念がある -- 運行計画/保守調整提案は進められない")}]))))

(defn- already-scheduled-violations
  "For `:schedule-service-operation`, refuses to double-schedule the
  SAME consist, off a dedicated `:scheduled?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-service-operation)
    (when (store/consist-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既に運行計画が調整済み")}])))

(defn- already-coordinating-violations
  "For `:coordinate-maintenance`, refuses to open a SECOND maintenance-
  coordination request while one is already open, off a dedicated
  `:maintenance-open?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :coordinate-maintenance)
    (when (store/consist-maintenance-already-open? st subject)
      [{:rule :already-coordinating
        :detail (str subject " は既に保守調整が進行中")}])))

(defn check
  "Censors a RailFreight-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (effect-not-propose-violations request proposal)
                           (op-allowlist-violations request proposal)
                           (action-allowlist-violations request proposal)
                           (scope-exclusion-violations request proposal)
                           (spec-basis-violations request proposal)
                           (record-not-verified-violations request st)
                           (hazmat-handling-protocol-violations request st)
                           (open-safety-concern-violations request st)
                           (already-scheduled-violations request st)
                           (already-coordinating-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
