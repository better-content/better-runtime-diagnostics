# Disposable offline client launch and production onboarding

The test-support archive is separate from the production diagnostics mod and must
never be distributed in either candidate ZIP. Its reviewed launch/UI drivers are
not read-only diagnostic observers. `verifyTestSupportIsolation` checks both the
production exclusion and their small compiled authority boundaries.

## Offline authentication

The native harness uses pinned portablemc 4.4.1 `OfflineAuthSession`, which supplies
an empty access token and an explicitly selected offline UUID. Vanilla 1.20.1
otherwise sends that deliberately invalid token to the online User API, logs a
401 authentication exception, and returns `UserApiService.OFFLINE` anyway.

`better_runtime_offline_fixture.mixins.json` has its own plugin. It is disabled
outside an explicit Dist/Debug disposable client binding: run ID, nonce, offline
name/UUID, sibling fixture client/diagnostic paths and all four candidate/support
hashes. Its sole redirect is the authlib call inside Minecraft's pinned
`createUserApiService` method. Before returning the existing OFFLINE implementation,
it rechecks the actual game directory, actual offline user UUID/name and **empty**
token. Real tokens, missing/changed identities, cross-run paths and unbound launches
are never accepted. It neither contacts authentication services nor changes
Minecraft gameplay. The harness retains `BC_OFFLINE_CLIENT_BOUND` in the raw audit;
no authentication exception is classified or suppressed.

## Onboarding before held-map input

A new Better Spawns network player is intentionally a spectator until onboarding.
The held-map driver retains its nonspectator guard and must not force Survival or
flight. `DedicatedOnboardingProbe` acts only after an exact run/nonce/player-bound
request exists in the diagnostic bridge and the native client chunk/production
selection state is ready. It invokes the real
`ClientOnboardingActions.submitSpawnOnly` UI action once. It observes a natural
server response (`SURVIVAL`, noncreative/nonspectator, selection complete and loaded
chunk) before emitting the bound readiness marker. It never changes game mode,
flight, selection state, slots, spawn files or server state directly. The root
harness independently runs the unchanged held-map guard afterward.

`FixtureClientInputTest` covers valid bindings and missing, cross-run, wrong-player,
wrong-path, malformed hash, real-token and stale onboarding-request cases. The
compiled archive contract keeps the single auth redirect and single production
onboarding action separate from the existing noncancelling observers.
