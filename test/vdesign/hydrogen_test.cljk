(ns vdesign.hydrogen-test
  (:require [clojure.test :refer [deftest is testing]]
            [vdesign.hydrogen :as h]
            [vdesign.dc-bus :as dc]
            [vdesign.cartridge :as cartridge]
            [vdesign.powertrain :as pt]))

(defn- close? [a b] (< (Math/abs (- a b)) 1e-9))

(defn- throws? [f]
  (try (f) false
       (catch #?(:clj Exception :cljs js/Error) _ true)))

(deftest lhv-conversion-identity
  (testing "1 kWh electric at eta=0.5 → h2 = 3.6e6 J / (0.5 · LHV) kg"
    (let [r (h/consumption-profile {:fc-profile-kw [1.0] :dt-s 3600.0
                                    :fc-elec-eff 0.5})]
      (is (close? (/ 3.6e6 (* 0.5 h/LHV-H2-J))
                  (get-in r [:intervals 0 :h2-kg])))
      (is (close? (get-in r [:h2 :total-kg]) (get-in r [:intervals 0 :h2-kg])))
      ;; zero power consumes nothing
      (let [z (h/consumption-profile {:fc-profile-kw [0.0] :dt-s 3600.0
                                      :fc-elec-eff 0.5})]
        (is (close? 0.0 (get-in z [:h2 :total-kg])))))))

(deftest eff-is-required-and-bounded
  (testing "no default efficiency; out-of-range efficiencies are refused"
    (is (throws? #(h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0})))
    (is (throws? #(h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0
                                          :fc-elec-eff 0.0})))
    (is (throws? #(h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0
                                          :fc-elec-eff 1.1})))
    (is (throws? #(h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0
                                          :fc-elec-eff -0.5})))
    ;; eta = 1.0 exactly is allowed (identity bound)
    (is (map? (h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0
                                      :fc-elec-eff 1.0})))))

(deftest mgh2-stoichiometry-matches-cartridge
  (testing "MgH2 mass is exact stoichiometry, same arithmetic as cartridge"
    (let [r (h/consumption-profile {:fc-profile-kw [10.0 20.0] :dt-s 10.0
                                    :fc-elec-eff 0.53})
          wh2 (/ (* 2 (:h cartridge/molar-masses-g-per-mol))
                 (:mgh2 cartridge/molar-masses-g-per-mol))]
      (doseq [row (:intervals r)]
        (is (close? (/ (:h2-kg row) wh2) (:mgh2-kg row))))
      ;; the shared provenance constant really is the powertrain LHV
      (is (= pt/LHV-H2-J h/LHV-H2-J))
      (is (close? wh2 (get-in r [:provenance :w-h2-mgh2]))))))

(deftest cartridge-depletion-is-reported-not-thrown
  (testing "store exhausts at the right interval; shortfall clamps, not errors"
    (let [;; demand far beyond a tiny store
          r (h/consumption-profile {:fc-profile-kw [10.0 10.0 10.0 10.0]
                                    :dt-s 100.0
                                    :fc-elec-eff 0.5
                                    :store-h2-kg 0.01})
          per (get-in r [:intervals 0 :h2-kg])]
      (is (pos? per))
      (is (= 0 (get-in r [:store :depleted-at-i])))
      (is (close? 0.0 (get-in r [:store :end-kg])))
      (is (close? (* 4 per) (get-in r [:h2 :total-kg])))
      (is (close? (- (* 4 per) 0.01) (get-in r [:h2 :total-shortfall-kg])))
      ;; conservation: total = delivered + shortfall
      (is (close? (get-in r [:h2 :total-kg])
                  (+ (- 0.01 (get-in r [:store :end-kg]))
                     (get-in r [:h2 :total-shortfall-kg]))))
      (every? #(is (>= % 0.0)) (map :store-remaining-kg (:intervals r)))))
  (testing "a store that lasts the whole cycle never flags depletion"
    (let [r (h/consumption-profile {:fc-profile-kw [5.0 5.0] :dt-s 60.0
                                    :fc-elec-eff 0.53
                                    :store-h2-kg 10.0})]
      (is (nil? (get-in r [:store :depleted-at-i])))
      (is (close? (- 10.0 (get-in r [:h2 :total-kg]))
                  (get-in r [:store :end-kg]))))))

(deftest composes-with-dc-bus-split
  (testing "consumption-for-split: fc energy matches the split's own total"
    (let [split (dc/power-split {:demand-profile-kw [10.0 30.0 5.0 0.0 30.0]
                                 :dt-s 1.0 :fc-max-kw 20.0
                                 :buffer-capacity-kwh 1.5})
          r (h/consumption-for-split split {:fc-elec-eff 0.53})]
      ;; every split interval's fc output is carried over unchanged
      (is (= (count (:intervals split)) (count (:intervals r))))
      (is (every? (fn [[a b]] (close? a (:fc-kw b)))
                  (map vector (map :fc-kw (:intervals split))
                       (:intervals r))))
      ;; Σ e-kwh equals the split's own fc-out energy (same dt, same kW)
      (is (close? (get-in split [:energy :fc-out-kwh])
                  (get-in r [:h2 :total-e-kwh])))
      (is (close? 1.0 (get-in split [:provenance :dt-s])))))
  (testing "non-split input is refused"
    (is (throws? #(h/consumption-for-split {:intervals []} {})))
    (is (throws? #(h/consumption-for-split {} {})))))

(deftest unmeasured-and-provenance-reported
  (testing "flat-eff bookkeeping never masquerades as a reactor model"
    (let [r (h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0
                                    :fc-elec-eff 0.53 :store-h2-kg 1.0})]
      (is (every? #(contains? (set (keys (:unmeasured r))) %)
                  [:fc-eff-operating-point :desorption-kinetics
                   :heat-of-desorption :plateau-pressure
                   :min-h2-recirculation]))
      (is (close? 0.53 (get-in r [:provenance :fc-elec-eff])))
      (is (close? 1.0 (get-in r [:provenance :store-h2-kg]))))))

(deftest input-validation
  (testing "refusals mirror the dc-bus contract's discipline"
    (is (throws? #(h/consumption-profile {:fc-profile-kw [] :dt-s 1.0
                                          :fc-elec-eff 0.5})))
    (is (throws? #(h/consumption-profile {:fc-profile-kw [-1.0] :dt-s 1.0
                                          :fc-elec-eff 0.5})))
    (is (throws? #(h/consumption-profile {:fc-profile-kw [1.0] :dt-s 0.0
                                          :fc-elec-eff 0.5})))
    (is (throws? #(h/consumption-profile {:fc-profile-kw [1.0] :dt-s 1.0
                                          :fc-elec-eff 0.5
                                          :store-h2-kg -2.0})))))
