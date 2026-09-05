(ns vdesign.swap-schedule-test
  "Tests for vdesign.swap-schedule — exact energy identities on synthetic
  fixtures (fixture numbers carry fixture provenance; no Mg/MgH2, PEM,
  or vehicle constant is measured or invented here). Demands are stated
  in kg of H2 per interval and converted to kW by the EXACT identity the
  contract itself uses, so the assertions read in kg."
  (:require [clojure.test :refer [deftest is testing]]
            [vdesign.swap-schedule :as ss]
            [vdesign.powertrain :as pt]
            [vdesign.cartridge :as cart]))

(def ^:private fixture-eff 0.5)
(def ^:private eff-src "fixture: synthetic efficiency for tests only")
(def ^:private cart-src "fixture: synthetic cartridge capacity for tests only")

(def ^:private dt 3600.0)

(defn- close? [a b] (< (Math/abs (- (double a) (double b)))
                       (* 1e-9 (max 1.0 (Math/abs (double b))))))

;; kW that makes one 3600 s interval draw exactly `kg` of H2 at η=0.5:
;; kg = E/(η·LHV), E = kW·1000·dt  →  kW = kg·η·LHV/(1000·dt)
(defn- kw-for-kg [kg]
  (/ (* kg fixture-eff pt/LHV-H2-J) (* 1000.0 dt)))

(defn- run
  ([kg-profile cap] (run kg-profile cap 0))
  ([kg-profile cap spares]
   (ss/swap-schedule {:fc-profile-kw (mapv kw-for-kg kg-profile)
                      :dt-s dt
                      :fc-elec-eff fixture-eff :eff-source eff-src
                      :cartridge-h2-kg cap :cartridge-source cart-src
                      :spare-cartridges spares})))

(defn- served-kg
  "H2 actually delivered in an interval row: demand − unmet."
  [row] (- (:h2-kg row) (:unmet-h2-kg row)))

(deftest single-cartridge-draws-to-exhaustion
  (testing "a mission within one cartridge: no swaps, one partial residual"
    (let [cap 10.0
          r (run [1.0 1.0] cap)]
      (is (zero? (get-in r [:swaps :count])))
      (is (nil? (get-in r [:swaps :first-at-interval])))
      (is (close? 2.0 (get-in r [:h2 :total-kg])))
      (is (zero? (get-in r [:h2 :total-unmet-kg])))
      (is (= 1 (get-in r [:swaps :total-cartridges-consumed]))
          "the one cartridge delivered H2 — it counts, with residual")
      ;; the mission ends mid-cartridge → residual = cap − used
      (is (= 1 (count (:residual r))))
      (is (close? (- cap 2.0) (:residual-h2-kg (first (:residual r)))))
      (is (nil? (:exhausted-at-interval-or-nil (first (:residual r))))))))

(deftest swap-happens-at-depletion-interval
  (testing "demand exceeding one cartridge draws a spare exactly when it empties"
    (let [cap 10.0
          ;; interval 0 draws 6 kg (4 left), interval 1 draws 6 kg →
          ;; 4 from cartridge 0, 2 from cartridge 1 → exactly 1 swap at i=1
          r (run [6.0 6.0] cap 1)]
      (is (= 1 (get-in r [:swaps :count])))
      (is (= 1 (get-in r [:swaps :first-at-interval])))
      (is (= 2 (get-in r [:swaps :total-cartridges-consumed])))
      (is (close? 12.0 (get-in r [:h2 :total-kg])))
      (is (zero? (get-in r [:h2 :total-unmet-kg])))
      ;; every interval fully served
      (is (close? 12.0 (reduce + 0.0 (map served-kg (:intervals r)))))
      ;; cartridge 0 exhausted (residual 0) at interval 1; cartridge 1 partial
      (is (= 2 (count (:residual r))))
      (let [c0 (first (:residual r))]
        (is (close? 0.0 (:residual-h2-kg c0)))
        (is (= 1 (:exhausted-at-interval c0))))
      (let [c1 (second (:residual r))]
        (is (close? (- cap 2.0) (:residual-h2-kg c1)))
        (is (nil? (:exhausted-at-interval-or-nil c1))))
      ;; conservation: delivered = Σ served = (cap from c0) + 2 (c1)
      (is (close? 12.0 (get-in r [:h2 :delivered-kg]))))))

(deftest multiple-swaps-within-one-interval
  (testing "a draw larger than a full cartridge swaps multiple times inside one interval"
    (let [cap 10.0
          ;; one interval drawing 25 kg from 10 kg cartridges: 2 full +
          ;; a 5 kg draw from a 3rd → 2 swaps inside the interval
          r (run [25.0] cap 2)]
      (is (= 2 (get-in r [:swaps :count])))
      (is (= 0 (get-in r [:swaps :first-at-interval]))
          "both swaps happen inside interval 0")
      (is (= 3 (get-in r [:swaps :total-cartridges-consumed])))
      (is (close? 25.0 (get-in r [:h2 :total-kg])))
      (is (zero? (get-in r [:h2 :total-unmet-kg])))
      ;; two exhausted (0 residual) + one partial (5 kg left)
      (is (= 3 (count (:residual r))))
      (is (close? 0.0 (:residual-h2-kg (nth (:residual r) 0))))
      (is (close? 0.0 (:residual-h2-kg (nth (:residual r) 1))))
      (is (close? 5.0 (:residual-h2-kg (nth (:residual r) 2)))))))

