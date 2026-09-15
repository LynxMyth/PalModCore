# Changelog

All notable changes to Palmod are documented in this file. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows
[SemVer](https://semver.org/) with a pre-`1.0.0` "beta" understanding — breaking changes to
datapack schemas or save data may still happen between minor versions until `1.0.0`.

## [0.9.3] - 2026-08-28

The Pal Work Station is now a plain craftable chest, and worker pals carry their own sphere.

### Added

- **Work station crafting recipe** (`recipes/pal_work_station.json` — planks ring + chest + iron)
  and loot table, so the station drops itself (and its contents) when broken.

### Changed

- **Pal Work Station is a plain 27-slot chest** (`BaseContainerBlockEntity` + `WorldlyContainer`):
  vanilla ChestMenu UI, comparator output, `InvWrapper` item-handler cap, hoppers on every face.
  It binds to no worker and has no ticker or sphere NBT, so breaking it is harmless. A static
  per-dimension index of loaded stations (`onLoad`/`setRemoved`) backs `findNearest`. Old 1-slot
  stations and their bound sphere migrate once automatically.
- **Worker pals are station-free** (`AbstractStationWorkerGoal`). The work anchor is `PalWorkHome`
  (where the pal was thrown); a full/refused/idle carry goes to the NEAREST station with room, and
  with none in range the pal just holds its load. The sorter drains the nearest station, scanning
  candidate containers once per probe instead of per slot.
- **Worker spheres travel with the pal.** Summoning a `station_mode` pal consumes the sphere and
  stamps it into the pal (`PalCarriedSphere`), so workers are orphan-exempt and keep running while
  the owner is away or offline. Right-clicking the pal recalls it into that sphere; death drops an
  empty sphere where it fell.
- AM hive NBT (`Leaf`/`HivePos`) is scrubbed on every summon, not just on the old station path.

## [0.9.2] - 2026-07-28

### Fixed

- **Hard crash on client load** (`ResourceLocationException` from `SoundManager`). The raw mesh
  source shipped at `assets/PalsphereMesh/`, an invalid namespace (uppercase). The source art moved
  to `art/pal_sphere_mesh/`, outside the jar; the shipped mesh assets already lived under `palmod`.

## [0.9.1] - 2026-07-21

A visual/feel update for catching, plus two catch-result fixes.

### Added

- **Pokéball-style catch animation** (`PalSphereProjectile` capture state machine). A sphere that
  hits a catchable mob no longer resolves instantly: the mob shrinks into the ball, the ball
  wobbles in place for **0.3s per 10% catch chance**, then reveals the result — on success the mob
  vanishes and a filled sphere pops out where the mob was; on failure the mob grows back to full
  size and the empty ball drops (50% destroyed, 5% with Infinite). The mob-shrink is rendered
  client-side (`client.PalCaptureClient`, `RenderLivingEvent`) off the projectile's synced capture
  state — no custom packet. The mob is frozen and damage-immune during the animation, with a
  safety net (`ForgeEvents`) that un-freezes any capture orphaned by a chunk unload.
- **3D voxel mesh for the thrown sphere** (`client.PalSphereMeshRenderer` + `PalSphereMeshClient`).
  The thrown ball now renders as its `PalsphereMesh` OBJ model (Level1/2/3 by sphere tier, via
  Forge's `forge:obj` loader) instead of the flat item billboard. Client-side; tuning constants
  (scale / vertical center / spin) live in the renderer.

### Changed

- **A caught pal now keeps the wild mob's full stats and comes out at full health.** `resolveSuccess`
  heals the mob to its (buffed) max before serializing instead of stripping the wild toughness to
  base — so the pal's max HP equals the mob's and it never summons near-death.
  (`WildCatchManager.stripWildToughness` still exists but is no longer called.)
- **Infinite enchant returns the ball 100% on a SUCCESSFUL catch** — the pal is in the filled
  sphere and the empty ball goes back to the catcher, so an Infinite sphere is effectively reusable.

## [0.9.0] - 2026-07-20

A survival-pressure release: the wild is denser and far deadlier, catching is a real
weaken-then-catch fight (tanky mobs finally catchable), pals recover between fights, and nights
can turn into a hunt. All numbers are config-driven in `palmod-common.toml` (restart to apply).

### Added

- **Well-fed self-heal for pals** (`ForgeEvents.tryWellFedHeal`). A non-deployed pal at or above
  `palHealWellFedThreshold` (80) hunger regenerates health, scaling with fullness and mood, up to
  `palHealPerSecond` (2) HP/s and burning `palHealHungerCostPerHp` (0.5) hunger per HP so it
  self-limits. Per-mob override via `stats.heal_per_second`. Guarded against reviving a pal that is
  mid-death-animation.
- **Wild toughness for every catchable mob** (`WildCatchManager.applyWildToughness`). Catchable
  wild mobs become bruisers: `wildHealthMultiplier` (×4.5 HP), `wildDamageMultiplier` (×2.0 attack
  where present), and self-regen (`wildRegenFractionPerSecond`, suppressed `wildRegenSuppressTicks`
  after a hit) — so the fight to weaken a mob is the real challenge. Stripped on capture, so a
  tamed pal keeps its true base stats and never summons near-death.
- **Hunting Night event** (`hunt.HuntingNightManager` + `ai.HuntNightAttackGoal`). Each night has a
  `huntNightChance` (0.10) to become a hunt: a warning broadcasts ~5 min before dusk
  (`huntWarningLeadTicks`), then every mob — even normally-passive ones — turns on players (passives
  deal zombie-level `huntMinDamage`, attackers deal `×huntDamageMultiplier`), extra mobs spawn in
  waves (`huntSpawnPool`) around players, and at dawn the hunt ends and its spawns despawn. Fully
  config-driven (`huntEnabled` master switch).
- **Biome-distributed spawns for the Alex's Mobs roster** (`data/palmod/forge/biome_modifier/`,
  17× `forge:add_spawns`). The shipped roster is spread across biome groups (grizzly→taiga,
  capuchin/gorilla/ant→jungle, orca→cold ocean, mungus→mushroom, End/Nether mobs in their realms,
  …) at moderate weights so no region is empty and species must be sought out.
- **Feeder crafting recipe** (`recipes/pal_feeder.json` — planks ring + hopper); the feeder was
  previously uncraftable. (The work station stays deliberately pal-bound-only, never a standalone
  item.)

### Changed

- **Catch formula rebuilt to a fraction-based curve** (`PalSphereProjectile`). Replaces the old
  `level − maxHP + 0.7×%lost` subtraction — which made high-HP mobs (Warden, iron golem, …)
  uncatchable even at ~0 HP — with `catchLowHpMaxRate × weaken^p`, where the toughness exponent `p`
  grows with base max health. Guarantees: every mob reaches ~`catchLowHpMaxRate` (0.90) at/below
  `catchFullRateHpFraction` (~5% HP), tankier mobs are strictly harder at mid HP but never a hard
  0%, and full-HP mobs stay near-0% (weaken-first preserved). Charges wild-toughness-stripped base
  HP so the ×4.5 buff never compounds catch difficulty. Verified in-game by catching a Warden.
- **`catchableChance` default 0.40 → 0.65** — more of the wild is catchable.
- **Cheaper sphere recipes**: `pal_sphere` = 4 iron + 1 redstone; `pal_sphere_mid` = 4 iron + 1
  redstone block; `pal_sphere_advanced` = 8 iron + 1 redstone block (was 8-iron / gold / diamond
  rings).

## [0.8.1] - 2026-07-14

### Added

- **Addon extensibility overhaul.** Palmod is now built to be extended by third-party mods
  without ever touching its source:
  - `CombatAbilityRegistry` and `WildDefenseRegistry` replace the hardcoded
    `combat_ability.type` switch and the `wild.type` (op/predator_locked) if-chains — a new
    ability or defense is one `register(...)` call.
  - `PalAbilityRegistry` converts the "one proactive power per mob" family (clone, fetch,
    magnet, xp_collector, greedy_boom, warp_beacon, time_stop.on_summon) from scattered
    if-chains into a single registry with an `appliesTo`/`onJoin`/`tick`/`onSummon`/
    `forcedDeployMode` hook interface.
  - New `com.mx.palmod.api.event` package: `PalCaughtEvent` (cancelable), `PalSummonedEvent`,
    `PalRecalledEvent`, `PalDiedEvent`, `PalDeployedEvent` — standard Forge events any addon
    can subscribe to for a pal's core lifecycle moments.
  - Deliberately left alone: deploy mode (anchor/sentry) selection, `wild.category`, and
    interact-click routing — dense, stable UX/dispatch logic where the regression risk
    outweighed the addon payoff.
- Mod logo (`palmod.png`, `mods.toml` `logoFile`).

## [0.8.0] - 2026-07-14

First tracked release. Palmod's core loop — catch, own, care for, and deploy creatures as
real-time companions — is feature-complete on Forge 1.20.1 against Alex's Mobs + Citadel.

### Added

**Catching & Spheres**
- Wild-catch roll on mob spawn (config-controlled chance), with an enchant-glint shimmer
  marking catchable mobs instead of a through-wall glow outline
- HP-based catch-rate formula (sphere tier + Leveling level − max HP + 0.7 × % health lost)
  so full-health targets are a deliberate near-miss and weakening a target first matters
- Three sphere tiers (Pal Sphere / Mid / Advanced) plus five enchantments: Leveling,
  Infinite, FastBall, Following, Warp Tether
- Close-range interact-based throws (right-click directly on a mob) alongside ranged throws,
  with double-throw protection against modded-client packet duplication

**Pal ownership & lifecycle**
- Summon-by-throw / recall-by-right-click sphere lifecycle with full mob NBT snapshotting
- Three distinct visual sphere states (empty / filled+inside / filled+released) including a
  client-side egg tint on filled spheres sourced from each mob's spawn egg
- Orphan rule: released Pals despawn without their sphere in hand, except station-bound,
  deployed, or currently-ridden Pals
- Owner interact routing (fetch, storage, XP collection, hand-feeding, mounting, sit/stand)
  driven entirely by each Pal's datapack-defined capabilities

**Care**
- Hunger/Mood stat system with per-mob decay rates, tiered slowdown, and self-recovery even
  while critically hungry (crawl-to-food instead of freezing)
- Pal Feeder block (hopper-fed) and per-mob food tables for hand-feeding
- Self-feeding station producers and owner-priority hand-feeding for station-bound Pals

**Work Stations**
- Pal Work Station block with sphere-bound worker placement, hopper-pushed output, and
  automatic filled-sphere return if a worker goes missing
- Five worker jobs out of the box: harvester, lumberjack, trader, sorter, miner — with a
  one-file extension point (`WorkerGoalRegistry`) for adding more

**Powers & deploy modes**
- One proactive special power per Pal (no manual ability buttons): elemental combat casts,
  summon-triggered time stop, recall-triggered warp beacon, sentry/anchor deploy modes,
  swarm cloning, item magnet auto-delivery, XP collection, heal/storm auras, mountable rides
- Deployed Pals (anchor/sentry) skip hunger/mood decay entirely

**Wild difficulty**
- Four wild-catch difficulty categories for catchable mobs: brawler, skittish, op (time
  stop / storm dodge / blink), and predator-locked (damage immune / shy hide / ambush hide)
- Pal Compass (craftable) for locating the nearest catchable mob within 128 blocks

**Paldex & in-game docs**
- Standalone Paldex book item opening a full in-game guide inside Alex's Mobs' own dictionary
  book UI: guide pages for every major system plus a bestiary with a Pal Info page per shipped
  creature (20 pals across brawler/skittish/op/predator-locked/support roles)

**Server & compatibility**
- `catchableChance` and `allowVanillaClients` config options; the latter patches Forge's
  network handshake so vanilla clients and protocol bots can still join a modded server

### Known issues

See [README.md § Known issues](README.md#known-issues) for the full,
continually-updated technical list. Highlights: some Alex's Mobs creatures with strong native
AI (elephant, tasmanian devil) partially resist hand-feed/target routing; flying mobs are hard
to catch with a thrown sphere; the wild-catch roll currently fires on bred/egg-hatched mobs too,
not just natural spawns; orca riding and fireball/water-burst casts are implemented but not yet
confirmed on a live client.
