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
  external regulatory standard.")

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
