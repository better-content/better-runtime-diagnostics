# Better Runtime Diagnostics

## Scope and authority

This repository owns its mod-specific behavior and authoring inputs. Read [local instructions](AGENTS.md)
and the [shared documentation/policy index](../../better-content-modpack/docs/README.md).


Server-authoritative Forge 1.20.1 diagnostic mod. An operator can run
`/runtimedata dump` to export the final live recipe manager, registries, tags,
effective loot tables, sampled effective villager offers, worldgen registries,
lighting, loaded dimensions, the live Creating Space rocket-accessible-dimension registry,
and mod list under `generated/runtime-dumps/` in the server directory. Every
file shares one snapshot ID so offline tooling can reject mixed evidence.

`dimensions.json` uses `bc.dimensions.v1`. It records sorted loaded dimension IDs and, when
Creating Space is loaded, the sorted contents of its dynamic
`creatingspace:rocket_accessible_dimension` registry. A missing registry makes the surface and
aggregate snapshot incomplete instead of silently producing an empty inventory.

`lighting.json` enumerates every live registered block state and fluid with positive
light emission. When Sodium Dynamic Lights is loaded, it also records automatic
luminous block items and accepted portable-item declarations from loaded mod
resources. Missing compatibility targets are retained as rejected diagnostics.
Portable results do not prove a particular client's configuration, resource-pack
overrides, entity/spell lighting, or shader-only emissive effects.

Trade output is explicitly sampled evidence: each effective listing is invoked
with 16 deterministic seeds for every villager type. It preserves dynamic
listing classes and representative offer NBT without claiming that a finite
sample enumerates every possible randomized offer.

`snapshot.json` and `completion.json` (`bc.runtime_dump_completion.v3`) are complete only when
every live recipe has a fully normalized
machine edge, every serializer payload succeeds, and the exact loot/worldgen
plus sampled-trade export contracts report no errors. Incomplete rows and raw
serializer payloads are retained for adapter work, but the command returns a
failure result and pack tooling must not promote or fingerprint that snapshot
as authoritative. Worldgen registry data proves configured live state, not
placement frequency or occurrence in a generated world; loaded loot tables
likewise do not prove that their runtime context is reachable.

`recipes.json` uses `bc.recipe_graph.v3`. Deterministic family adapters preserve
all Create/Sifter outputs with their probabilities, exact Create and TConstruct
fluid amounts, and recipe-level heat requirements. They never sample randomized
`rollResults()` methods. Known quantitative families make the graph incomplete
when those semantics cannot be recovered.

Optional recipe adapters only emit edges backed by live accessors or pinned
serializer state. In addition to item and fluid edges, the graph can therefore
name block transformations, TConstruct materials/modifiers/modifier slots, and
enchantments without pretending those state transitions are ordinary items.
Context-dependent rules use an explicit `operation_kind`, typed `effects`, and
typed `requirements`: Blood Magic flask state changes, TConstruct material-cost
melting and recycling/tool mutations, and AE2 matter-cannon ammo profiles are
therefore navigable without fake static outputs. The same schema represents
Malum spirit repairs, PneumaticCraft heat/fuel properties, Ars scry mappings,
Blood Magic living-armour mutations, and Goety brewing/soul operations.
AlmostUnified client recipe trackers are explicit non-gameplay metadata
exclusions carrying their exact linked recipe flags, never fabricated crafting
edges. Dynamic special crafting is represented by typed runtime selectors and
copy/mutation effects; placement policies, meteor world effects, smeltery fuel
profiles, and modifier application/removal likewise remain distinct from
ordinary static item transforms. Clockwork gas crafting retains exact gas-mass
maps, reaction requirements, and energy, while TConstruct part swapping and
modifier-set worktable recipes retain their live tool predicates and runtime
mutation semantics. Modded state-copy, dye, binding, repair, and container
recipes use the same runtime-selector model. Fluid brewing, uncrafting result
grids, entity/ritual effects, entropy world-state changes, and intentional
outputless processing are represented as their own typed operations.

The command is intentionally the only trigger. The mod performs no work during
startup, reload, or player synchronization.

`/runtimedata combat` separately exports `combat-profile.json`. It samples the
default armor, toughness, and health of every constructible entity identified
by either the hostile mob category or Minecraft's Enemy contract,
excludes 100-health boss-class entries from percentile selection, and derives
the pack's Trash/Elite/Boss armor representatives at P50/P75/P90. It changes no
entities and performs no automatic difficulty scaling.

