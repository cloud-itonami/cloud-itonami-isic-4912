(ns railfreight.store
  "SSoT for the community-freight-rail-operations-coordination actor,
  behind a `Store` protocol so the backend is a swap, not a rewrite --
  the same seam every prior `cloud-itonami-isic-*` actor in this fleet
  uses (see e.g. `cloud-itonami-isic-4920`'s own `freightops.store`).

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/railfreight/store_contract_test.clj), which is the whole point:
  the actor, the Rail Freight Governor and the audit ledger never know
  which SSoT they run on.

  The single entity here is a `consist` (a train consist / cargo-
  manifest / routing record under coordination). `:log-shipment-record`
  registers/updates it, `:schedule-service-operation` and
  `:coordinate-maintenance` each apply SEQUENTIALLY to the SAME
  consist (a consist can be scheduled once its record is verified/
  registered, and separately have maintenance coordinated), with
  dedicated double-actuation-guard booleans (`:scheduled?`/
  `:maintenance-open?`, never a `:status` value) -- the same discipline
  `cloud-itonami-isic-4920`'s own `dispatched?`/`settled?` guards use.

  The ledger stays append-only on every backend: 'which consist was
  screened for an unregistered record, unconfirmed hazmat-handling
  protocol, or an open track-safety concern, which consist was
  scheduled, which maintenance coordination was opened, on what
  jurisdictional basis, approved by whom' is always a query over an
  immutable log -- the audit trail a shipper or regulator trusting a
  freight-rail operator needs, and the evidence an operator needs if a
  schedule or a maintenance action is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [railfreight.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (consist [s id])
  (all-consists [s])
  (ledger [s])
  (schedule-history [s] "the append-only service-schedule history (railfreight.registry drafts)")
  (maintenance-history [s] "the append-only maintenance-coordination history (railfreight.registry drafts)")
  (next-schedule-sequence [s jurisdiction] "next schedule-number sequence for a jurisdiction")
  (next-maintenance-sequence [s jurisdiction] "next maintenance-number sequence for a jurisdiction")
  (consist-already-scheduled? [s consist-id] "has this consist's service operation already been scheduled?")
  (consist-maintenance-already-open? [s consist-id] "does this consist already have an open maintenance-coordination request?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-consists [s consists] "replace/seed the consist directory (map id->consist)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained consist set covering both coordination
  lifecycles (schedule, maintenance) plus the governor's own checks,
  so the actor + tests run offline."
  []
  {:consists
   {"consist-1" {:id "consist-1" :carrier "Local Freight Rail Co"
                  :origin "Yard A" :destination "Yard B"
                  :cargo-manifest ["50x boxcar - general merchandise" "10x gondola - aggregate"]
                  :hazmat? false
                  :registered? true :spec-basis "https://www.mlit.go.jp/tetudo/tetudo_fr10_000006.html"
                  :legal-basis "鉄道事業法 (Railway Business Act)"
                  :hazmat-handling-confirmed? false
                  :safety-concern-raised? false :safety-concern-resolved? false
                  :scheduled? false :schedule-number nil
                  :maintenance-open? false :maintenance-number nil
                  :jurisdiction "JPN" :status :intake}
    "consist-2" {:id "consist-2" :carrier "Local Freight Rail Co"
                  :origin "Yard A" :destination "Yard C"
                  :cargo-manifest ["30x boxcar - general merchandise"]
                  :hazmat? false
                  :registered? false :spec-basis nil :legal-basis nil
                  :hazmat-handling-confirmed? false
                  :safety-concern-raised? false :safety-concern-resolved? false
                  :scheduled? false :schedule-number nil
                  :maintenance-open? false :maintenance-number nil
                  :jurisdiction "JPN" :status :intake}
    "consist-3" {:id "consist-3" :carrier "Local Freight Rail Co"
                  :origin "Yard A" :destination "Yard D"
                  :cargo-manifest ["2x tank car - class 3 flammable liquid"]
                  :hazmat? true
                  :registered? true :spec-basis "https://www.mlit.go.jp/tetudo/tetudo_fr10_000006.html"
                  :legal-basis "鉄道事業法 (Railway Business Act)"
                  :hazmat-handling-confirmed? false
                  :safety-concern-raised? false :safety-concern-resolved? false
                  :scheduled? false :schedule-number nil
                  :maintenance-open? false :maintenance-number nil
                  :jurisdiction "JPN" :status :intake}
    "consist-4" {:id "consist-4" :carrier "Local Freight Rail Co"
                  :origin "Yard A" :destination "Yard E"
                  :cargo-manifest ["40x boxcar - general merchandise"]
                  :hazmat? false
                  :registered? true :spec-basis "https://www.mlit.go.jp/tetudo/tetudo_fr10_000006.html"
                  :legal-basis "鉄道事業法 (Railway Business Act)"
                  :hazmat-handling-confirmed? false
                  :safety-concern-raised? true :safety-concern-resolved? false
                  :scheduled? false :schedule-number nil
                  :maintenance-open? false :maintenance-number nil
                  :jurisdiction "JPN" :status :intake}
    "consist-5" {:id "consist-5" :carrier "Local Freight Rail Co"
                  :origin "Yard A" :destination "Yard F"
                  :cargo-manifest ["20x gondola - aggregate"]
                  :hazmat? false
                  :registered? true :spec-basis "https://www.mlit.go.jp/tetudo/tetudo_fr10_000006.html"
                  :legal-basis "鉄道事業法 (Railway Business Act)"
                  :hazmat-handling-confirmed? false
                  :safety-concern-raised? false :safety-concern-resolved? false
                  :scheduled? false :schedule-number nil
                  ;; already has an open maintenance-coordination request --
                  ;; represents one opened through some earlier session/tool
                  ;; (its own number is out of THIS store's own sequence
                  ;; counter and history, deliberately: the flag alone is
                  ;; what `already-coordinating-violations` checks, never a
                  ;; history-vector length).
                  :maintenance-open? true :maintenance-number nil
                  :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-service!
  "Backend-agnostic `:consist/mark-scheduled` -- looks up the consist
  via the protocol and drafts the service-schedule record, and returns
  {:result .. :consist-patch ..} for the caller to persist."
  [s consist-id]
  (let [c (consist s consist-id)
        seq-n (next-schedule-sequence s (:jurisdiction c))
        result (registry/register-service-schedule consist-id (:jurisdiction c) seq-n)]
    {:result result
     :consist-patch {:scheduled? true
                     :schedule-number (get result "schedule_number")}}))

(defn- coordinate-maintenance!
  "Backend-agnostic `:consist/mark-maintenance-coordinated` -- looks up
  the consist via the protocol and drafts the maintenance-coordination
  record, and returns {:result .. :consist-patch ..} for the caller to
  persist."
  [s consist-id]
  (let [c (consist s consist-id)
        seq-n (next-maintenance-sequence s (:jurisdiction c))
        result (registry/register-maintenance-coordination consist-id (:jurisdiction c) seq-n)]
    {:result result
     :consist-patch {:maintenance-open? true
                     :maintenance-number (get result "maintenance_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (consist [_ id] (get-in @a [:consists id]))
  (all-consists [_] (sort-by :id (vals (:consists @a))))
  (ledger [_] (:ledger @a))
  (schedule-history [_] (:schedules @a))
  (maintenance-history [_] (:maintenances @a))
  (next-schedule-sequence [_ jurisdiction] (get-in @a [:schedule-sequences jurisdiction] 0))
  (next-maintenance-sequence [_ jurisdiction] (get-in @a [:maintenance-sequences jurisdiction] 0))
  (consist-already-scheduled? [_ consist-id] (boolean (get-in @a [:consists consist-id :scheduled?])))
  (consist-maintenance-already-open? [_ consist-id] (boolean (get-in @a [:consists consist-id :maintenance-open?])))
  (commit-record! [s {:keys [action path value]}]
    (case action
      :consist/log
      (let [consist-id (first path)
            {:keys [patch spec-basis legal-basis]} value]
        (swap! a update-in [:consists consist-id]
               merge (assoc patch
                            :registered? (some? spec-basis)
                            :spec-basis spec-basis
                            :legal-basis legal-basis)))

      :consist/flag-safety-concern
      (let [consist-id (first path)
            {:keys [note]} value]
        (swap! a update-in [:consists consist-id]
               merge {:safety-concern-raised? true
                      :safety-concern-resolved? false
                      :safety-concern-note note}))

      :consist/mark-scheduled
      (let [consist-id (first path)
            {:keys [result consist-patch]} (schedule-service! s consist-id)
            jurisdiction (:jurisdiction (consist s consist-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:schedule-sequences jurisdiction] (fnil inc 0))
                       (update-in [:consists consist-id] merge consist-patch)
                       (update :schedules registry/append result))))
        result)

      :consist/mark-maintenance-coordinated
      (let [consist-id (first path)
            {:keys [result consist-patch]} (coordinate-maintenance! s consist-id)
            jurisdiction (:jurisdiction (consist s consist-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:maintenance-sequences jurisdiction] (fnil inc 0))
                       (update-in [:consists consist-id] merge consist-patch)
                       (update :maintenances registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-consists [s consists] (when (seq consists) (swap! a assoc :consists consists)) s))

(defn seed-db
  "A MemStore seeded with the demo consist set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :ledger [] :schedule-sequences {} :schedules []
                           :maintenance-sequences {} :maintenances []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (ledger facts, schedule/maintenance records) are
  stored as EDN strings so `langchain.db` doesn't expand them into
  sub-entities -- the same convention every sibling actor's store
  uses."
  {:consist/id                  {:db/unique :db.unique/identity}
   :ledger/seq                  {:db/unique :db.unique/identity}
   :schedule/seq                {:db/unique :db.unique/identity}
   :maintenance/seq              {:db/unique :db.unique/identity}
   :schedule-sequence/jurisdiction    {:db/unique :db.unique/identity}
   :maintenance-sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- consist->tx [{:keys [id carrier origin destination cargo-manifest hazmat?
                            registered? spec-basis legal-basis
                            hazmat-handling-confirmed?
                            safety-concern-raised? safety-concern-resolved? safety-concern-note
                            scheduled? schedule-number
                            maintenance-open? maintenance-number
                            jurisdiction status]}]
  (cond-> {:consist/id id}
    carrier                                          (assoc :consist/carrier carrier)
    origin                                              (assoc :consist/origin origin)
    destination                                           (assoc :consist/destination destination)
    cargo-manifest                                          (assoc :consist/cargo-manifest (enc cargo-manifest))
    (some? hazmat?)                                            (assoc :consist/hazmat? hazmat?)
    (some? registered?)                                          (assoc :consist/registered? registered?)
    spec-basis                                                    (assoc :consist/spec-basis spec-basis)
    legal-basis                                                    (assoc :consist/legal-basis legal-basis)
    (some? hazmat-handling-confirmed?)                              (assoc :consist/hazmat-handling-confirmed? hazmat-handling-confirmed?)
    (some? safety-concern-raised?)                                    (assoc :consist/safety-concern-raised? safety-concern-raised?)
    (some? safety-concern-resolved?)                                    (assoc :consist/safety-concern-resolved? safety-concern-resolved?)
    safety-concern-note                                                  (assoc :consist/safety-concern-note safety-concern-note)
    (some? scheduled?)                                                     (assoc :consist/scheduled? scheduled?)
    schedule-number                                                          (assoc :consist/schedule-number schedule-number)
    (some? maintenance-open?)                                                  (assoc :consist/maintenance-open? maintenance-open?)
    maintenance-number                                                           (assoc :consist/maintenance-number maintenance-number)
    jurisdiction                                                                   (assoc :consist/jurisdiction jurisdiction)
    status                                                                           (assoc :consist/status status)))

(def ^:private consist-pull
  [:consist/id :consist/carrier :consist/origin :consist/destination :consist/cargo-manifest
   :consist/hazmat? :consist/registered? :consist/spec-basis :consist/legal-basis
   :consist/hazmat-handling-confirmed?
   :consist/safety-concern-raised? :consist/safety-concern-resolved? :consist/safety-concern-note
   :consist/scheduled? :consist/schedule-number
   :consist/maintenance-open? :consist/maintenance-number
   :consist/jurisdiction :consist/status])

(defn- pull->consist [m]
  (when (:consist/id m)
    {:id (:consist/id m) :carrier (:consist/carrier m) :origin (:consist/origin m)
     :destination (:consist/destination m)
     :cargo-manifest (dec* (:consist/cargo-manifest m))
     :hazmat? (boolean (:consist/hazmat? m))
     :registered? (boolean (:consist/registered? m))
     :spec-basis (:consist/spec-basis m) :legal-basis (:consist/legal-basis m)
     :hazmat-handling-confirmed? (boolean (:consist/hazmat-handling-confirmed? m))
     :safety-concern-raised? (boolean (:consist/safety-concern-raised? m))
     :safety-concern-resolved? (boolean (:consist/safety-concern-resolved? m))
     :safety-concern-note (:consist/safety-concern-note m)
     :scheduled? (boolean (:consist/scheduled? m)) :schedule-number (:consist/schedule-number m)
     :maintenance-open? (boolean (:consist/maintenance-open? m)) :maintenance-number (:consist/maintenance-number m)
     :jurisdiction (:consist/jurisdiction m) :status (:consist/status m)}))

(defrecord DatomicStore [conn]
  Store
  (consist [_ id]
    (pull->consist (d/pull (d/db conn) consist-pull [:consist/id id])))
  (all-consists [_]
    (->> (d/q '[:find [?id ...] :where [?e :consist/id ?id]] (d/db conn))
         (map #(pull->consist (d/pull (d/db conn) consist-pull [:consist/id %])))
         (sort-by :id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (schedule-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :schedule/seq ?s] [?e :schedule/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (maintenance-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :maintenance/seq ?s] [?e :maintenance/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-schedule-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :schedule-sequence/jurisdiction ?j] [?e :schedule-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-maintenance-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :maintenance-sequence/jurisdiction ?j] [?e :maintenance-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (consist-already-scheduled? [s consist-id]
    (boolean (:scheduled? (consist s consist-id))))
  (consist-maintenance-already-open? [s consist-id]
    (boolean (:maintenance-open? (consist s consist-id))))
  (commit-record! [s {:keys [action path value]}]
    (case action
      :consist/log
      (let [consist-id (first path)
            {:keys [patch spec-basis legal-basis]} value]
        (d/transact! conn [(consist->tx (assoc (merge (consist s consist-id) patch)
                                               :id consist-id
                                               :registered? (some? spec-basis)
                                               :spec-basis spec-basis
                                               :legal-basis legal-basis))]))

      :consist/flag-safety-concern
      (let [consist-id (first path)
            {:keys [note]} value]
        (d/transact! conn [(consist->tx (assoc (consist s consist-id)
                                               :id consist-id
                                               :safety-concern-raised? true
                                               :safety-concern-resolved? false
                                               :safety-concern-note note))]))

      :consist/mark-scheduled
      (let [consist-id (first path)
            {:keys [result consist-patch]} (schedule-service! s consist-id)
            jurisdiction (:jurisdiction (consist s consist-id))
            next-n (inc (next-schedule-sequence s jurisdiction))]
        (d/transact! conn
                     [(consist->tx (assoc (merge (consist s consist-id) consist-patch) :id consist-id))
                      {:schedule-sequence/jurisdiction jurisdiction :schedule-sequence/next next-n}
                      {:schedule/seq (count (schedule-history s)) :schedule/record (enc (get result "record"))}])
        result)

      :consist/mark-maintenance-coordinated
      (let [consist-id (first path)
            {:keys [result consist-patch]} (coordinate-maintenance! s consist-id)
            jurisdiction (:jurisdiction (consist s consist-id))
            next-n (inc (next-maintenance-sequence s jurisdiction))]
        (d/transact! conn
                     [(consist->tx (assoc (merge (consist s consist-id) consist-patch) :id consist-id))
                      {:maintenance-sequence/jurisdiction jurisdiction :maintenance-sequence/next next-n}
                      {:maintenance/seq (count (maintenance-history s)) :maintenance/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-consists [s consists]
    (when (seq consists) (d/transact! conn (mapv consist->tx (vals consists)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:consists ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [consists]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-consists s consists))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo consist set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
