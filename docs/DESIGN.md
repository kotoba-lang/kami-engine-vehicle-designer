# Vehicle Design Actor — a concept generator sealed behind physics

## 1. Premise: what a design proposer is, and what it is missing

A generative vehicle-design agent — an LLM or generative-CAD model —
is extraordinary at *spanning the concept space*: body style, packaging,
powertrain layout, a target curb mass that hits the marketing brief. What
it is **not** is a physics engine. Ask it for a 1500 km city car and it
will gladly hand you a 1100 kg concept, because nothing in its objective
forces the energy store to actually fit the mass and volume budget it
just wrote down.

So the design problem is **not** "use a model to design a car." It is
**"how do we confine the proposer inside a trust boundary"** so that bold
concepts can be explored but only *physically closed* designs leave the
actor as a spec. Everything below follows from that — and it is the exact
mirror of the [robotaxi actor](../../robotaxi-actor), where a research VLA
is confined behind a SafetyGovernor.

| robotaxi-actor | vehicle-design-actor |
|---|---|
| Alpamayo-R1 proposes a **trajectory** | DesignProposer proposes a **concept** |
| SafetyGovernor verifies against the **ODD + collision check** | PhysicsGovernor verifies against **conservation laws** |
| reject → **MRC** (safe stop) | infeasible → **rejected spec** (the design-side MRC) |
| teleop sign-off (`interrupt-before`) | engineer sign-off (`interrupt-before`) |
| one run = one ~100 ms **planning tick** | one run = one **design pass** |

## 2. Actor topology

```
VehicleDesignActor (one design = one supervised run)
│
├── DesignProposer ……… concept generator (LLM / gen-CAD). PROPOSAL ONLY.
│
├── PhysicsGovernor ……… INDEPENDENT censor — conservation laws
│     ├── mass closure …… solve the mass spiral to a fixed point
│     ├── energy balance … road load → store size (per powertrain)
│     ├── packaging ……… store volume ≤ envelope
│     └── gross / fraction  GVWR + store-mass-fraction gates
│
├── Powertrain models …… :bev (pack) | :fcev (tank + stack + buffer)
│     the ONLY part of the design that branches on powertrain
│
└── Design review ……… human engineer sign-off before release (interrupt)
```

Principles (deliberately the same three as robotaxi):

1. **The proposer is the lowest-trust node and never emits a spec
   directly.** Its concept is always censored by the PhysicsGovernor.
2. **Closure or fall-back.** A concept that does not close falls back to a
   *rejected spec* carrying the violated gates — the design analogue of an
   MRC. Nothing infeasible is ever released.
3. **Everything is checkpointed** (Datomic in prod, in-mem in dev), so
   "why is this car 1480 kg?" is a query over the audit ledger: proposal →
   mass-spiral history → verdict → emit.

## 3. The mass spiral — why this needs a solver, not a formula

A car's energy store is sized for its range, but a bigger store makes the
car heavier, which raises the energy it burns per km, which demands a
bigger store. For feasible designs this is a contraction and converges in
a handful of iterations:

```
curb₀ = proposer's optimistic guess
repeat:
  store   = size(powertrain, operating_mass = curb + occupants, range)
  curb'   = glider + store.mass + motor
until |curb' − curb| < 1 kg     →  CONVERGED (fixed point)
   or  curb' > 1.05 · GVWR       →  DIVERGED  (mass-spiral runaway → reject)
```

For over-reach (too much range for the cell/tank technology) the map is
**not** a contraction — curb climbs every iteration. That divergence is
the governor's reject signal: it is *physically meaningful*, not a tuning
artifact. The whole loop is bounded (≤60 iterations) and encapsulated in
one graph-node call, so one graph run is still one auditable design pass.

## 4. BEV vs FCEV — same mission energy, two stores

Road load (rolling + aero, cycle-blended) gives the **mechanical** energy
at the wheels per km — identical for both powertrains. The split is only
in how that energy is stored and the path efficiency back to it:

- **BEV.** `nominal_kWh = range · consumption / DoD`, where
  `consumption = wheel_energy / η(battery→wheel) + aux`. Pack mass =
  `nominal_kWh / 175 Wh·kg⁻¹`, volume = `nominal_kWh / 0.45 kWh·L⁻¹`.
  Tends to be **mass-limited** — the pack is the heaviest single item.

- **FCEV.** `H2_kg = range · consumption_LHV / LHV`, where the electric
  demand is up-converted through the fuel cell (`η ≈ 0.53`). Tank-system
  mass = `H2_kg / 0.055` (700-bar type-IV gravimetric), plus stack
  (`P / 2 kW·kg⁻¹`) and a small buffer battery. Tends to be
  **volume-limited** — compressed H₂ is ~0.04 kg/L and the tanks are bulky.

That is why, from one 500 km sedan brief, the actor returns a heavier-but-
compact BEV and a lighter-but-volume-tight FCEV — the trade is structural,
and the governor surfaces it as concrete margins (kg to GVWR, L to
envelope, store mass fraction).

## 5. Constants & extension points

All physics lives in `vdesign.powertrain/tech` (pack Wh/kg, FC efficiency,
H₂ gravimetric fraction, …) and `vdesign.proposer/classes` (glider mass,
Cd·A, package envelope per class). They reflect ~2026 production figures
and are the single place to retune. The DesignProposer is a deterministic
heuristic here; swapping it for a real generative-CAD or LLM node requires
no change to the governor or the graph — the trust boundary is the
`:propose → :govern` edge, and that is the whole point.