(deftest exhausted-spares-report-unmet-not-exception
  (testing "no spares: the shortfall interval is reported, mass conservation holds"
    (let [cap 10.0
          r (run [6.0 6.0] cap 0)]
      (is (= 1 (get-in r [:swaps :total-cartridges-consumed])))
      (is (zero? (get-in r [:intervals 0 :unmet-h2-kg])))
      ;; i1: 4 kg from the active cartridge, 2 kg unmet
      (is (close? 2.0 (get-in r [:intervals 1 :unmet-h2-kg])))
      (is (close? 2.0 (get-in r [:h2 :total-unmet-kg])))
      ;; conservation: total-kg (required, 12) = delivered (cap) + unmet (2)
      (is (close? 12.0 (get-in r [:h2 :total-kg])))
      (is (close? 12.0 (+ (get-in r [:h2 :delivered-kg])
                          (get-in r [:h2 :total-unmet-kg]))))
      (is (close? cap (get-in r [:h2 :delivered-kg])))
      (is (true? (get-in r [:swaps :spares-exhausted?])))
      ;; the cartridge that ran dry mid-mission is in the ledger; the
      ;; post-shortfall placeholder never delivered and is not
      (is (= 1 (count (:residual r))))
      (is (close? 0.0 (:residual-h2-kg (first (:residual r))))))))

(deftest mgh2-column-is-exact-stoichiometry
  (testing "mgh2 = h2 / w-h2 with the cartridge namespace's own weights"
    (let [r (run [1.0] 100.0)
          wh2 (get-in r [:provenance :w-h2-mgh2])
          wh2-cart (/ (* 2 (:h cart/molar-masses-g-per-mol))
                      (:mgh2 cart/molar-masses-g-per-mol))]
      (is (close? wh2 wh2-cart))
      (doseq [row (:intervals r)]
        (is (close? (/ (:h2-kg row) wh2) (:mgh2-kg row)))))))

(deftest provenance-and-unmeasured-carried
  (let [r (run [1.0] 10.0 1)]
    (is (close? fixture-eff (get-in r [:provenance :fc-elec-eff])))
    (is (= eff-src (get-in r [:provenance :eff-source])))
    (is (= cart-src (get-in r [:provenance :cartridge-source])))
    (is (= pt/LHV-H2-J (get-in r [:provenance :lhv-h2-j-per-kg])))
    (is (= :draw-down (get-in r [:provenance :swap-policy])))
    (is (= 1 (get-in r [:provenance :spare-cartridges])))
    (is (every? #(contains? (set (keys (:unmeasured r))) %)
                [:desorption-kinetics :heat-of-desorption :plateau-pressure
                 :swap-duration :swap-vent-losses]))))

(deftest zero-power-interval-consumes-nothing
  (let [r (run [0.0 0.0] 10.0)]
    (is (zero? (get-in r [:h2 :total-kg])))
    (is (zero? (get-in r [:h2 :total-e-kwh])))
    (is (zero? (get-in r [:swaps :count])))
    (is (zero? (get-in r [:swaps :total-cartridges-consumed]))
        "no cartridge has delivered H2 — the untouched full one is not counted")
    (is (zero? (count (:residual r))))))

(deftest fails-closed
  (testing "blank provenance strings"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 1.0
                             :fc-elec-eff fixture-eff :eff-source ""
                             :cartridge-h2-kg 10.0 :cartridge-source cart-src})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 1.0
                             :fc-elec-eff fixture-eff :eff-source eff-src
                             :cartridge-h2-kg 10.0 :cartridge-source " "}))))
  (testing "efficiency out of (0, 1]"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 1.0
                             :fc-elec-eff 1.5 :eff-source eff-src
                             :cartridge-h2-kg 10.0 :cartridge-source cart-src}))))
  (testing "non-positive cartridge capacity / dt"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 1.0
                             :fc-elec-eff fixture-eff :eff-source eff-src
                             :cartridge-h2-kg 0.0 :cartridge-source cart-src})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 0.0
                             :fc-elec-eff fixture-eff :eff-source eff-src
                             :cartridge-h2-kg 10.0 :cartridge-source cart-src}))))
  (testing "negative power and empty profile"
    (is (thrown? #?(:clj Exception :cljs js/Error) (run [-1.0] 10.0)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (run [] 10.0))))
  (testing "negative or fractional spare count"
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 1.0
                             :fc-elec-eff fixture-eff :eff-source eff-src
                             :cartridge-h2-kg 10.0 :cartridge-source cart-src
                             :spare-cartridges -1})))
    (is (thrown? #?(:clj Exception :cljs js/Error)
          (ss/swap-schedule {:fc-profile-kw [1.0] :dt-s 1.0
                             :fc-elec-eff fixture-eff :eff-source eff-src
                             :cartridge-h2-kg 10.0 :cartridge-source cart-src
                             :spare-cartridges 1.5})))))
