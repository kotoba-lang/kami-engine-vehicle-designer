(ns vdesign.powertrain
  "Energy-system models for a clean-sheet vehicle — the ONLY part of the
  design that branches on powertrain. Everything upstream (glider, road
  load, package envelope) is propulsion-agnostic; the two functions here
  size the on-board energy store from the *same* mission energy.

  All sizing is first-principles, not a lookup table:

    road load → mechanical wheel energy per km
              → source energy per km   (÷ path efficiency, per powertrain)
              → store size for the required range
              → store MASS, which feeds back into road load (the mass
                spiral the PhysicsGovernor closes).

  Units are SI internally (J, kg, m, W); kWh / L surface in the result
  maps for human-readable specs. Constants reflect ~2026 production
  technology and are the single place to retune as cells/tanks improve."
  (:require [clojure.string :as str]))

;; ───────────────────────────── constants ─────────────────────────────

(def ^:const g 9.81)              ; m/s^2
(def ^:const rho-air 1.225)       ; kg/m^3
(def ^:const J-per-kWh 3.6e6)
(def ^:const LHV-H2-J 1.20e8)     ; J/kg  (hydrogen lower heating value, 120 MJ/kg)

;; Steady rolling+aero at one average speed undercounts a real drive cycle:
;; it ignores the accel/transient energy that braking only partly returns,
;; plus the high-speed aero share of mixed driving. This blends a single
;; operating point up to representative (≈WLTP-class) cycle energy.
(def ^:const cycle-factor 1.55)

;; Pack / tank / stack technology — pack-LEVEL figures (not bare cell).
(def tech
  {:bev  {:b2w-eff      0.88      ; battery→wheel (inverter·motor·gearbox)
          :dcdc-eff     0.95      ; battery→12V/aux bus
          :regen-credit 0.15      ; share of mech demand recovered on a mixed cycle
          :dod          0.90      ; usable depth of discharge
          :pack-Wh-kg   175.0     ; pack-level gravimetric energy
          :pack-kWh-L   0.45      ; pack-level volumetric energy
          :motor-kW-kg  5.0}      ; traction motor+inverter specific power
   :fcev {:b2w-eff      0.88      ; buffer-battery/motor→wheel
          :fc-elec-eff  0.53      ; H2(LHV)→electric, system (stack+BoP)
          :regen-credit 0.15
          :grav-frac    0.055     ; usable H2 / tank-system mass (700 bar type-IV)
          :h2-kg-L      0.040     ; stored H2 density @700 bar (≈40 kg/m^3)
          :tank-overhead 2.2      ; external system vol / internal H2 vol
          :fc-kW-kg     2.0       ; fuel-cell system specific power (incl BoP)
          :motor-kW-kg  5.0
          :buffer-kWh   1.5       ; traction buffer battery
          :buffer-Wh-kg 150.0}})

;; ─────────────────────── shared road-load model ──────────────────────

(defn road-load-J-per-km
  "Mechanical energy delivered at the wheels to cover 1 km at the design
  operating mass, on a representative steady cycle. Rolling + aero only;
  grade/transients average out over a cycle. `regen-credit` reflects the
  share of that energy recovered under braking."
  [{:keys [crr cd frontal-area avg-speed]} mass-kg regen-credit]
  (let [v       avg-speed                       ; m/s
        f-roll  (* crr mass-kg g)               ; N
        f-aero  (* 0.5 rho-air cd frontal-area v v)
        e-mech  (* (+ f-roll f-aero) 1000.0 cycle-factor)]   ; J over 1000 m, cycle-blended
    (* e-mech (- 1.0 regen-credit))))

(defn aux-J-per-km
  "Electrical auxiliary energy (HVAC, compute, lights) per km — a function
  of TIME, so it scales inversely with speed, not distance."
  [p-aux-w avg-speed]
  (* p-aux-w (/ 1000.0 avg-speed)))

;; ───────────────────────── BEV energy system ─────────────────────────

