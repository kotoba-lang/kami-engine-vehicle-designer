(ns vdesign.datom
  "Kotoba Datom-log representation for design outputs — the same EAVT,
  Datomic-isomorphic shape the nvidia_cosmos-compat / nvidia_isaac-compat
  actors speak (ADR-2605262130 + ADR-2605312345). This is the bridge that
  makes a vehicle's verification AND its manufacturing process queryable
  facts rather than opaque blobs: 'why is station 4 the bottleneck?' or
  'which parts share tool T-03?' become Datalog over these tuples.

  Two views, from one entity map:
    • `entity`  — a Datomic transaction map  {:ns.Kind/id .. :ns.Kind/a ..}
    • `eavt`    — flat [e a v] tuples, the Datom-log view ('datom 化')

  No external DB: in this actor we just carry the tx-maps + tuples on the
  StateGraph channels; a production run transacts them through the same
  `DatomPort` seam (kotoba-kqe) the compat actors use."
  (:require [clojure.string :as str]))

(defn entity
  "Build a Datomic-style entity (tx-map) for `kind` with stable `id`.
  Keys are namespaced :vdesign.<Kind>/<attr>; nil attrs are dropped."
  [kind id attrs]
  (let [k (name kind)]
    (into {(keyword "vdesign" (str k "/id")) id}
          (for [[a v] attrs :when (some? v)]
            [(keyword "vdesign" (str k "/" (name a))) v]))))

(defn eavt
  "Flatten an entity tx-map into [e a v] Datom tuples, keyed by its /id —
  the literal Datom-log view of the same facts."
  [ent]
  (let [id-attr (first (filter #(str/ends-with? (name %) "/id") (keys ent)))
        e       (get ent id-attr)]
    (mapv (fn [[a v]] [e a v]) (dissoc ent id-attr))))

(defn log
  "Collect a seq of entity tx-maps into {:tx [..] :datoms [..] :count n} —
  what a node returns onto the :datoms channel."
  [entities]
  (let [ents (vec (remove nil? entities))]
    {:tx     ents
     :datoms (vec (mapcat eavt ents))
     :count  (reduce + 0 (map (comp count eavt) ents))}))
