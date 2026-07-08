(ns vdesign.cad-bridge-test
  "vdesign.design/geometry-of -> vdesign.cad (BREP packaging envelope) ->
  vdesign.process (real kotoba-lang/cnc toolpath + G-code, kotoba-lang/cad
  artifact classification) exercised end to end. The closure-contract test
  suite's `release` helper predates `:geometry` and never sets it, so this
  suite runs through the real StateGraph (`vdesign.design/build`), whose
  `spec` always attaches `:geometry` (see design.cljc)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [vdesign.design :as design]
            [vdesign.proposer :as proposer]
            [vdesign.cad :as cad]
            [vdesign.process :as process]))

(defn- released-design [requirements powertrain]
  (let [actor (design/build)
        tid   (str "t/cad-bridge/" (gensym))
        _     (g/run* actor {:requirements requirements :powertrain powertrain} {:thread-id tid})
        res   (g/run* actor nil {:thread-id tid})]
    (get-in res [:state :design])))

(deftest geometry-of-is-populated
  (testing "a released spec carries wheelbase/floor/frontal-area geometry, not nil"
    (let [concept (proposer/propose {:class :sedan :range-km 500} :bev)
          geom    (design/geometry-of concept)]
      (is (pos? (:wheelbase-m geom)))
      (is (pos? (:floor-area-m2 geom)))
      (is (pos? (:frontal-area-m2 geom))))))

(deftest envelope-solid-scales-to-target-dims
  (testing "the evaluated BREP solid's bounding box matches envelope-dims-mm"
    (let [geometry {:wheelbase-m 2.9 :floor-area-m2 2.4 :frontal-area-m2 2.3}
          dims     (cad/envelope-dims-mm geometry)
          solid    (cad/envelope-solid geometry)
          pts      (map :point (:vertices solid))
          extent   (fn [axis] (- (apply max (map #(nth % axis) pts))
                                  (apply min (map #(nth % axis) pts))))]
      (is (= dims (:dims solid)))
      (is (< (Math/abs (- (extent 0) (:length-mm dims))) 1e-6))
      (is (< (Math/abs (- (extent 1) (:width-mm dims))) 1e-6))
      (is (< (Math/abs (- (extent 2) (:height-mm dims))) 1e-6)))))

(deftest envelope-mesh-tessellates
  (testing "tessellation produces a non-empty, triangle-aligned mesh"
    (let [mesh (cad/envelope-mesh (cad/envelope-solid {:wheelbase-m 2.9 :floor-area-m2 2.4
                                                        :frontal-area-m2 2.3}))]
      (is (pos? (count (:positions mesh))))
      (is (pos? (count (:indices mesh))))
      (is (zero? (mod (count (:indices mesh)) 3)) "indices are flat triangle triples"))))

(deftest process-plan-includes-envelope-buck-and-artifacts
  (testing "a released design's process plan machines a design-review buck
            from its packaging envelope and classifies every output file"
    (let [design (released-design {:class :sedan :range-km 500} :bev)
          p      (process/plan design)]
      (is (= :released (:status design)))
      (is (some? (:geometry design)))
      (is (some? (:envelope-buck p)))
      (is (str/includes? (get-in p [:envelope-buck :gcode]) "M30"))
      (is (pos? (get-in p [:envelope-buck :triangle-count])))
      ;; one classified artifact per CAM job's .nc file, plus the envelope .stl
      (is (= (inc (count (:cam p))) (count (:artifacts p))))
      (is (some #(= :mesh/stl (:artifact/id %)) (:artifacts p)))
      (is (every? #(= :toolpath/gcode (:artifact/id %))
                  (remove #(= :mesh/stl (:artifact/id %)) (:artifacts p)))))))

(deftest process-plan-without-geometry-skips-envelope-buck
  (testing "designs assembled without :geometry (older/hand-rolled callers)
            degrade gracefully instead of throwing"
    (let [design (dissoc (released-design {:class :sedan :range-km 500} :fcev) :geometry)
          p      (process/plan design)]
      (is (nil? (:envelope-buck p)))
      (is (= (count (:cam p)) (count (:artifacts p)))))))
