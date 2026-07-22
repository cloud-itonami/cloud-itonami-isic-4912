(ns railfreight.railfreightllm
  "RailFreight-LLM client -- the *contained intelligence node* for the
  community-freight-rail-operations-coordination actor.

  It normalizes/registers a consist's shipment/route record (cargo-
  manifest, routing), drafts a service-schedule COORDINATION proposal,
  drafts a track-safety-concern flag, drafts a maintenance-coordination
  proposal, drafts a hazmat-transport-scope confirmation, drafts a
  track/rolling-stock inspection-record log, drafts a maintenance-
  release proposal, and drafts a booking/reconciliation record. CRITICAL:
  it is a smart-but-untrusted advisor, and it is scoped to COORDINATION
  only -- it has NO track/dispatch-safety authority and NO rolling-
  stock/locomotive control (see `railfreight.governor` ns docstring
  `SCOPE`). It returns a *proposal* (`:effect` is ALWAYS the literal
  `:propose`), never a committed record and never a real dispatch
  clearance, hazmat-scope determination, inspection sign-off, or
  maintenance release. Every output is censored downstream by
  `railfreight.governor` before anything touches the SSoT, and the
  five highest-stakes ops NEVER auto-commit at any phase -- see README
  `Actuation`.

  CORRECTION (docs/adr/0002-remove-fabricated-jurisdiction-catalog.md):
  `propose-log-shipment-record` used to look up a jurisdiction's
  'official' spec-basis citation from a hardcoded `railfreight.facts`
  catalog asserting real regulator names and legal citations. It no
  longer does -- `:spec-basis`/`:legal-basis` are now OPERATOR-SUPPLIED
  request keys (opaque strings this actor neither invents nor
  validates against any 'official' list); an absent citation simply
  means the proposal has none, and the governor's own `spec-basis-
  violations` HOLDs it exactly as before.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion
                                 ; gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     :propose       ; ALWAYS this literal value -- this actor
                                 ; never actuates (railfreight.governor's
                                 ; own `effect-not-propose-violations`
                                 ; hard-enforces this)
     :action     kw             ; the SSoT mutation this proposal, if
                                 ; approved, would apply -- one of the
                                 ; closed `railfreight.governor/allowed-
                                 ; actions`
     :value      map            ; payload for :action
     :stake      kw|nil         ; :coordination/schedule-service |
                                 ; :coordination/flag-track-safety-concern |
                                 ; :coordination/coordinate-maintenance |
                                 ; :coordination/register-hazmat-transport-scope |
                                 ; :coordination/release-rolling-stock-from-maintenance |
                                 ; nil
     :confidence 0..1}

  IMPORTANT re: the fleet-wide self-tripping-bug class (see
  `railfreight.governor/scope-exclusion-actions` docstring): every
  disclaimer below DENIES having track/dispatch-safety authority using
  DIFFERENT wording than the full finalization-action phrases that
  list names (e.g. it says 'does not clear a train for departure', not
  the literal phrase 'clear THIS consist for departure' the governor
  scans for) -- and `railfreight.governor-self-trip-test` proves this
  holds for every proposal this advisor's default `infer` can produce,
  rather than relying on wording care alone."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [railfreight.store :as store]
            [langchain.model :as model]))

(defn- propose-log-shipment-record
  "Draft the consist/cargo-manifest/routing record UPDATE + pass
  through whatever operator-supplied spec-basis/legal-basis citation
  the request itself carries. The LLM only normalizes/validates the
  patch; it does not invent the patch fields, the jurisdiction, or a
  spec-basis citation the operator did not themselves submit."
  [db {:keys [subject patch spec-basis legal-basis]}]
  (let [existing (store/consist db subject)
        base (merge existing patch)
        jurisdiction (:jurisdiction base)]
    (if (nil? spec-basis)
      {:summary    (str subject " の記録更新: 運用者提出のspec-basis引用がありません")
       :rationale  "運用者(operator)から提出されたspec-basis引用が無い提案 -- このactor自身は法域要件を推測で作らない。この提案は記録の登録・検証を完了させない。"
       :cites      []
       :effect     :propose
       :action     :consist/log
       :value      {:patch (assoc patch :jurisdiction jurisdiction) :spec-basis nil :legal-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str subject " の編成/貨物マニフェスト/経路記録を運用者提出の引用で更新")
       :rationale  (str "運用者提出の引用: " spec-basis (when legal-basis (str " / " legal-basis))
                        " -- このactor自身は法域要件の内容を検証・保証しない。本提案は記録の登録のみで、"
                        "列車の運行や保守の可否判断は行わない。")
       :cites      [spec-basis]
       :effect     :propose
       :action     :consist/log
       :value      {:patch patch :spec-basis spec-basis :legal-basis legal-basis}
       :stake      nil
       :confidence 0.95})))

(defn- propose-schedule-service-operation
  "Draft a SERVICE-SCHEDULE coordination proposal -- a scheduling
  DRAFT, never a real dispatch clearance. ALWAYS `:stake
  :coordination/schedule-service` -- this actor has no track/dispatch-
  safety authority. See README `Actuation`: no phase ever adds this op
  to a phase's `:auto` set (`railfreight.phase`); the governor also
  always escalates on `:coordination/schedule-service`. Two
  independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [c (store/consist db subject)
        registered? (and c (:registered? c))
        hazmat-ok? (and c (or (not (:hazmat? c)) (:hazmat-handling-confirmed? c)))
        concern-clear? (and c (or (not (:safety-concern-raised? c)) (:safety-concern-resolved? c)))]
    {:summary    (str subject " 向け運行計画スケジュール調整案"
                      (when c (str " (carrier=" (:carrier c) ")")))
     :rationale  (if c
                   (str "registered?=" registered? " hazmat-handling-confirmed?=" hazmat-ok?
                        " safety-concern-clear?=" concern-clear?
                        " -- this is a scheduling coordination draft only;"
                        " it does not clear a train for departure and does not"
                        " decide any hazmat-handling protocol -- those remain a"
                        " certified dispatcher/track-safety authority's own act.")
                   "consistが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :consist/mark-scheduled
     :value      {:consist-id subject}
     :stake      :coordination/schedule-service
     :confidence (if (and registered? hazmat-ok? concern-clear?) 0.9 0.3)}))

(defn- propose-flag-track-safety-concern
  "Draft a TRACK-SAFETY-CONCERN flag -- surfaces a track-fault/hazmat-
  handling/derailment-risk concern for human review. ALWAYS `:stake
  :coordination/flag-track-safety-concern` -- flagging a concern must
  ALWAYS reach a human sign-off; this actor never resolves or
  dismisses a concern itself. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`railfreight.phase`); the
  governor also always escalates on `:coordination/flag-track-safety-
  concern`. Two independent layers agree, deliberately."
  [db {:keys [subject note]}]
  (let [c (store/consist db subject)]
    {:summary    (str subject " について懸念事項を報告"
                      (when note (str ": " note)))
     :rationale  (str "this proposal only SURFACES a concern for human review;"
                      " it does not resolve, dismiss, or act on the concern itself --"
                      " a certified track-safety authority always makes that call.")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :consist/flag-safety-concern
     :value      {:consist-id subject :note (or note "unspecified track-fault/hazmat-handling/derailment-risk concern")}
     :stake      :coordination/flag-track-safety-concern
     :confidence 0.95}))

(defn- propose-coordinate-maintenance
  "Draft a MAINTENANCE-COORDINATION proposal -- rolling-stock/track
  maintenance coordination, never a real maintenance-release decision.
  ALWAYS `:stake :coordination/coordinate-maintenance` -- this actor
  has no authority to release equipment/track back into service. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`railfreight.phase`); the governor also always escalates on
  `:coordination/coordinate-maintenance`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [c (store/consist db subject)
        registered? (and c (:registered? c))
        concern-clear? (and c (or (not (:safety-concern-raised? c)) (:safety-concern-resolved? c)))
        already-open? (and c (:maintenance-open? c))]
    {:summary    (str subject " 向け保守調整(車両/線路)案")
     :rationale  (if c
                   (str "registered?=" registered? " safety-concern-clear?=" concern-clear?
                        " maintenance-open?=" already-open?
                        " -- this is a maintenance-coordination draft only;"
                        " it does not release any equipment or track back into"
                        " service -- that remains a certified maintenance"
                        " authority's own act.")
                   "consistが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :consist/mark-maintenance-coordinated
     :value      {:consist-id subject}
     :stake      :coordination/coordinate-maintenance
     :confidence (if (and registered? concern-clear? (not already-open?)) 0.9 0.3)}))

(defn- propose-register-hazmat-transport-scope
  "Draft a HAZMAT-TRANSPORT-SCOPE confirmation -- records the
  operator-submitted evidence that a hazmat-handling protocol is on
  file for this consist, never a determination this actor itself
  makes. ALWAYS `:stake :coordination/register-hazmat-transport-
  scope` -- confirming a hazmat-transport scope must ALWAYS reach a
  human sign-off. `:evidence` MUST come from the request (operator-
  submitted); a request with no evidence yields a proposal with none,
  and `railfreight.governor/hazmat-scope-evidence-violations` HOLDs
  it -- this actor never invents evidence to fill the gap."
  [db {:keys [subject evidence]}]
  (let [c (store/consist db subject)]
    {:summary    (str subject " 向け危険物輸送スコープ(hazmat-transport-scope)確認案"
                      (when evidence (str " -- 根拠: " evidence)))
     :rationale  (str "this proposal only records the operator-submitted evidence"
                      " that a hazmat-handling protocol is already on file;"
                      " it does not itself set or change what that protocol requires"
                      " -- determining or amending the protocol remains a certified"
                      " operator/authority's own act.")
     :cites      (if evidence [evidence] [])
     :effect     :propose
     :action     :consist/confirm-hazmat-handling
     :value      {:consist-id subject :evidence evidence}
     :stake      :coordination/register-hazmat-transport-scope
     :confidence (if (and c (:registered? c) evidence (not (str/blank? evidence))) 0.9 0.3)}))

(defn- propose-log-inspection-record
  "Draft a robotics-assisted TRACK/ROLLING-STOCK INSPECTION-RECORD
  log entry -- records an inspection OBSERVATION, never a
  serviceability determination. `:inspection-result` MUST come from
  the request; `railfreight.governor/inspection-record-invalid-
  violations` independently verifies it against the closed
  `railfreight.facts/inspection-results` vocabulary."
  [db {:keys [subject inspection-result]}]
  (let [c (store/consist db subject)]
    {:summary    (str subject " の track/rolling-stock 検査記録: " inspection-result)
     :rationale  "this proposal only logs a robotics-assisted inspection observation;"
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :consist/log-inspection
     :value      {:consist-id subject :inspection-result inspection-result}
     :stake      nil
     :confidence (if (and c (:registered? c)) 0.9 0.3)}))

(defn- propose-release-rolling-stock-from-maintenance
  "Draft a MAINTENANCE-RELEASE proposal -- proposes marking rolling
  stock/track serviceable again after maintenance, contingent on a
  completed inspection record already on file (`railfreight.governor/
  maintenance-release-uninspected-violations` independently verifies
  this -- 'track/rolling-stock cannot be marked serviceable without a
  completed inspection record present'). ALWAYS `:stake
  :coordination/release-rolling-stock-from-maintenance` -- this actor
  has no authority to actually certify serviceability; that remains a
  certified maintenance authority's own act."
  [db {:keys [subject]}]
  (let [c (store/consist db subject)
        registered? (and c (:registered? c))
        maintenance-open? (and c (:maintenance-open? c))
        inspected? (and c (some? (:last-inspection-result c)))
        passed? (and c (= "pass" (:last-inspection-result c)))]
    {:summary    (str subject " 向け保守解放(maintenance-release)提案")
     :rationale  (if c
                   (str "registered?=" registered? " maintenance-open?=" maintenance-open?
                        " inspection-on-file?=" inspected? " last-inspection-result="
                        (:last-inspection-result c)
                        " -- this is a maintenance-release coordination draft only;"
                        " a certified maintenance authority makes the actual"
                        " serviceability determination and puts the equipment back"
                        " into service, not this actor.")
                   "consistが見つかりません")
     :cites      (if c [subject] [])
     :effect     :propose
     :action     :consist/mark-maintenance-released
     :value      {:consist-id subject}
     :stake      :coordination/release-rolling-stock-from-maintenance
     :confidence (if (and registered? maintenance-open? inspected? passed?) 0.9 0.3)}))

(defn- propose-log-reconciliation-record
  "Draft a booking/RECONCILIATION-RECORD log entry -- `:evidence` MUST
  come from the request (operator-submitted); `railfreight.governor/
  reconciliation-evidence-violations` independently verifies it is
  non-blank -- `docs/business-model.md`'s own Trust Control:
  'reconciliation records require verified evidence'."
  [db {:keys [subject evidence amount]}]
  (let [c (store/consist db subject)]
    {:summary    (str subject " の予約/精算(booking/reconciliation)記録"
                      (when amount (str " 金額=" amount)))
     :rationale  (str "this proposal only logs a booking/reconciliation record on"
                      " operator-submitted evidence: " evidence)
     :cites      (if evidence [evidence] [])
     :effect     :propose
     :action     :consist/log-reconciliation
     :value      {:consist-id subject :evidence evidence :amount amount}
     :stake      nil
     :confidence (if (and c (:registered? c) evidence (not (str/blank? evidence))) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-shipment-record                     (propose-log-shipment-record db request)
    :schedule-service-operation               (propose-schedule-service-operation db request)
    :flag-track-safety-concern                (propose-flag-track-safety-concern db request)
    :coordinate-maintenance                   (propose-coordinate-maintenance db request)
    :register-hazmat-transport-scope          (propose-register-hazmat-transport-scope db request)
    :log-inspection-record                    (propose-log-inspection-record db request)
    :release-rolling-stock-from-maintenance   (propose-release-rolling-stock-from-maintenance db request)
    :log-reconciliation-record                (propose-log-reconciliation-record db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :propose :action :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域貨物鉄道事業者の運行コーディネーションエージェントの助言者です。"
       "あなたには線路・運行の安全権限も、車両・機関車の制御権限もありません。"
       "与えられた事実と運用者提出の引用のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に:propose) "
       ":action(:consist/log|:consist/mark-scheduled|:consist/flag-safety-concern|"
       ":consist/mark-maintenance-coordinated|:consist/confirm-hazmat-handling|"
       ":consist/log-inspection|:consist/mark-maintenance-released|:consist/log-reconciliation) "
       ":stake(:coordination/schedule-service か :coordination/flag-track-safety-concern か "
       ":coordination/coordinate-maintenance か :coordination/register-hazmat-transport-scope か "
       ":coordination/release-rolling-stock-from-maintenance か nil) :confidence(0..1)。\n"
       "重要: 法域の規制要件やspec-basis引用、危険物輸送スコープの根拠(evidence)、"
       "精算記録の根拠(evidence)を、運用者(operator)が提出していないのに絶対に創作してはいけません。"
       "危険物取扱手順の確認状況や軌道安全懸念の解消状況、検査結果を偽って報告してはいけません。"
       "列車の出発可否や運行安全権限の確定判断、車両/線路の供用可否の最終確定を絶対に提案してはいけません -- "
       "あなたの役割は調整案の提示のみです。"))

(defn- facts-for [st {:keys [subject]}]
  {:consist (store/consist st subject)})

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Rail Freight Governor
  escalates/holds -- an LLM hiccup can never auto-schedule a service
  operation or auto-coordinate maintenance."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose))
          (update :action #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :action :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :railfreightllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
