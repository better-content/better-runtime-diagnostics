# Better Runtime Diagnostics

## Scope

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

The command is intentionally the only export trigger. The mod performs no export scanning during
startup, reload, or player synchronization.

## Bounded playtest diagnostics

Loader SLF4J `bc.playtest.better_runtime_diagnostics.*` emits one INFO startup/source status
(UNKNOWN if exact own metadata is unavailable). DEBUG records operator-command start and actual
exporter return: complete/incomplete/failed, existing snapshot identity, counts and monotonic
elapsed time. No automatic exports, payload dumps, absolute output paths or exception-message
copies are introduced; existing failure messages and parser witnesses remain unchanged.
One shared 120/minute burst20 detail bucket, 256 aggregate keys, 2048-character records and
30-second summaries bound logging. Disconnect/server stop clears aggregate state without
refilling allowance. Standalone remains INFO; the pack selects scoped DEBUG. Completion records
never replace snapshot completeness checks or native runtime acceptance.

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

### Additive native bee-body color fixture

The existing nonce/candidate-bound gameplay supplement preserves all bee states, controls,
deadlines, dense675/unload/reconnect handling and100actual-client-tick windows. Optional
`species_id` on `bees-setup` selects native `complicated_bees:forest` or `complicated_bees:plains`;
absence preserves the old forest default. `bees-species-change` updates the same authored native
nest or resident queen via native setters/factory/handler; sentinel `invalid` clears it. Neither
route forces climate, weather, time or genes. Native eligibility still has the existing20minute
bound; added appearance readiness does not redefine the old `ready` field.

Server observation adds actual native body/nest colors, species/climate and primitive cosmetic
state. A separately guarded nonshipping queen oracle uses native expressed-species decoding and
checks authored queen tags before/after; it is not an extra production decode or a NBT export.
Paired `expected_species_id`/`expected_native_rgb` on `bee-window-start` opt into color agreement
through the existing window. Positive color windows additionally require actual typed-particle
creation and no legacy creation. Old `engine_creations` remains the total; additive legacy/typed
counts distinguish the protocols. Requests and source pixels are not engine/render acceptance.
Same-candidate frames of native occupied nests **and resident queens**, real eligibility,
in-place invalidation and existing lifecycle/control acceptance remain required.

### Read-only native whale receipt

The supplement's `WhaleClientObserver` is a `Dist.CLIENT` Forge subscriber (not a mixin).
It is inert unless the client JVM explicitly supplies `bc.pack_test.whale.enabled=true`,
canonical lowercase dashed UUID properties `bc.pack_test.whale.nonce`, `.entity_uuid` and
`.player_uuid`, and the existing `bc.pack_test.diagnostics.run_id`, `.mode=debug`,
`.client_sha256`, `.server_sha256`, `.runtime_sha256`, `.support_sha256` and `.dir` properties.
Hashes must be exactly 64 lowercase hexadecimal characters. The directory must be an existing,
canonical, nonsymlink `<runRoot>/<runId>/<suite-or-target>/fixture/runtime-diagnostics`;
the actual native client game directory must be an existing nonsymlink sibling `client-N`.
Bindings are checked again while armed; invalid or changed properties permanently disarm the
observer in that JVM. A nonce and newly summoned entity UUID are required for each fresh probe.

Only a native client `EntityJoinLevelEvent` for the exact UUID and type
`untamedwilds:baleen_whale` in `minecraft:overworld`, with the matching local player and an
underlying native connection reporting `Connection.isConnected()`, starts the observation window. The same native entity reference must then be present
in the same `ClientLevel` at two distinct later END client ticks. Wrong identities, types,
dimensions, absent entities or replacement references permanently disarm after the join;
repeated/stale ticks are ignored and cannot contribute. There is no
pre-join deadline. The post-join window is 30 monotonic seconds; disconnect, changed binding,
level replacement or expiry permanently clears it. It cannot rearm an old binding.

Success emits exactly one INFO line beginning `BC_WHALE_CLIENT_OBSERVED ` followed by compact
JSON with schema `bc.whale_client_observation.v1` and `run`, `nonce`, `player_uuid`, `entity_uuid`,
`entity_type`, `dimension`, `join_tick`, `first_tick`, `last_tick`, `client_sha256`, `server_sha256`,
`runtime_sha256`, `support_sha256`. These ticks are this observer's monotonically increasing END
client-tick counter, not server ticks or wall-clock estimates. The observer does not summon,
mutate, cancel, send packets or replace native behavior. The harness independently bounds
post-summon waiting and requires a fresh heartbeat; a receipt is not visual acceptance.

`src/testSupportPure` holds immutable Java bindings/observations/state; JUnit loads that output
without loading native client classes. The pure output is included only in the support JAR,
never production. `WhaleObservationTest` covers strict properties/fixture ancestry, wrong native
identities, reference replacement, missing joins, stale/repeated ticks, disconnect, expiry and
one-shot completion. Source and bytecode guards reject whale gameplay mutation/cancellation;
`./gradlew build` remains the full source gate.

## Canonical identity

- Repository and release artifact: `better-runtime-diagnostics`
- Mod ID and resource namespace: `better_runtime_diagnostics`
- Java package: `com.bettercontent.betterruntimediagnostics`
- Validation: `./gradlew build`

This normalization is a clean break. Worlds, configuration files, and integrations created for earlier identities are not migrated or aliased.
