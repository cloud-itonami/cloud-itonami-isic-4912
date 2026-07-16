(ns railfreight.facts
  "Per-jurisdiction rail-carrier-safety AND hazmat-by-rail regulatory
  catalog -- the G2-style spec-basis table the Rail Freight Governor
  checks every `:log-shipment-record` proposal against ('did the
  advisor cite an OFFICIAL public source for this jurisdiction's
  consist/route-registration requirements, or did it invent one?').

  Like `cloud-itonami-isic-4920`'s own `freightops.facts` (general
  road freight), each jurisdiction entry here cites BOTH the general
  rail-carrier-safety regulator/law AND a SEPARATE hazmat-by-rail
  transport regime -- freight rail carries its own hazardous-
  materials-by-rail regime distinct from road/parcel hazmat rules (see
  this repo's README `Scope note`).

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  consist-registration/carrier-authorization/routing-record evidence
  set (PLUS a hazmat-handling-protocol-confirmation record for every
  seeded jurisdiction); `:legal-basis` / `:owner-authority` /
  `:provenance` are the G2 citation the governor requires before any
  `:log-shipment-record` proposal can commit. `:hazmat-owner-authority`
  / `:hazmat-legal-basis` / `:hazmat-provenance` are the SEPARATE
  hazmat-by-rail citation the governor's own hazmat-handling-protocol
  check is grounded in."
  {"JPN" {:name "Japan"
          :owner-authority "国土交通省鉄道局 (Ministry of Land, Infrastructure, Transport and Tourism, Railway Bureau)"
          :legal-basis "鉄道事業法 (Railway Business Act)"
          :national-spec "鉄道事業法に基づく安全管理規程・輸送の安全に関する基準"
          :provenance "https://www.mlit.go.jp/tetudo/tetudo_fr10_000006.html"
          :required-evidence ["編成登録記録 (consist-registration record)"
                              "事業者認可記録 (carrier-authorization record)"
                              "運行経路記録 (routing record)"
                              "危険物取扱手順確認記録 (hazmat-handling-protocol-confirmation record)"]
          :hazmat-owner-authority "国土交通省鉄道局"
          :hazmat-legal-basis "鉄道による危険物の運送に関する技術上の基準を定める省令"
          :hazmat-provenance "https://www.mlit.go.jp/tetudo/tetudo_fr10_000006.html"}
   "USA" {:name "United States"
          :owner-authority "Federal Railroad Administration (FRA)"
          :legal-basis "Federal railroad safety regulations (49 C.F.R. Parts 200-299)"
          :national-spec "FRA track-safety/operating-practices standards for freight railroads"
          :provenance "https://railroads.dot.gov/regulations"
          :required-evidence ["Consist-registration record"
                              "Carrier-authorization record"
                              "Routing record"
                              "Hazmat-handling-protocol-confirmation record"]
          :hazmat-owner-authority "Pipeline and Hazardous Materials Safety Administration (PHMSA)"
          :hazmat-legal-basis "Hazardous Materials Regulations (49 C.F.R. Parts 171-180)"
          :hazmat-provenance "https://www.phmsa.dot.gov/regulations"}
   "GBR" {:name "United Kingdom"
          :owner-authority "Office of Rail and Road (ORR)"
          :legal-basis "Railways and Other Guided Transport Systems (Safety) Regulations 2006 (ROGS)"
          :national-spec "ORR safety-management-system standards for freight-rail operators"
          :provenance "https://www.orr.gov.uk/rail/health-and-safety"
          :required-evidence ["Consist-registration record"
                              "Carrier-authorization record"
                              "Routing record"
                              "Hazmat-handling-protocol-confirmation record"]
          :hazmat-owner-authority "Office of Rail and Road (ORR) / Department for Transport"
          :hazmat-legal-basis "Carriage of Dangerous Goods and Use of Transportable Pressure Equipment Regulations 2009 (CDG 2009)"
          :hazmat-provenance "https://www.legislation.gov.uk/uksi/2009/1348/contents"}
   "DEU" {:name "Germany"
          :owner-authority "Eisenbahn-Bundesamt (EBA)"
          :legal-basis "Allgemeines Eisenbahngesetz (AEG, General Railway Act)"
          :national-spec "AEG Sicherheitsanforderungen für Eisenbahnverkehrsunternehmen"
          :provenance "https://www.gesetze-im-internet.de/aeg_1994/"
          :required-evidence ["Zugbildungsnachweis (consist-registration record)"
                              "Unternehmenszulassungsnachweis (carrier-authorization record)"
                              "Streckennachweis (routing record)"
                              "Gefahrgutverfahrensnachweis (hazmat-handling-protocol-confirmation record)"]
          :hazmat-owner-authority "Eisenbahn-Bundesamt (EBA)"
          :hazmat-legal-basis "Gefahrgutverordnung Eisenbahn und Binnenschifffahrt (GGVSEB)"
          :hazmat-provenance "https://www.gesetze-im-internet.de/ggvseb/"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to register a
  consist/route or schedule a service operation on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-4912 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `railfreight.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn hazmat-spec-basis
  "The jurisdiction's hazmat-by-rail requirement map, or nil -- nil
  means this jurisdiction has NO formal hazmat-by-rail regime this
  catalog is aware of. In this R0 catalog all four seeded
  jurisdictions actually have one, reported honestly."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:hazmat-owner-authority sb)
      (select-keys sb [:hazmat-owner-authority :hazmat-legal-basis :hazmat-provenance]))))
