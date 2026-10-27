# Source-local verification

Run the ordinary exporter/unit tests and build the production runtime archive with:

```sh
JAVA_HOME=$HOME/.sdkman/candidates/java/17.0.17-tem TMPDIR=$HOME/.tmp \
  ./gradlew test stageRuntimeJar --max-workers=2 --console=plain
```

`RuntimeSnapshotWriterTest` writes real temporary JSON documents through the same
publication boundary as `RecipeGraphExporter.dump`. It checks the required manifest,
shared snapshot/world/time identity, explicit incomplete metadata, rejected mixed or
missing documents, and removal of stale completion markers on write failure. This is
not a live recipe-manager integration test or a consumer's content-policy/promotion
check. The other exporter tests retain their normal owning-repository scope.

## Optional nonshipping support archive

The existing `testSupport` source set and its `testSupportJar` artifact are not part
of production diagnostics. Their broad native SDK/provider closure is resolved only
when explicitly requesting a task whose name contains `testSupport` (including
`verifyTestSupportIsolation`) or passing `-PwithTestSupport=true`. An external build
that depends indirectly on that artifact must set the property explicitly.

This guard is necessary because ForgeGradle shares `fg.deobf` resolution across
source sets: otherwise even `compileJava` and `test` attempt to resolve optional
fixture SDKs they never use. Ordinary `test`, `stageRuntimeJar` and production
`assemble` do not require these providers. Native-free `testSupportPure` helpers
remain available to the existing local unit tests without the optional archive.

Explicit support builds still require their declared/pinned native dependencies and
an explicit qualified `BC_CUSTOM_MOD_JAR_DIR` provider stage. Missing retired
providers are errors for that optional artifact, not a reason to pull sibling pack
sources or add fallback/stub JARs to normal source-local verification. See
[fixture-client-launch.md](fixture-client-launch.md) for the existing artifact's
authority boundary; this does not add a pack acceptance tier or deployment step.