(defn size-bev
  "Battery-electric energy system for `range-km` at operating `mass-kg`.
  Returns {:kind :bev :consumption-kWh-km .. :usable-kWh .. :nominal-kWh ..
           :mass-kg .. :volume-L .. :p-peak-kW ..}."
  [glider concept mass-kg]
  (let [{:keys [b2w-eff dcdc-eff regen-credit dod pack-Wh-kg pack-kWh-L motor-kW-kg]}
        (:bev tech)
        {:keys [range-km]} concept
        e-mech   (road-load-J-per-km glider mass-kg regen-credit)
        e-aux    (aux-J-per-km (:p-aux-w concept) (:avg-speed glider))
        batt-J   (+ (/ e-mech b2w-eff) (/ e-aux dcdc-eff))   ; at the battery, per km
        cons-kWh (/ batt-J J-per-kWh)
        usable   (* cons-kWh range-km)
        nominal  (/ usable dod)
        mass     (/ (* nominal 1000.0) pack-Wh-kg)
        volume   (/ nominal pack-kWh-L)
        motor    (/ (:p-peak-kw concept) motor-kW-kg)]
    {:kind :bev
     :consumption-kWh-km cons-kWh
     :usable-kWh usable
     :nominal-kWh nominal
     :propulsion-mass-kg motor
     :store-mass-kg mass
     :mass-kg (+ mass motor)
     :volume-L volume
     :p-peak-kW (:p-peak-kw concept)}))

;; ──────────────────────── FCEV energy system ─────────────────────────

(defn size-fcev
  "Hydrogen fuel-cell energy system for `range-km` at operating `mass-kg`.
  H2(LHV) → electric (stack) → wheel (buffer/motor). Returns the parallel
  shape to `size-bev`, with H2 mass + tank/stack/buffer breakdown."
  [glider concept mass-kg]
  (let [{:keys [b2w-eff fc-elec-eff regen-credit grav-frac h2-kg-L tank-overhead
                fc-kW-kg motor-kW-kg buffer-kWh buffer-Wh-kg]}
        (:fcev tech)
        {:keys [range-km]} concept
        e-mech   (road-load-J-per-km glider mass-kg regen-credit)
        e-aux    (aux-J-per-km (:p-aux-w concept) (:avg-speed glider))
        ;; electric energy at the bus per km, then up-convert through the stack
        elec-J   (+ (/ e-mech b2w-eff) e-aux)
        h2-J     (/ elec-J fc-elec-eff)                ; H2 LHV energy per km
        h2-kg    (/ (* h2-J range-km) LHV-H2-J)
        tank     (/ h2-kg grav-frac)                   ; full tank-system mass
        tank-vol (* (/ h2-kg h2-kg-L) tank-overhead)   ; external L
        stack    (/ (:p-peak-kw concept) fc-kW-kg)
        buffer   (/ (* buffer-kWh 1000.0) buffer-Wh-kg)
        motor    (/ (:p-peak-kw concept) motor-kW-kg)]
    {:kind :fcev
     :h2-kg h2-kg
     :consumption-kWh-km (/ h2-J J-per-kWh)            ; H2 LHV basis
     :tank-mass-kg tank
     :stack-mass-kg stack
     :buffer-mass-kg buffer
     :propulsion-mass-kg (+ stack buffer motor)
     :store-mass-kg (+ tank h2-kg)
     :mass-kg (+ tank h2-kg stack buffer motor)
     :volume-L tank-vol
     :p-peak-kW (:p-peak-kw concept)}))

(defn size
  "Dispatch on powertrain. `mass-kg` is the current operating-mass estimate
  in the closure spiral (the PhysicsGovernor calls this repeatedly)."
  [powertrain glider concept mass-kg]
  (case powertrain
    :bev  (size-bev  glider concept mass-kg)
    :fcev (size-fcev glider concept mass-kg)
    (throw (ex-info "unknown powertrain" {:powertrain powertrain}))))
