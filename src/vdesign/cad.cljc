(ns vdesign.cad
  "CAD bridge — turns a released design's `:geometry` (see
  `vdesign.design/geometry-of`) into a coarse BREP packaging envelope via
  `kotoba-lang/brep`'s parametric feature tree, then tessellates it for
  downstream CAM (`vdesign.process`) and `kotoba-lang/cad`'s artifact
  registry.

  Honest scope: this is a PACKAGING ENVELOPE — a bounding-box
  approximation of the glider volume (length × width × height) — not a
  styled body surface. `brep.feature/evaluate` currently only realizes an
  `:extrude` `:operation :new` as a fixed ±0.5-unit-square cross-section
  extruded along the given direction/distance (sketch entities are not
  yet consumed by `evaluate`; revolve/fillet/chamfer/boolean are
  documented not-yet-implemented in kotoba-lang/brep). So the cross-
  section here is realized at unit scale, then the resulting vertices are
  scaled non-uniformly to the target dimensions — a documented
  work-around for the kernel's current maturity, not a hidden one."
  (:require [brep.feature :as feat]
            [brep.tessellate :as tess]))

(def ^:const assumed-height-m
  "Typical passenger-vehicle height, used only to split frontal-area into
  width × height (frontal-area alone can't determine both)."
  1.5)

(def ^:const overhang-ratio
  "length ≈ wheelbase × overhang-ratio (front + rear overhang allowance)."
  1.15)

(defn envelope-dims-mm
  "Derive {:length-mm :width-mm :height-mm} from a design's `:geometry`
  map (`:wheelbase-m`, `:frontal-area-m2`)."
  [{:keys [wheelbase-m frontal-area-m2]}]
  (let [height-m (min assumed-height-m (Math/sqrt (max frontal-area-m2 1e-3)))
        width-m  (/ frontal-area-m2 height-m)
        length-m (* wheelbase-m overhang-ratio)]
    {:length-mm (* length-m 1000.0)
     :width-mm  (* width-m 1000.0)
     :height-mm (* height-m 1000.0)}))

(defn- scale-point [[x y z] sx sy sz]
  [(* x sx) (* y sy) (* z sz)])

(defn envelope-solid
  "Build a single-sketch/extrude BREP feature tree sized to `geometry`
  and evaluate it. Returns {:solid :edges :vertices :dims}. Throws
  ex-info if the feature tree fails to evaluate (it always succeeds for
  this single-extrude case, per brep.feature/evaluate's documented
  base-feature support)."
  [geometry]
  (let [{:keys [length-mm width-mm height-mm] :as dims} (envelope-dims-mm geometry)
        ;; sketch on XY (the footprint plane); extrude along Z by height-mm
        ;; (matches the CNC convention downstream: Z is the mill's depth
        ;; axis, XY is the table plane).
        sketch  (feat/sketch-feature 1 (feat/sketch-plane-xy) [])
        extrude (feat/extrude-feature 2 1 [0.0 0.0 1.0] height-mm :new)
        tree    (-> (feat/feature-tree)
                    (feat/add-feature sketch)
                    (feat/add-feature extrude))
        [status result] (feat/evaluate tree)]
    (when (not= status :ok)
      (throw (ex-info "brep envelope evaluation failed" {:result result :geometry geometry})))
    (let [[solid edges vertices] result
          scaled (mapv #(update % :point scale-point length-mm width-mm 1.0) vertices)]
      {:solid solid :edges edges :vertices scaled :dims dims})))

(defn envelope-mesh
  "Tessellate an `envelope-solid` result into {:positions [[x y z] ...]
  :indices [i0 i1 i2 ...]} — the shape `kotoba.cam.stock/from-mesh`
  expects (positions, flat triangle index list)."
  [{:keys [solid edges vertices]}]
  (let [[positions indices] (tess/tessellate-solid solid edges vertices)]
    {:positions positions :indices indices}))
