# vehicle-design-actor

A **clean-sheet vehicle design** actor — given requirements (class, range,
payload, top speed) it designs a car *from zero* and sizes its energy
system for **either a battery-electric (BEV) or a hydrogen fuel-cell
(FCEV)** powertrain. Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) — the same runtime the
[`robotaxi-actor`](../robotaxi-actor) rides on.

> **Why an actor layer at all?** A generative design proposer (LLM /
> CAD agent) is a brilliant *concept generator* but it does **not respect
> conservation laws** — it will happily target a 1500 km city car at
> 1100 kg. This project seals the proposer into a single node and wraps
> it with an independent **PhysicsGovernor** that closes the mass spiral,
> balances energy, and checks packaging — and **rejects** any concept
> that does not close.

It is the design-side mirror of the robotaxi actor: there, a research
VLA is sealed behind a SafetyGovernor that can reject any *trajectory*;
here, a concept generator is sealed behind a PhysicsGovernor that can
reject any *design*.

## The core contract

```
requirements + powertrain (:bev | :fcev)
        │
        ▼
   ┌──────────────┐  concept (untrusted)  ┌──────────────────┐
   │ DesignProposer│ ────────────────────▶ │ PhysicsGovernor  │ (independent)
   │  (sealed)     │  target mass · power  │ mass closure ·   │
   └──────────────┘                        │ energy · package │
                                           └────────┬─────────┘
                                      closes ◀──────┴──────▶ infeasible → rejected
                                        │
                                 engineer sign-off  [interrupt]
                                        │
              (a) ┌───────────────────────────────────┐
                  │ SimGovernor — genesis/cae-compat   │ structural · clash · axle
                  │ + kami-shugyo-style per-env DR      │ survives? ◀──▶ rejected
                  └──────────────┬────────────────────┘
                                 │ survives
              (b) ┌───────────────────────────────────┐
                  │ ProcessPlanner — BOM → CAM (G-code) │ → kotoba Datom log
                  │ → 4D assembly order (giemon :seq)   │   (verification + mfg facts)
                  └──────────────┬────────────────────┘
                                 ▼  released spec + process, all datafied
```

**The proposer never ships a design the PhysicsGovernor hasn't closed.**
That single invariant is what lets a bold concept generator produce a
manufacturable spec — and the **mass spiral** (heavier car → bigger store
→ heavier car …) is solved as a bounded fixed-point inside one graph run.

## Run

```bash
clojure -M:dev:run     # clean-sheet a sedan as BEV and FCEV, then reject an over-reach
clojure -M:dev:test    # the closure contract as executable tests
```

Demo output clean-sheets the **same** 500 km sedan as a BEV (~67 kWh,
~1480 kg) and as an FCEV (~3 kg H₂, ~1230 kg — lighter, but tank-volume
limited), then pushes a **1500 km city BEV** whose mass spiral runs away
→ the PhysicsGovernor rejects it (the design-side Minimal Risk Condition).

## BEV vs FCEV — where the design splits

Everything upstream of the energy store is **propulsion-agnostic** (one
glider, one road-load model). Only `vdesign.powertrain` branches:

| | BEV | FCEV |
|---|---|---|
| Store | Li-ion pack (175 Wh/kg, 0.45 kWh/L pack-level) | 700-bar H₂ tank (5.5 wt% system) + FC stack + buffer |
| Path η (source→wheel) | ~0.84 (battery→wheel · regen) | ~0.40 (H₂ LHV → stack 0.53 → wheel) |
| Sized from | nominal kWh = range · consumption / DoD | H₂ kg = range · consumption / LHV |
| Tends to be limited by | **mass** (pack is heavy) | **volume** (H₂ tanks are bulky) |

Both feed the **same** mass-closure spiral, so the two architectures fall
out of identical requirements as physically distinct vehicles.

## Layout

| File | Actor / role |
|---|---|
| `src/vdesign/proposer.cljc` | **DesignProposer** — the contained concept generator (heuristic mock) |
| `src/vdesign/physics.cljc` | **PhysicsGovernor** — mass closure · energy balance · packaging gates |
| `src/vdesign/powertrain.cljc` | BEV / FCEV energy-system models (the only powertrain branch) |
| `src/vdesign/simverify.cljc` | **(a) SimGovernor** — structural · clash · axle on the Isaac/genesis-compat surface + per-env DR |
| `src/vdesign/process.cljc` | **(b) ProcessPlanner** — BOM → CAM (G-code) → 4D assembly order |
| `src/vdesign/datom.cljc` | kotoba Datom-log (EAVT) representation — verification + mfg as facts |
| `src/vdesign/design.cljc` | **VehicleDesignActor** — the langgraph-clj StateGraph (1 run = 1 design pass) |
| `src/vdesign/sim.cljc` | demo driver |
| `test/vdesign/closure_contract_test.clj` | the closure + verify + process invariants, executable |

## (a) Simulate, (b) datafy the build — the kami-engine bridge

A closed spec is not a finished design. Two downstream stages connect it to
the workspace's simulation and manufacturing fabric:

- **(a) `simverify` → Isaac / Cosmos sim.** The released spec is verified
  for crash-load paths, package clash and axle loading against the
  **kami-genesis** (`isaacsim.core.api`) / **kami-cae** surface, then
  hardened with **kami-shugyo-style per-env domain randomization** (mass
  ±8 %, structure ±6 %, crash pulse ±10 %) for a sim-to-real margin — the
  same clean-room Isaac-compat stack kami-engine matured in ADR-0034. A
  structural failure downgrades even a physics-closed design to *rejected*
  (a second governor). The DR batch is seeded, so verdicts reproduce.

- **(b) `process` → datafied assembly.** The verified spec explodes into a
  **BOM**, a **CAM** process (toolpaths → **G-code**, in kami-cam's
  vocabulary), and a **4D assembly order** (the giemon-factory
  `construction.order.json :seq` pattern) — where the BEV and FCEV lines
  diverge exactly at their energy systems (`battery-pack-install` /
  `charge-to-soc` vs `h2-tank-install` / `h2-leak-test` / `h2-fill`).

Both stages write **kotoba datoms** (EAVT, Datomic-isomorphic — the same
log the `nvidia_cosmos-compat` / `nvidia_isaac-compat` actors speak), so
"why is station 4 the bottleneck?" or "which parts share tool T-03?" is a
Datalog query, not a spreadsheet. **Yes — the assembly process is datafied,
down to the G-code and the takt time.**

## Status

Working reference model. The proposer is a deterministic heuristic and the
physics constants in `vdesign.powertrain/tech` reflect ~2026 production
technology — retune there as cells/tanks improve, or swap the proposer for
a real generative-CAD / LLM node behind the same StateGraph boundary.
See [`docs/DESIGN.md`](docs/DESIGN.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md).
