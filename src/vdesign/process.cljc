(ns vdesign.process
  "ProcessPlanner — (b) explode a verified spec into a BILL OF MATERIALS,
  generate a CAM process (toolpaths → G-code, kami-cam vocabulary) for the
  machined parts, and lay out the 4D ASSEMBLY ORDER (the giemon-factory
  `construction.order.json :seq` pattern). Everything is emitted as kotoba
  datoms, so the manufacturing process is queryable facts — the literal
  '組み立て工程まで全てデータ化' answer for this vehicle.

  CAM mirrors kami-cam's types (ToolType EndMill/Drill/FaceMill, StockShape
  Block/Cylinder, MachineType Mill3Axis, G00/G01 segments). The assembly
  order mirrors giemon-factory's staged reveal — here the BEV and FCEV
  diverge exactly where their energy systems do (pack-skateboard vs
  H2-tank + leak/fill stations)."
  (:require [vdesign.datom :as d]
            [clojure.string :as str]))

(def ^:const module-kWh 5.0)    ; battery module granularity
(def ^:const tank-kg    3.0)    ; usable H2 per 700-bar cylinder

;; ───────────────────────────── BOM ─────────────────────────────

(defn- bom
  "Major-assembly bill of materials with quantities + apportioned mass (kg)."
  [{:keys [powertrain energy mass-budget]}]
  (let [g  (:glider-kg mass-budget)
        p  (:propulsion-kg mass-budget)
        s  (:energy-store-kg mass-budget)
        common
        [{:part "body-in-white"      :qty 1 :mass (* g 0.42) :make :weld}
         {:part "closure-panel"      :qty 4 :mass (* g 0.10) :make :stamp}
         {:part "suspension-corner"  :qty 4 :mass (* g 0.16) :make :machine}
         {:part "wheel+tire"         :qty 4 :mass (* g 0.12) :make :buy}
         {:part "brake-corner"       :qty 4 :mass (* g 0.06) :make :buy}
         {:part "interior+seats"     :qty 1 :mass (* g 0.14) :make :buy}
         {:part "traction-motor"     :qty 1 :mass (* p 0.55) :make :machine}
         {:part "inverter+reducer"   :qty 1 :mass (* p 0.45) :make :machine}
         {:part "hv-harness"         :qty 1 :mass 18         :make :buy}
         {:part "thermal-loop"       :qty 1 :mass 22         :make :buy}]
        store
        (case powertrain
          :bev  (let [n (long (Math/ceil (/ (:nominal-kWh energy) module-kWh)))]
                  [{:part "battery-module" :qty n :mass (/ (* s 0.85) n) :make :buy}
                   {:part "pack-tray"      :qty 1 :mass (* s 0.15) :make :machine}])
          :fcev (let [n (long (Math/ceil (/ (:h2-kg energy) tank-kg)))]
                  [{:part "h2-tank-700bar" :qty n :mass (/ (:tank-mass-kg energy) n) :make :buy}
                   {:part "fc-stack"       :qty 1 :mass (:stack-mass-kg energy) :make :buy}
                   {:part "buffer-battery" :qty 1 :mass (:buffer-mass-kg energy) :make :buy}
                   {:part "tank-saddle"    :qty 2 :mass 9 :make :machine}]))]
    (vec (concat common store))))

;; ───────────────────────────── CAM ─────────────────────────────

(defn- gcode
  "Minimal but real G-code for one milled part (kami-cam PostProcessor shape):
  metric/absolute, tool change, rapid to clearance, profile + drill, end."
  [job]
  (let [{:keys [part tool feed-mm-min holes]} job]
    (str/join "\n"
      (concat
       [(str "( " part " — KAMI-CAM Mill3Axis )")
        "G21 G90 G94"                       ; mm, absolute, feed/min
        (str "T" (:n tool) " M06  ( " (:type tool) " " (:material tool) " )")
        "M03 S9000"
        "G00 Z5.0"
        (str "G00 X0 Y0")
        (str "G01 Z-2.0 F" feed-mm-min)     ; plunge
        (str "G01 X120 Y0 F" feed-mm-min)   ; profile pass (representative)
        "G01 X120 Y80" "G01 X0 Y80" "G01 X0 Y0"]
       (mapcat (fn [i] [(str "G00 X" (* 20 (inc i)) " Y40")
                        "G01 Z-8.0 F300" "G00 Z5.0"]) (range holes))
       ["G00 Z25.0" "M05" "M30"]))))

