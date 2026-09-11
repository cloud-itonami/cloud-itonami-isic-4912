(ns railfreight.facts-test
  "`railfreight.facts` used to hold a hardcoded jurisdiction-regulatory
  catalog (real regulator names, legal citations, government URLs) --
  removed per `docs/adr/0002-remove-fabricated-jurisdiction-catalog.md`
  since this actor is an internal operations coordinator, not a
  jurisdiction-facts actor, and must never assert real-world
  regulatory content it cannot verify. What remains is the closed,
  non-regulatory `inspection-results` vocabulary this actor's own
  `:log-inspection-record` op cites."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [railfreight.facts :as facts]))

(deftest inspection-results-closed-vocabulary
  (is (contains? facts/inspection-results "pass"))
  (is (contains? facts/inspection-results "conditional-pass"))
  (is (contains? facts/inspection-results "fail"))
  (is (contains? facts/inspection-results "pending-review")))

(deftest inspection-result-recognized-predicate
  (is (true? (facts/inspection-result-recognized? "pass")))
  (is (true? (facts/inspection-result-recognized? "fail")))
  (is (false? (facts/inspection-result-recognized? "excellent")))
  (is (false? (facts/inspection-result-recognized? nil)))
  (is (false? (facts/inspection-result-recognized? ""))))

;; ───────── ADR-0002 enforcement: no fabricated citations may return ─────────

(deftest citation-policy-is-declared-and-traceable
  (testing "the policy names the decision record that justifies it"
    (is (= :internal-vocabulary-only (:policy facts/citation-policy)))
    (is (= "docs/adr/0002-remove-fabricated-jurisdiction-catalog.md"
           (:basis facts/citation-policy)))
    (is (seq (:rationale facts/citation-policy))))

  (testing "blueprint.edn mirrors the declaration, with a mandatory basis"
    (let [bp (edn/read-string (slurp (io/file "blueprint.edn")))
          bp (if (vector? bp) (first bp) bp)]
      (is (= :internal-vocabulary-only
             (:itonami.blueprint/citation-policy bp))
          "the fleet audit reads this key to avoid flagging a by-design gap")
      (is (seq (:itonami.blueprint/citation-policy-basis bp))
          "an unjustified declaration must not be honoured"))))

(defn- public-var-values
  "Every value held by a public var in `railfreight.facts`. Deliberately
  reads VALUES, not source text: docstrings are metadata and are excluded,
  so the namespace docstring's legitimate description of the removed
  fabrication (which names 49 C.F.R., ROGS 2006 etc.) cannot be mistaken
  for the fabrication itself -- and unlike a source-text scan, nothing is
  skipped because of where a quote happens to fall."
  []
  (->> (ns-publics 'railfreight.facts) vals (map deref)))

(defn- strings-in
  "All strings reachable inside `x` (maps, sets, vectors, nested)."
  [x]
  (cond
    (string? x) [x]
    (coll? x) (mapcat strings-in x)
    :else []))

(deftest facts-namespace-holds-no-external-citations
  ;; ADR-0002 was prose-only until now: nothing mechanically stopped a
  ;; fabricated jurisdiction catalog from being reintroduced here. This is
  ;; that missing guard.
  (let [values (public-var-values)
        ;; :basis is a repo-relative ADR path, not an external citation.
        strs (remove #{(:basis facts/citation-policy)} (mapcat strings-in values))]

    (testing "the public surface is a known, closed set of vars"
      (is (= #{"inspection-results" "inspection-result-recognized?" "citation-policy"}
             (set (map name (keys (ns-publics 'railfreight.facts)))))
          "a new public var here needs a deliberate review against ADR-0002"))

    (testing "no absolute http(s) URL -- the removed catalog carried government URLs"
      (is (empty? (filter #(re-find #"https?://" %) strs))
          "a URL in railfreight.facts means an external citation crept back in"))

    (testing "no jurisdiction-regulator citations"
      (is (empty? (filter #(re-find #"C\.F\.R\.|CFR|ROGS|鉄道事業法|AEG" %) strs))
          "the specific fabricated citations ADR-0002 removed must not return"))

    (testing "the guard actually bites -- an injected citation would be caught"
      ;; Proves the assertions above are not vacuously passing over an empty
      ;; or mis-sliced input.
      (is (seq strs) "there must be real strings under inspection")
      (let [poisoned (conj (vec strs) "49 C.F.R. Parts 200-299, https://www.fra.dot.gov/")]
        (is (seq (filter #(re-find #"https?://" %) poisoned)))
        (is (seq (filter #(re-find #"C\.F\.R\." %) poisoned)))))))
