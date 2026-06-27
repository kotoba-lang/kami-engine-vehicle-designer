(ns vdesign.simverify
  "SimGovernor — (a) push a physics-closed spec into a structural + collision
  verification expressed against the Isaac/genesis-compat surface, then
  harden it with kami-shugyo-style per-env DOMAIN RANDOMIZATION (sim-to-real
  margin). The verdict and every check are emitted as kotoba datoms.

  This is a SECOND, independent governor downstream of the PhysicsGovernor:
  closure proves the design *exists*; this proves it *survives* — crash
  load paths, package clash, axle loading — and that those margins hold
  under manufacturing + test scatter, not just at the nominal point.

  Clean-room: we speak the kami-genesis (`isaacsim.core.api`) / kami-cae
  vocabulary (Articulation, Collider, multi-env batch, mass DR) but compute
  on this actor's own closed-form structural model — no NVIDIA code, same
  invariant the kami-engine Isaac-compat stack holds (ADR-0034)."
  (:require [vdesign.datom :as d]))

(def ^:const g 9.81)
(def ^:const a-crash (* 20.0 g))     ; 20 g frontal crash pulse
(def ^:const n-envs 16)              ; Isaac-style vectorized envs
(def ^:const sf-floor 1.5)           ; nominal structural safety factor
(def ^:const dr-sf-floor 1.3)        ; worst-case over the randomized batch

;; Per-class geometry priors (the cae/genesis scene the spec instantiates).
(def geom
  {:city  {:floor-area 1.6 :floor-clear 0.12 :wheelbase 2.40 :front-frac 0.50
           :struct-N-kg 240 :axle-N-kg 230}
   :sedan {:floor-area 2.4 :floor-clear 0.13 :wheelbase 2.90 :front-frac 0.50
           :struct-N-kg 250 :axle-N-kg 230}
   :suv   {:floor-area 2.8 :floor-clear 0.15 :wheelbase 2.95 :front-frac 0.52
           :struct-N-kg 260 :axle-N-kg 235}
   :truck {:floor-area 6.0 :floor-clear 0.20 :wheelbase 3.60 :front-frac 0.45
           :struct-N-kg 300 :axle-N-kg 240}})

;; ─────────────── reproducible domain randomization (seeded LCG) ───────────────

(defn- lcg-seq
  "Deterministic [0,1) stream from a seed — no Math/random, so every run and
  every test sees the identical randomized batch (reproducibility = G6)."
  [seed n]
  (->> (iterate (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)) (long seed))
       (rest) (take n) (mapv #(/ (double %) 2147483648.0))))

(defn- jitter [r center frac]
  (* center (+ (- 1.0 frac) (* r 2.0 frac))))

;; ───────────────────────────── checks ─────────────────────────────

(defn- structural-sf
  "Crash-load safety factor: the floor rails must carry the energy store's
  inertial load under the crash pulse. allowable ∝ glider mass (structure)."
  [gm store-mass glider-mass decel]
  (let [load      (* store-mass decel)              ; N into the rails
        allowable (* (:struct-N-kg gm) glider-mass)]
    (/ allowable (max load 1.0))))

(defn- clash-sf
  "Package clash: does the store actually FIT its placement, not just the
  volume budget? BEV = floor skateboard (height-limited); FCEV = cylindrical
  tanks (packing-limited)."
  [gm powertrain store-vol-L avail-L]
  (case powertrain
    :bev  (let [h (/ (* store-vol-L 1e-3) (:floor-area gm))]  ; pack height, m
            (/ (:floor-clear gm) (max h 1e-3)))
    ;; store-vol-L already includes type-IV overhead (powertrain/tank-overhead);
    ;; here we add only the install dead-space around the cylinders (≈15%).
    :fcev (/ avail-L (max (/ store-vol-L 0.85) 1e-3))))

(defn- axle-sf
  "Front/rear axle loading vs rating (rating ∝ gross-mass limit)."
  [gm curb gross]
  (let [front (* curb g (:front-frac gm))
        rear  (* curb g (- 1.0 (:front-frac gm)))
        rating (* (:axle-N-kg gm) gross)]
    (/ rating (max front rear))))

(defn check
  "Run the genesis/cae-compat verification on a released `design`. Returns
  {:passed? :min-sf :checks [..] :envs n :datoms .. :tx ..}."
  [design]
  (let [{:keys [class powertrain curb-mass-kg energy margins]} design
        gm        (get geom class (:sedan geom))
        store-m   (:store-mass-kg energy)
        store-v   (:volume-L energy)
        glider    (get-in design [:mass-budget :glider-kg])
        gross     (+ curb-mass-kg (:gross-kg margins))   ; reconstruct GVWR
        avail-L   (+ store-v (:volume-L margins))
        ;; nominal checks
        nom {:structural (structural-sf gm store-m glider a-crash)
             :clash      (clash-sf gm powertrain store-v avail-L)
             :axle       (axle-sf gm curb-mass-kg gross)}
        ;; Isaac-style randomized batch: vary store mass, glider strength,
        ;; crash decel per env; the structural path is the worst-case driver.
        rs        (partition 3 (lcg-seq (+ 1009 (hash [class powertrain])) (* 3 n-envs)))
        dr-sfs    (mapv (fn [[rm rg rd]]
                          (structural-sf gm (jitter rm store-m 0.08)
                                         (jitter rg glider 0.06)
                                         (jitter rd a-crash 0.10)))
                        rs)
        dr-min    (reduce min dr-sfs)
        checks    [{:check :structural :sf (:structural nom)
                    :pass? (>= (:structural nom) sf-floor)
                    :detail (str "20 g crash: floor rails carry "
                                 (Math/round (double store-m)) " kg store")}
                   {:check :package-clash :sf (:clash nom)
                    :pass? (>= (:clash nom) 1.0)
                    :detail (case powertrain
                              :bev  "pack height within floor-to-cabin clearance"
                              :fcev "H2 cylinders within rear/tunnel packing")}
                   {:check :axle-load :sf (:axle nom)
                    :pass? (>= (:axle nom) 1.3)
                    :detail "heavier-axle load vs axle rating"}
                   {:check :dr-robustness :sf dr-min
                    :pass? (>= dr-min dr-sf-floor)
                    :detail (str "worst structural SF over " n-envs
                                 " randomized envs (mass ±8% · strength ±6% · pulse ±10%)")}]
        passed?   (every? :pass? checks)
        min-sf    (reduce min (map :sf checks))
        run-id    (str "simrun-" (name class) "-" (name powertrain))
        sim-ent   (d/entity :SimRun run-id
                            {:solver     "kami-genesis(isaacsim.core.api-compat)"
                             :scene      (str (name class) "/" (name powertrain))
                             :envs       n-envs
                             :passed     passed?
                             :minSF      (Math/round (* 100.0 min-sf))})
        chk-ents  (map (fn [c]
                         (d/entity :SimCheck (str run-id "/" (name (:check c)))
                                   {:run run-id :kind (name (:check c))
                                    :sf (Math/round (* 100.0 (:sf c)))
                                    :pass (:pass? c)}))
                       checks)
        ledger    (d/log (cons sim-ent chk-ents))]
    {:passed? passed?
     :min-sf  min-sf
     :checks  checks
     :envs    n-envs
     :tx      (:tx ledger)
     :datoms  (:datoms ledger)
     :datom-count (:count ledger)}))