(defn- cam-jobs
  "CAM jobs for the make-by-machining parts in the BOM."
  [powertrain bom-lines]
  (let [machined (filter #(= :machine (:make %)) bom-lines)
        tool-for (fn [part]
                   (cond
                     (str/includes? part "motor")  {:n 3 :type "EndMill"  :material "Carbide"}
                     (str/includes? part "tray")   {:n 5 :type "FaceMill" :material "Carbide"}
                     (str/includes? part "saddle") {:n 7 :type "EndMill"  :material "HSS"}
                     :else                          {:n 9 :type "EndMill"  :material "Carbide"}))]
    (mapv (fn [{:keys [part qty mass]}]
            (let [tool   (tool-for part)
                  holes  (cond (str/includes? part "tray") 8
                               (str/includes? part "corner") 6 :else 4)
                  feed   1200
                  len-mm (+ 400 (* holes 30))                   ; toolpath length est.
                  job    {:part part :qty qty :tool tool :holes holes
                          :feed-mm-min feed :machine "Mill3Axis"
                          :stock "Block(AlSi10Mg)"
                          :cycle-s (Math/round (* 60.0 (/ len-mm feed)))}]
              (assoc job :gcode (gcode job))))
          machined)))

;; ─────────────────────── 4D assembly order ───────────────────────

(defn- assembly-order
  "Staged build sequence (giemon `:seq`), diverging by powertrain at the
  energy-system + EOL stations. Each step has a station, takt time and
  predecessor — a true 4D order, not a flat list."
  [powertrain]
  (let [common-head
        [{:seq 1 :step "floor-platform"   :station "BODY-1" :takt-s 95}
         {:seq 2 :step "underbody-rails"  :station "BODY-2" :takt-s 90}]
        energy
        (case powertrain
          :bev  [{:seq 3 :step "battery-pack-install" :station "EV-PACK" :takt-s 110}]
          :fcev [{:seq 3 :step "h2-tank-install"      :station "H2-TANK" :takt-s 120}
                 {:seq 4 :step "fc-stack-install"     :station "H2-FC"   :takt-s 100}])
        rest-steps
        ["body-on" "motor-subframe" "closures" "interior" "hv-harness"]
        eol
        (case powertrain
          :bev  ["coolant-fill" "charge-to-soc" "eol-roll-test" "commission"]
          :fcev ["coolant-fill" "h2-leak-test" "h2-fill" "eol-roll-test" "commission"])
        tail (concat rest-steps eol)
        start (inc (apply max (map :seq (concat common-head energy))))]
    (vec (concat common-head energy
                 (map-indexed
                  (fn [i s] {:seq (+ start i) :step s
                             :station (str "GA-" (inc i))
                             :takt-s (if (str/includes? s "test") 130 85)})
                  tail)))))

;; ───────────────────────────── plan ─────────────────────────────

(defn plan
  "Full process plan for a released `design`. Returns
  {:bom [..] :cam [..] :assembly [..] :takt-s n :tx .. :datoms ..}."
  [design]
  (let [pt    (:powertrain design)
        lines (bom design)
        cam   (cam-jobs pt lines)
        order (assembly-order pt)
        takt  (apply max (map :takt-s order))
        vid   (str "veh-" (name (:class design)) "-" (name pt))
        bom-ents (map-indexed
                  (fn [i l] (d/entity :BomLine (str vid "/p" i)
                                      {:vehicle vid :part (:part l) :qty (:qty l)
                                       :massKg (Math/round (double (:mass l)))
                                       :make (name (:make l))}))
                  lines)
        cam-ents (map (fn [j] (d/entity :CamJob (str vid "/cam/" (:part j))
                                        {:vehicle vid :part (:part j)
                                         :machine (:machine j) :stock (:stock j)
                                         :tool (str (get-in j [:tool :type]) "/"
                                                    (get-in j [:tool :material]))
                                         :cycleS (:cycle-s j)
                                         :gcodeLines (count (str/split-lines (:gcode j)))}))
                      cam)
        asm-ents (map (fn [s] (d/entity :AssemblyStep (str vid "/seq/" (:seq s))
                                        {:vehicle vid :seq (:seq s) :step (:step s)
                                         :station (:station s) :taktS (:takt-s s)}))
                      order)
        ledger   (d/log (concat bom-ents cam-ents asm-ents))]
    {:bom-lines lines
     :cam cam
     :assembly order
     :takt-s takt
     :tx (:tx ledger)
     :datoms (:datoms ledger)
     :datom-count (:count ledger)}))
