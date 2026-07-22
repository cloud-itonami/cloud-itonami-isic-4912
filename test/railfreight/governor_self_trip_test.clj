(ns railfreight.governor-self-trip-test
  "Dedicated regression test for a bug class this exact fleet has
  independently rediscovered and fixed in multiple sibling repos: a
  governor's own scope-exclusion term list phrased as a bare noun
  (e.g. \"safety\", \"dispatch\", \"clearance\") accidentally matches
  inside the mock advisor's OWN default rationale/disclaimer text for
  a legitimate, allowed proposal -- causing the actor to self-block on
  its own happy path.

  `railfreight.governor/scope-exclusion-actions` is deliberately
  phrased as full finalization/execution ACTION phrases rather than
  bare nouns (see that var's own docstring for the reasoning). This
  test does not merely trust that phrasing choice by inspection -- it
  runs the DEFAULT mock advisor's `infer` for every op in the closed
  allowlist, across every demo consist (so every distinct rationale
  branch this advisor can produce is exercised, including the hazmat/
  concern/already-open/no-spec-basis branches), and asserts NONE of
  the resulting proposals trip `scope-exclusion-violations` -- the
  actual guarantee, not wording care alone."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [railfreight.governor :as governor]
            [railfreight.railfreightllm :as llm]
            [railfreight.store :as store]))

(defn- scope-exclusion-rule-fired? [request proposal st]
  (let [verdict (governor/check request {:actor-id "op-1"} proposal st)]
    (boolean (some #{:scope-exclusion-violation} (mapv :rule (:violations verdict))))))

(def ^:private all-ops
  "Every op in the closed allowlist -- see `railfreight.governor/
  allowed-ops`. Kept as a literal (not a require of that var) so this
  regression test independently re-derives which ops exist, the same
  'don't trust the thing under test to describe its own scope'
  discipline the rest of this ns docstring establishes."
  [:log-shipment-record :schedule-service-operation
   :flag-track-safety-concern :coordinate-maintenance
   :register-hazmat-transport-scope :log-inspection-record
   :release-rolling-stock-from-maintenance :log-reconciliation-record])

(deftest default-advisor-never-self-trips-the-scope-exclusion-check
  (let [db (store/seed-db)
        advisor (llm/mock-advisor)
        subjects (mapv :id (store/all-consists db))
        requests (for [op all-ops
                       subject subjects]
                   ;; :evidence/:inspection-result are deliberately omitted
                   ;; here -- the self-trip guarantee must hold even for the
                   ;; LOW-confidence/HARD-hold branches these ops take with
                   ;; no operator-supplied payload, not only the happy path.
                   {:op op :subject subject})]
    (doseq [{:keys [op subject] :as request} requests]
      (testing (str op " / " subject)
        (let [proposal (llm/-advise advisor db request)]
          (is (not (scope-exclusion-rule-fired? request proposal db))
              (str "default advisor's own proposal self-tripped scope-exclusion: "
                   (pr-str proposal))))))))

(deftest default-advisor-proposals-never-mention-a-forbidden-finalization-phrase-literally
  (testing "belt-and-suspenders: directly assert none of governor's own exclusion phrases appear verbatim in any default proposal's rationale/summary"
    (let [db (store/seed-db)
          advisor (llm/mock-advisor)
          subjects (mapv :id (store/all-consists db))]
      (doseq [op all-ops
              subject subjects]
        (let [proposal (llm/-advise advisor db {:op op :subject subject})
              text (str/lower-case (str (:summary proposal) " " (:rationale proposal)))]
          (doseq [phrase governor/scope-exclusion-actions]
            (is (not (str/includes? text (str/lower-case phrase)))
                (str op "/" subject " rationale unexpectedly contains exclusion phrase " (pr-str phrase)))))))))
