<img src="src/main/resources/palmod.png" alt="Palmod logo" width="64" height="64">

# Palmod

**Quality-of-life creature companions for Minecraft — without the turns.**

![Version](https://img.shields.io/badge/version-0.9.3-blue)
[![Minecraft](https://img.shields.io/badge/minecraft-1.20.1-brightgreen)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/forge-47.4.10%2B-orange)](https://files.minecraftforge.net/)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

> **Status: 0.9.3 — beta.** Core loop (catch → own → summon → work/fight/assist) is
> feature-complete and server-tested, now with a survival-pressure layer (tanky, self-healing
> wild mobs; biome-distributed spawns; hunting nights), a Pokéball-style catch animation, and
> station-free worker Pals that haul to any craftable Work Station. The
> thrown sphere's 3D mesh renders client-side and still needs a visual pass on a real modded
> client. A few systems remain flagged untested-in-game — see [Known Issues](#known-issues)
> before reporting a bug.

---

## What is this?

Palmod is a Forge server mod built around one idea: creature-collecting games are fun because
of the *loop* — find something interesting, catch it, raise it, watch it get useful or strong —
not because of turn-based battle menus. Palmod keeps the loop and throws out everything else.
There's no Pokémon-style combat system, no menus to fight through, and nothing here is
Pokémon or Palworld content, assets, or mechanics — it's an original ruleset that happens to
call a caught creature a **"Pal"** because "creature you threw a sphere at" doesn't fit on a
tooltip.

Under the hood it's a **quality-of-life mod first**: your Pals are automated farmhands,
combat allies, mobile storage, buffs, and fast travel, layered on top of vanilla and
[Alex's Mobs](https://www.curseforge.com/minecraft/mc-mods/alexs-mobs) creatures with zero
turn-based interruption — everything happens in real time, in the open world, while you keep
playing normally.

Everything about *which mob does what* lives in datapack JSON, not code — see
[Adding & Configuring Pals](#adding--configuring-pals-datapacks) if you want to add your own
creature, rebalance an existing one, or build an addon.

### Requirements

- Minecraft **1.20.1**, Forge **47.4.10+**
- [Alex's Mobs](https://www.curseforge.com/minecraft/mc-mods/alexs-mobs) `1.22.9+` (required —
  the Paldex, dictionary pages, and most shipped Pals depend on it)
- [Citadel](https://www.curseforge.com/minecraft/mc-mods/citadel) `2.6.3+` (Alex's Mobs
  dependency)
- Server-authoritative: all Pal logic runs server-side. The client only needs item
  textures/models, the egg-tint on filled spheres, and the Paldex book pages — a vanilla or
  bare-Forge client can still join and see a functional (if visually plainer) mod, see
  `allowVanillaClients` below.

---

## Table of contents

- [Player Guide](#player-guide)
  - [Catching a Pal](#catching-a-pal)
  - [Sphere tiers & enchantments](#sphere-tiers--enchantments)
  - [The Pal Compass](#the-pal-compass)
  - [Summoning, recalling & orphaning](#summoning-recalling--orphaning)
  - [Interacting with your Pal](#interacting-with-your-pal)
  - [Hunger & Mood](#hunger--mood)
  - [Work Stations](#work-stations)
  - [Powers & deploy modes](#powers--deploy-modes)
  - [The Paldex](#the-paldex)
  - [Wild Pals fight back](#wild-pals-fight-back)
- [Server configuration](#server-configuration)
- [Adding & Configuring Pals (datapacks)](#adding--configuring-pals-datapacks)
  - [Behavior JSON reference](#behavior-json-reference)
  - [Worker types](#worker-types)
  - [Wild difficulty categories](#wild-difficulty-categories)
  - [Food & trade tables](#food--trade-tables)
  - [Adding Paldex / dictionary pages](#adding-paldex--dictionary-pages)
  - [Writing a code addon](#writing-a-code-addon)
- [Building from source](#building-from-source)
- [Known issues](#known-issues)
- [Credits](#credits)

---

## Player Guide

### Catching a Pal

Not every mob is catchable. When a mob spawns, it has a chance (config-controlled, default
**40%**) to roll "catchable." Catchable mobs show a subtle enchant-glint shimmer — there's no
outline-through-walls glow, so you'll need line of sight. Throw a Pal Sphere at an *uncatchable*
mob and the sphere is simply lost — there's no partial credit.

Catch chance is HP-based, and it's deliberately **not** a coin flip at full health:

```
catch % = (sphere tier + Leveling enchant level) − max HP + 0.7 × (% health already lost)
```

In practice: weaken it first. A full-health cow is close to uncatchable with a basic sphere;
the same cow at 1 HP is a good bet. Tougher/rarer creatures need a better sphere tier, a
Leveling enchant, or both. This is intentional — Palmod rewards fighting or wearing a target
down before you commit a sphere, not blind spam-throwing.

You can throw from range, or right-click a mob directly for a guaranteed close-range throw.

### Sphere tiers & enchantments

| Item | Craft | Notes |
|---|---|---|
| **Pal Sphere** | 8× Iron Ingot + Redstone (ring around center) | Base tier |
| **Mid Pal Sphere** | 8× Gold Ingot + Redstone Block | Better catch odds |
| **Advanced Pal Sphere** | 8× Diamond + Redstone Block | Best catch odds |

All three are enchantable (enchant an *empty* sphere before you catch with it):

| Enchantment | Effect |
|---|---|
| **Leveling** | Adds levels directly to the catch-rate formula |
| **Infinite** | Sphere isn't consumed on a lost/failed throw |
| **FastBall** | Faster projectile travel speed |
| **Following** | Sphere homes in on its target (skips uncatchable/hidden mobs) |
| **Warp Tether** | Recalling the caught Pal later teleports it straight to you |

### The Pal Compass

Craft: 4× Pal Sphere around a vanilla Compass. Right-click-equivalent hold in your hotbar and
it points at the **nearest catchable mob within 128 blocks** (with hysteresis so the needle
doesn't twitch between two nearby targets). Shy or ambush-hidden wild Pals are excluded until
they reveal themselves. No target in range = the needle spins.

### Summoning, recalling & orphaning

- **Throw a filled sphere** → summons the Pal into the world, alive, at full state, with a
  name tag over its head.
- **Right-click while it's out** → recalls it back into the sphere (snapshots its current
  state — health, position data, everything).
- **The Pal dies** → its sphere reverts to an empty sphere automatically. No re-catching a
  ghost.
- **Orphan rule**: a released (summoned) Pal despawns if its sphere isn't in your hotbar or
  offhand — you can't summon a Pal and then wander off without it. Exceptions: deployed Pals
  (anchor/sentry), worker Pals (they carry their own sphere — see below), and anything you're
  currently riding.

### Interacting with your Pal

Right-clicking your own Pal does different things depending on how you click and what it's
configured to do:

| Click | Requires | Result |
|---|---|---|
| Sneak + holding an item | `fetch` enabled | Sends it to fetch a matching item |
| Sneak, empty hand | `mobile_chest_size` > 0 | Opens its storage (a vanilla chest UI) |
| Sneak, empty hand | `xp_collector` enabled | Collects its stored XP as bottles |
| Plain click + food it likes | Hunger < 100 | Hand-feeds it |
| Plain click | `rideable` | Mounts it (steers by where you look) |
| Plain click | none of the above | Toggles sit/stand |

### Hunger & Mood

Every Pal has a Hunger and Mood meter that decays over time (rates are configurable per
mob). Actions — chopping, harvesting, casting abilities — cost Hunger too. Effects compound
the hungrier a Pal gets:

- **< 80** — actively seeks out a stocked Feeder on its own
- **< 30** — moves and works at 0.7× speed
- **< 5** — stops working entirely, but keeps crawling (0.3×) toward food instead of freezing
  solid — it can still recover on its own
- Deployed Pals (turrets/waystones) don't decay at all
- Feed it directly by right-clicking with food it likes, or build it a **Pal Feeder** block
  (hopper-fed) so it self-serves

### Work Stations

The **Pal Work Station** is craftable (planks + a chest + an iron ingot) and is a plain 27-slot
chest — right-click to open it, hopper into or out of it, read it with a comparator, break it
and get everything back.

Pals with a job (lumberjack, harvester, trader, sorter, miner) don't need to be assigned to one.
Throw such a Pal out where you want it working and it starts on its own, doing its job within a
configurable radius of the spot it landed. When its arms fill up — or when there's nothing left
to work — it walks to the **nearest** Work Station in range and unloads whatever it's carrying.
With no station nearby it just holds onto the load until one shows up.

**A worker Pal carries its own sphere.** Throwing it out consumes the sphere — it goes with the
Pal — so the Pal stays at work whether or not you're nearby, and keeps going while you're
logged out. To bring it back, **right-click the Pal itself** and it returns to its sphere in
your hands. If a worker dies it drops an empty sphere where it fell, and breaking a station
never harms it — it just delivers to the next nearest one.

### Powers & deploy modes

Every Pal has **one** special power (see the [ability roster](#ability-roster) below) that
triggers automatically from how you use it — there are no manual ability buttons to press:

- **Combat casters** throw elemental attacks at your target automatically while summoned and
  fed.
- **`time_stop`** Pals freeze everything in a radius the instant you summon them — pick your
  moment and your spot with the throw.
- **`warp_beacon`** Pals root themselves in place on a normal summon; recalling them later
  teleports *you* to that spot.
- **Sneak-throwing** a sphere with `deploy_mode` set (instead of a normal throw) turns that
  Pal into a stationary **anchor** or **sentry** turret instead of a mobile companion.
- **Aura** Pals (heal/storm) passively buff you just by being out and fed nearby.

#### Ability roster

| Ability | Example Pal | Ability | Example Pal |
|---|---|---|---|
| Bodyguard (retaliate-only) | Grizzly Bear | ZA WARUDO (time stop on summon) | Enderiophage |
| Lumberjack | Gorilla | Warp beacon (teleport on recall) | Spectre |
| Harvester | Leafcutter Ant | Sentry turret | Guster |
| Trader | Crow | Lightning cast | Sunbird |
| Sorter | Raccoon | Fireball cast | Soul Vulture |
| Miner | Rocky Roller | Earth spike cast | Elephant |
| Heal aura | Mungus | Water burst cast | Mantis Shrimp |
| Storm charge (lightning on your hits) | Bald Eagle | Rideable swim mount | Orca |
| Mobile storage | Kangaroo | Swarm / clone | Tasmanian Devil, Capuchin Monkey |
| XP collector | Endergrade | Item magnet (auto-delivers to chest/storage Pal) | Mimicube |

Hand-feeding works on every Pal regardless of its special power.

### The Paldex

Craft a **Paldex** (Book + Pal Sphere) and right-click to open an in-game guide — powered by
Alex's Mobs' own dictionary book UI, so it looks and feels native. It covers spheres, catching,
enchantments, the compass, care & feeding, stations, powers, and wild types, plus a bestiary
page with a Pal Info entry for every shipped creature.

### Wild Pals fight back

Catchable wild mobs aren't always easy targets. Some fight back based on a difficulty
category set in their behavior JSON:

- **Brawler** — more HP, damage resistance, throws its own combat ability at you
- **Skittish** — flees the moment it sees or hears you
- **Op** — has an "unfair" defensive trick: freezes your thrown spheres in mid-air
  (`time_stop`), dodges + counter-strikes lightning while airborne (`storm_dodge`), or
  straight-up teleports away (`blink`, unless your sphere has Warp Tether)
- **Predator-locked** — can't be hurt at all except by a specific counter-ability from one of
  your own Pals, or stays invisible/hidden until baited out by a Pal you already own nearby

---

## Server configuration

Config lives at `config/palmod-common.toml` after first launch:

| Key | Default | Effect |
|---|---|---|
| `catchableChance` | `0.40` | Chance any naturally-spawning mob rolls catchable (0.0–1.0) |
| `allowVanillaClients` | `true` | Dedicated-server-only. Patches Forge's network handshake so vanilla clients and protocol bots (e.g. mineflayer) can still join even though Alex's Mobs/Citadel would normally reject them. Modded logic still runs server-side; those clients just won't render custom GUIs/animations from other mods. |

> Config changes require a **server restart** to take effect — Forge's config hot-reload does
> not reliably apply on a dedicated server for this mod.

---

## Adding & Configuring Pals (datapacks)

Palmod deliberately keeps **all** per-mob balance and behavior out of Java. Adding a new Pal —
or reworking an existing one — is a datapack change, no compiling required. This also means a
separate datapack/addon can layer on top of Palmod without touching its jar at all.

Drop files under a datapack (or resource pack, for the dictionary pages) at:

```
data/<your_namespace>/pal_behaviors/<file>.json   # required — unlocks the mob as a Pal
data/<your_namespace>/pal_foods/<file>.json       # optional — enables hand-feeding
data/<your_namespace>/pal_trades/<file>.json      # optional — only for trader-type workers
```

The **filename doesn't matter** — the target mob is identified by the `"entity"` field inside
each JSON (a full entity registry ID, e.g. `"minecraft:panda"` or `"alexsmobs:capuchin_monkey"`).
Any mob without a `pal_behaviors` entry simply can't be caught/owned — it stays a plain vanilla
mob and never rolls catchable.

### Behavior JSON reference

```json5
{
  "entity": "alexsmobs:gorilla",
  "behaviors": {
    "follow_owner": { "speed": 1.1, "start_distance": 18.0, "stop_distance": 9.0 }, // or just true/false
    "protect_owner": true,
    "attack_target": true,
    "guard_only": true, "guard_leash": 12.0,          // retaliate-only bodyguard behavior
    "melee_attack": { "speed": 1.2 },                  // or bool
    "station_mode": {
      "enabled": true, "worker_type": "lumberjack",
      "harvest_radius": 10, "wander_radius": 24,
      "log_cap": 64, "ore_tag": "forge:ores"
    },
    "deploy_mode": "anchor",                           // or "sentry" — sneak-throw activates
    "sentry_radius": 16.0,
    "warp_beacon": true,                                // roots on summon; recall warps owner to it
    "mobile_chest_size": 27,                            // storage slots (vanilla chest UI)
    "combat_ability": {
      "type": "lightning",                              // lightning | fireball | earth_spike | water_burst
      "damage": 6.0, "range": 16.0, "cooldown_ticks": 60, "hunger_cost": 2.0
    },
    "clone": { "max": 6, "duration_ticks": 200, "cooldown_ticks": 300, "hunger_cost": 4.0 },
    "rideable": { "speed": 2.2 },                        // or bool
    "fetch": { "radius": 16 },                           // or bool
    "xp_collector": { "radius": 8.0, "max_stored": 1395 },
    "magnet": { "radius": 6.0, "slots": 2 },              // hoards one item type, auto-delivers
    "greedy_boom": { "boom_at_hunger": 95.0 }             // or bool
  },
  "time_stop": { "duration_ticks": 200, "radius": 24.0, "cooldown_ticks": 1200, "hunger_cost": 40.0 },
  "aura": {
    "type": "heal",                                      // heal | storm
    "radius": 8.0, "interval_ticks": 100, "potency": 0, "hunger_cost": 2.0
  },
  "stats": { "hunger_decay_per_minute": 3.0, "mood_decay_per_day": 2.0 },
  "hunger_costs": { "harvest": 2.0, "chop": 4.0 },        // any action key you reference in code
  "wild": {
    "category": "brawler",                                // brawler | skittish | op | predator_locked
    "type": "",                                            // op/predator_locked subtype, see below
    "counter_ability": "water_burst",
    "health_multiplier": 1.5, "resistance": 0, "use_combat_ability": true,
    "sight_range": 32.0, "hearing_range": 20.0, "flee_speed": 1.4,
    "radius": 24.0, "cooldown_ticks": 600, "hide_range": 16.0, "lure_range": 8.0
  }
}
```

Only include the blocks a given Pal actually needs — everything is optional except `entity`.

### Worker types

`station_mode.worker_type` picks which job goal a station-bound Pal runs:

| `worker_type` | Behavior |
|---|---|
| `harvester` | Harvests a renewable resource in a radius (e.g. leaf-cutting) |
| `lumberjack` | Chops and replants trees, caps output at `log_cap` |
| `trader` | Converts payment items dropped near the station (see [trade tables](#food--trade-tables)) |
| `sorter` | Sorts/organizes nearby storage |
| `miner` | Mines a configurable ore tag (`ore_tag`) within its radius |

Adding a new job type is a one-file code change: subclass `AbstractStationWorkerGoal` and
register it with `WorkerGoalRegistry.register("your_type", ...)` — see
[Writing a code addon](#writing-a-code-addon).

### Wild difficulty categories

Set under `wild.category`. Each has its own extra fields (all shown in the reference above):

- **`brawler`** — `health_multiplier`, `resistance` (`-1` = off, `0` = Resistance I),
  `use_combat_ability` (fires its `combat_ability` block at attackers, free of hunger cost)
- **`skittish`** — `sight_range`, `hearing_range`, `flee_speed`
- **`op`** — set `wild.type` to one of:
  - `time_stop` — freezes thrown spheres in `radius`, telegraphed, `cooldown_ticks` gate
  - `storm_dodge` — dodges + strikes the thrower with lightning while airborne; catchable
    only while grounded
  - `blink` — teleports away from any incoming sphere unless it has Warp Tether
- **`predator_locked`** — set `wild.type` to one of:
  - `damage_immune` — untouchable except by an owned Pal whose `combat_ability.type` matches
    `counter_ability` (`/kill` and void damage still work)
  - `shy_hide` — invisible + rooted near players unless a **passive** owned Pal is within
    `lure_range`
  - `ambush_hide` — hidden until any owned Pal wanders within `hide_range`, then pounces

Leave `wild` out entirely for a mob with no special defenses (plain catchable). The six values
above are just the built-ins — `wild.type` is resolved through `WildDefenseRegistry` (see
[Writing a code addon](#writing-a-code-addon)), so a new type is a registration call away, not
a source edit.

### Food & trade tables

```json5
// data/<ns>/pal_foods/<file>.json
{
  "entity": "alexsmobs:leafcutter_ant",
  "default_hunger_restore": 3.0,
  "default_mood_bonus": 0.0,
  "foods": [
    { "item": "minecraft:sugar", "hunger_restore": 8.0, "mood_bonus": 3.0 }
  ]
}
```

A mob with no `pal_foods` entry can still be summoned/owned, it just can't be hand-fed by item
(only via a Pal Feeder, if it self-feeds as a producer).

```json5
// data/<ns>/pal_trades/<file>.json — only relevant for worker_type: "trader"
{
  "entity": "alexsmobs:crow",
  "payment": "minecraft:emerald",
  "results": [
    { "item": "minecraft:diamond", "count": 1, "weight": 3 }
  ]
}
```

Weights are relative — the trader rolls one `results` entry per payment item consumed.

### Adding Paldex / dictionary pages

Optional, client-only, purely cosmetic. To give a new Pal its own "Pal Info" page in the AM
dictionary book:

1. Add `assets/alexsmobs/book/animal_dictionary/<page>.json` +
   `en_us/<page>.txt` as plain resource-pack assets — these merge fine as-is.
2. If you also want a button linking to it from the bestiary grid or an existing AM page,
   that requires *overriding* an existing AM page JSON, which needs to ship inside a
   **forced resource pack** (same trick Palmod itself uses via `PalmodBookPack`) since plain
   same-path overrides silently lose to Alex's Mobs' own pack.

This system is entirely optional — a Pal works in every other respect without a dictionary
page.

### Writing a code addon

Every extension point below is a **standalone registration call** — add `palmod` as a compile
dependency in your own mod's `build.gradle`, call `register(...)` once (from your mod
constructor or `FMLCommonSetupEvent`), and you're done. Nothing here requires editing, forking,
or PRing into Palmod itself — that's the whole point of these being registries instead of
switch statements.

**New station job** (`station_mode.worker_type`) — subclass `AbstractStationWorkerGoal`, then:

```java
WorkerGoalRegistry.register("fisher", (mob, stationPos, behavior) ->
    new MyFisherGoal(mob, stationPos, behavior.getHarvestRadius(), behavior.getWanderRadius()));
```

**New combat ability** (`combat_ability.type`) — one lambda via `CombatAbilityRegistry`:

```java
CombatAbilityRegistry.register("poison_spit", (level, mob, target, behavior) -> {
    target.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
    target.hurt(level.damageSources().mobAttack(mob), behavior.getCombatDamage());
});
```

**New wild defense** (`wild.type`, for `op`/`predator_locked` mobs) — implement whichever
hooks of `WildDefenseRegistry.WildDefense` you need (all default to a no-op, so a simple dodge
only needs `onSphereHit`):

```java
WildDefenseRegistry.register("smoke_bomb", new WildDefenseRegistry.WildDefense() {
    @Override
    public boolean onSphereHit(Mob mob, PalBehavior behavior, PalSphereProjectile sphere, ServerLevel level) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY(), mob.getZ(), 40, 0.5, 0.5, 0.5, 0.05);
        mob.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60));
        return true; // consumes the sphere — this throw is defended
    }
});
```

Reference `data/<ns>/pal_behaviors/*.json` with `"wild": {"category": "op", "type": "smoke_bomb", ...}`
and it's live — no Palmod source touched.

**New proactive pal power** (the "one special power per mob" system — clone, magnet, XP
collector, greedy boom, warp beacon, time-stop-on-summon, and anything you add) — implement
whichever hooks of `PalAbilityRegistry.PalAbility` you need: `onJoin` to inject an AI goal,
`tick` for a passive ~20-tick hook, `onSummon` to react once right after a fresh summon, or
`forcedDeployMode` if the power should root the pal into a deploy mode on a plain summon:

```java
PalAbilityRegistry.register(new PalAbilityRegistry.PalAbility() {
    @Override
    public boolean appliesTo(PalBehavior behavior) {
        return behavior.getExtraFieldYouAdded() > 0; // gate on your own behavior field
    }

    @Override
    public void tick(Mob mob, PalBehavior behavior, ServerLevel level) {
        // runs every ~20 ticks while this pal is owned and alive
    }
});
```

**Reacting to a pal's lifecycle** — subscribe to the events in `com.mx.palmod.api.event` on the
Forge event bus like any other Forge event, no registry needed:

| Event | Fires when | Cancelable |
|---|---|---|
| `PalCaughtEvent` | A wild-catch roll succeeds, before the mob is removed | Yes — canceling treats it as a failed catch |
| `PalSummonedEvent` | A filled sphere finishes placing a pal in the world | No |
| `PalRecalledEvent` | A released pal is recalled into its sphere (deliberate or automatic) | No |
| `PalDiedEvent` | An owned pal dies, right before its sphere reverts to empty | No |
| `PalDeployedEvent` | A pal roots into a deploy mode (anchor/sentry) on summon | No |

```java
@SubscribeEvent
public static void onCatch(PalCaughtEvent event) {
    if (event.getPal().getType() == EntityType.WITHER) {
        event.setCanceled(true); // no catching the Wither, thanks
    }
}
```

Everything else — the sneak/plain click-priority table, deploy mode (anchor/sentry) selection,
and hunger/mood ticking — is still centralized directly in `ForgeEvents` rather than a registry;
those are dense, stable UX logic where the regression risk of adding indirection outweighed the
addon payoff. A new mob with a correct `pal_behaviors` entry still needs **zero** Java to work
at all — every extension point above is purely additive for mods that want more.

One honest limitation: there's currently no separate published API artifact (see
[`build.gradle`](build.gradle)'s `maven-publish` block, which just ships the whole jar) — an
addon still compiles against the full `palmod` jar and its internal packages, not a slim public
API. The registries above are stable extension points regardless; just don't reach into
unrelated internals and expect them to stay put across versions.

---

## Building from source

```powershell
git clone https://github.com/LynxMyth/PalModCore.git
cd PalModCore
.\gradlew.bat build
```

Output jar lands in `build/libs/palmod-<version>.jar`. First build downloads Forge's toolchain
and can take a few minutes. See [README.txt](README.txt) for IDE-specific run-config setup
(Eclipse/IntelliJ) inherited from the Forge MDK template.

---

## Known issues

- Some AM mobs with strong built-in AI (elephant, tasmanian devil) partially fight the mod's
  hand-feed/target routing — prefer vanilla or simpler-AI mobs when picking a combat-ability
  carrier.
- Flying mobs are very hard to catch with a thrown sphere (they dodge, and Following doesn't
  reliably lock on) — the Compass helps you *find* them, not hit them.
- The wild-catch roll currently fires on every unowned mob join, including bred/egg-hatched
  mobs, not just natural spawns.
- A few systems are implemented and code-verified but not yet confirmed on a live client:
  orca riding, fireball/water-burst casts, and all client-only visuals (egg tint, glints,
  dictionary rendering).

---

## Credits

Built on [Minecraft Forge](https://files.minecraftforge.net/) for Minecraft 1.20.1. Designed
to run alongside [Alex's Mobs](https://www.curseforge.com/minecraft/mc-mods/alexs-mobs) and
[Citadel](https://www.curseforge.com/minecraft/mc-mods/citadel). Licensed under the MIT
License — see [LICENSE](LICENSE).
