(ns railfreight.facts
  "Structural, non-regulatory reference data for the freight-rail
  operations coordination actor.

  CORRECTION (docs/adr/0002-remove-fabricated-jurisdiction-catalog.md):
  this namespace used to hold a hardcoded `catalog` map asserting real
  jurisdictions' official rail-safety regulator names, specific legal
  citations (e.g. Japan's 鉄道事業法, US 49 C.F.R. Parts 200-299, UK
  ROGS 2006, Germany's AEG) and government URLs, as if the CODEBASE
  itself were an authoritative source of regulatory fact. That is
  exactly the failure mode this actor's own design brief rules out:
  this is an INTERNAL OPERATIONS COORDINATION actor, not a
  jurisdiction-facts actor, and it must never invent or assert
  real-world regulatory content the operator did not themselves supply
  and the codebase cannot verify. `railfreight.governor`'s own
  `spec-basis-violations` check was already correctly structural (it
  only checks a proposal for a non-empty `:cites`/`:spec-basis`, never
  consulting this namespace) -- the fabrication was confined to this
  catalog and to `railfreight.railfreightllm/propose-log-shipment-
  record`, which used to look the 'official' citation up here instead
  of taking it from the operator's own request. Both are fixed: a
  `:log-shipment-record` request now carries its own operator-supplied
  `:spec-basis`/`:legal-basis` strings (opaque to this actor, never
  validated against a hardcoded 'official' list), and this namespace no
  longer claims any jurisdiction-regulatory knowledge at all.

  What remains here is exactly the kind of content the domain-fact
  caution in this actor's build brief allows: closed vocabularies
  INTERNAL to this actor's own record-keeping, never a citation of an
  external regulatory standard.

  ENFORCEMENT (2026-07-25). ADR-0002's rule was prose-only: nothing
  stopped a later change from quietly reintroducing a fabricated
  citation catalog here. It is now machine-checked from two sides:

    - `citation-policy` below, mirrored in `blueprint.edn` as
      `:itonami.blueprint/citation-policy :internal-vocabulary-only`
      with a mandatory `:itonami.blueprint/citation-policy-basis`
      pointing at ADR-0002, so the fleet audit
      (`scripts/itonami-fleet-audit.cljs`) can distinguish \"citations
      forbidden by design\" from \"citations missing\" and stops
      reporting this repo as a real-world-ingest gap that must never
      be closed.
    - `test/railfreight/facts_test.clj` asserts this namespace contains
      no absolute http(s) URL and no regulatory-citation shape, so
      reintroducing the fabrication fails CI instead of passing review.")

(def citation-policy
  "This namespace deliberately holds NO external regulatory citations.

  `:internal-vocabulary-only` means: every value here is a closed
  vocabulary internal to this actor's own record-keeping. Real-world
  regulatory content (`:spec-basis` / `:legal-basis`) is
  operator-supplied per request and never invented or validated here.
  A zero citation count is the correct, complete state of this
  namespace -- see `:basis` for the decision record."
  {:policy :internal-vocabulary-only
   :basis "docs/adr/0002-remove-fabricated-jurisdiction-catalog.md"
   :rationale (str "Internal operations-coordination actor, not a "
                   "jurisdiction-facts actor. An earlier build fabricated "
                   "real regulators' names, legal citations and government "
                   "URLs here; ADR-0002 removed them and forbids their "
                   "return.")})

(def inspection-results
  "Closed set of recognized track/rolling-stock inspection outcome codes
  a robotics-assisted inspection record's `:inspection-result` may
  cite -- independently verified by the Rail Freight Governor
  (`railfreight.governor/inspection-record-invalid-violations`).
  Generic pass/conditional/fail/pending vocabulary internal to this
  actor's own record-keeping -- NOT a citation of any external
  inspection standard or certification scheme."
  #{"pass" "conditional-pass" "fail" "pending-review"})

(defn inspection-result-recognized? [result]
  (contains? inspection-results result))
