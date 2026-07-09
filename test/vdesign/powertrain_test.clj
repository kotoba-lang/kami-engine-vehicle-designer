(ns vdesign.powertrain-test
  "size-fcev's mass-breakdown arithmetic: :tank-mass-kg is already the FULL
  tank-system mass (grav-frac = usable-H2 / tank-system-mass), so
  :store-mass-kg / :mass-kg must not add h2-kg a second time."
  (:require [clojure.test :refer [deftest is testing]]
            [vdesign.proposer :as proposer]
            [vdesign.powertrain :as pt]))

(defn- glider [c]
  (select-keys c [:crr :cd :frontal-area :avg-speed :glider-mass
                  :gross-limit :p-aux-w]))

(deftest fcev-store-mass-does-not-double-count-h2
  (testing "store-mass-kg equals tank-mass-kg (H2 is already inside the tank
            mass via grav-frac), not tank-mass-kg + h2-kg"
    (let [concept (proposer/propose {:class :sedan :range-km 500} :fcev)
          store   (pt/size-fcev (glider concept) concept (:glider-mass concept))]
      (is (pos? (:h2-kg store)))
      (is (= (:tank-mass-kg store) (:store-mass-kg store))
          "a fully-loaded H2 tank's mass IS the store mass -- no separate fuel add-on")))
  (testing "mass-kg is the sum of store + propulsion, not store + h2-kg + propulsion"
    (let [concept (proposer/propose {:class :sedan :range-km 500} :fcev)
          store   (pt/size-fcev (glider concept) concept (:glider-mass concept))]
      (is (= (:mass-kg store)
             (+ (:store-mass-kg store) (:propulsion-mass-kg store)))))))