Build, test, and stage the reobfuscated runtime JAR with:

```sh
./gradlew build stageRuntimeJar
```

## Disposable lifecycle and observation support

`bc.pack_test.world=save|verify` enables the existing isolated integrated-world lifecycle probe. Verify exit is normally inert. Its explicit `bc.pack_test.verify_exit.*` interface carries `run_id`, `player`, canonical `player_uuid`, `nonce`, `fixture_root`, `world_root`, and `request_dir`. The last must be exactly the fresh fixture child `world-lifecycle`. The unchanged `bc.pack_test.verify_exit.v1` payload is strictly bounded/typed and binds actual loaded native server/level/player references, EMI readiness and a one-shot normal native exit. The harness requests exit only after marker equality and nondecreasing saved/reopened time. No journal/native storage properties or checkpoints participate; no aliases are accepted. No storage initialization, repair or UUID replacement occurs.

`./gradlew build` also builds and validates `build/libs/better-runtime-test-support.jar`, an isolated test supplement with non-cancelling rotation/species observers and explicitly armed native fixture drivers. Its `bc.pack_test.diagnostics.*` metadata binds the disposable run and hashes; it has no item recipes, commands, packet-action observer, inventory ledger or storage hooks. Mapping, observer bytecode non-cancellation and production isolation guards are mandatory; the separately audited gameplay hold driver below is the sole narrow cancellation exception. This supplement is injected only into copied pack fixtures, classified only in their copied blocking KubeJS policy, and forbidden in distributable ZIPs even when renamed. It is not a production dependency.

The isolated supplement additionally provides explicitly armed, nonce-bound native acceptance
probes. `FreshWorldProbe` accepts only an absent save in a fresh Debug singleplayer fixture,
creates a normal world with the native random seed, observes the production resolved initial
spawn, submits the typed Better Spawns onboarding action and records correlated native Survival
ticks. Its explicit exit request uses normal disconnect/server shutdown. It never authors a
spawn marker. `FreshWorldInput` tests reject wrong fixture/run/tier, preexisting saves, symlinks
and mismatched exit bindings.

`DeepVoidTickProbe` arms a real network Survival player in the extended-bottom Overworld and
observes before/after the upstream native END tick. It records above-boundary and disabled-switch
negative controls before native entry and restores temporary config state. It does not call the
upstream procedure or teleport into the destination. The harness separately requires the real
client's destination report. Compile inputs include the staged Better Spawns provider and exact
Deep Void file 8507128; these are supplement-only dependencies, not production API additions.
Support hashes are candidate-bound. Source/contract gates prove compilation and isolation, not
native runtime acceptance; see the [shared testing procedure](../../better-content-modpack/docs/testing.md).

The explicit Debug `gameplay` target also binds all requests and responses to run, nonce, player
and candidate/support hashes. Its presence observer reads only exact public campaign APIs and
persists actual eligible native ticks/monotonic time across a real server-process restart; it
never seeds campaign history or credits offline time. Authored world/item actions exercise the
actual registry providers, native menus, harvesting and readiness feedback. Read-only client
hooks observe submitted particles/progress and native sound-source playback. The separate
held-cut driver suppresses only a bound no-input release, once-per-native-tick public mining
calls continue normally, and new targets/stop/disconnect/deadline clear it. Its exact bytecode
boundaries are checked separately; it is not human mouse input.

Native framebuffer captures and typed pinned Oculus APIs distinguish default rendering from
actual shader-enabled day/night frames. The harness's native OpenAL WAVE backend captures mixer
output through normal client exit, not synthetic sound. Sound dispatch, native source playing,
aggregate PCM, cue-specific waveform identification and physical listening remain distinct
verdicts. Native GUI frames do not authorize human-pointer inventory acceptance. All these
helpers remain supplement-only, never production/candidate content. New fixture compile APIs
use named local Ivy providers (ForgeGradle does not deobfuscate `files(...)` dependencies);
canonical `BC_CUSTOM_MOD_JAR_DIR` stages override the declared standalone SDK sources. See the
[shared gameplay procedure](../../better-content-modpack/docs/testing.md).

## Canonical identity

- Repository and release artifact: `better-runtime-diagnostics`
- Mod ID and resource namespace: `better_runtime_diagnostics`
- Java package: `com.bettercontent.betterruntimediagnostics`
- Validation: `./gradlew build`

This normalization is a clean break. Worlds, configuration files, and integrations created for earlier identities are not migrated or aliased.
