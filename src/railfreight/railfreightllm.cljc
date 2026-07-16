(ns railfreight.railfreightllm
  "RailFreight-LLM client -- the *contained intelligence node* for the
  community-freight-rail-operations-coordination actor.

  It normalizes/registers a consist's shipment/route record (cargo-
  manifest, routing), drafts a service-schedule COORDINATION proposal,
  drafts a track-safety-concern flag, and drafts a maintenance-
  coordination proposal. CRITICAL: it is a smart-but-untrusted
  advisor, and it is scoped to COORDINATION only -- it has NO track/
  dispatch-safety authority and NO rolling-stock/locomotive control
  (see `railfreight.governor` ns docstring `SCOPE`). It returns a
  *proposal* (`:effect` is ALWAYS the literal `:propose`), never a
  committed record and never a real dispatch clearance or maintenance
  release. Every output is censored downstream by
  `railfreight.governor` before anything touches the SSoT, and
  `:schedule-service-operation`/`:flag-track-safety-concern`/
  `:coordinate-maintenance` proposals NEVER auto-commit at any phase --
  see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis AND
                                 ; scope-exclusion gates
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
                                 ; :coordination/coordinate-maintenance | nil
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
            [railfreight.facts :as facts]
            [railfreight.store :as store]
            [langchain.model :as model]))

(defn- propose-log-shipment-record
  "Draft the consist/cargo-manifest/routing record UPDATE + its
  jurisdictional registration citation. The LLM only normalizes/
  validates the patch and cites the jurisdiction's own official
  source; it does not invent the patch fields, the jurisdiction, or a
  spec-basis for a jurisdiction with none on file."
  [db {:keys [subject patch no-spec?]}]
  (let [existing (store/consist db subject)
        base (merge existing patch)
        iso3 (if no-spec? "ATL" (:jurisdiction base))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str subject " の記録更新: " iso3 " の公式spec-basisが見つかりません")
       :rationale  "railfreight.facts に未登録の法域。要件を推測で作らない。この提案は記録の登録・検証を完了させない。"
       :cites      []
       :effect     :propose
       :action     :consist/log
       :value      {:patch (assoc patch :jurisdiction iso3) :spec-basis nil :legal-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str subject " の編成/貨物マニフェスト/経路記録を更新し、" iso3
                        " (" (:owner-authority sb) ") 向けに登録")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb)
                        " -- 本提案は記録の登録のみで、列車の運行や保守の可否判断は行わない")
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :propose
       :action     :consist/log
       :value      {:patch patch :spec-basis (:provenance sb) :legal-basis (:legal-basis sb)}
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

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-shipment-record            (propose-log-shipment-record db request)
    :schedule-service-operation     (propose-schedule-service-operation db request)
    :flag-track-safety-concern      (propose-flag-track-safety-concern db request)
    :coordinate-maintenance         (propose-coordinate-maintenance db request)
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
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :effect(常に:propose) "
       ":action(:consist/log|:consist/mark-scheduled|:consist/flag-safety-concern|"
       ":consist/mark-maintenance-coordinated) "
       ":stake(:coordination/schedule-service か :coordination/flag-track-safety-concern か "
       ":coordination/coordinate-maintenance か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "危険物取扱手順の確認状況や軌道安全懸念の解消状況を偽って報告してはいけません。"
       "列車の出発可否や運行安全権限の確定判断を絶対に提案してはいけません -- "
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
