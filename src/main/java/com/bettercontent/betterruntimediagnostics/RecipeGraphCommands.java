package com.bettercontent.betterruntimediagnostics;

import com.bettercontent.betterruntimediagnostics.diagnostics.PlaytestDiagnostics;
import com.bettercontent.betterruntimediagnostics.diagnostics.ExportOutcome;
import com.bettercontent.betterruntimediagnostics.diagnostics.PlaytestLog;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class RecipeGraphCommands {
    private RecipeGraphCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("runtimedata")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("dump").executes(context -> {
                    boolean observing = PlaytestDiagnostics.LOG.enabled("export");
                    long started = observing ? System.nanoTime() : 0;
                    if (observing) PlaytestDiagnostics.LOG.event("export", "dump.started", "server", "started", "operator_command", null);
                    var result = RecipeGraphExporter.dump(context.getSource().getServer());
                    if (observing) {
                        PlaytestDiagnostics.LOG.event("export", "dump.completed", "server",
                                ExportOutcome.dump(result.success(), result.complete()),
                                result.success() ? "exporter_returned" : "exporter_failure",
                                () -> PlaytestLog.fields("snapshot", result.snapshotId(), "recipes", result.recipeCount(),
                                        "partial", result.partialCount(), "errors", result.errorCount(),
                                        "elapsed_ms", (System.nanoTime() - started) / 1_000_000));
                    }
                    if (result.success()) {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Recipe graph " + result.snapshotId() + ": " + result.recipeCount()
                                        + " recipes, " + result.partialCount() + " partial, "
                                        + result.errorCount() + " errors, "
                                        + (result.complete() ? "complete" : "INCOMPLETE")
                                        + " -> " + result.outputDirectory()), true);
                        if (!result.complete()) {
                            context.getSource().sendFailure(Component.literal(
                                    "Runtime evidence is incomplete and must not be promoted; inspect snapshot.json and recipe issues."));
                        }
                        return result.complete() ? 1 : 0;
                    }
                    context.getSource().sendFailure(Component.literal("Recipe graph dump failed: " + result.message()));
                    return 0;
                }))
                .then(Commands.literal("combat").executes(context -> {
                    boolean observing = PlaytestDiagnostics.LOG.enabled("export");
                    long started = observing ? System.nanoTime() : 0;
                    var result = CombatProfileExporter.dump(context.getSource().getServer());
                    if (observing) {
                        PlaytestDiagnostics.LOG.event("export", "combat.completed", "server", ExportOutcome.combat(result.success()),
                                result.success() ? "exporter_returned" : "exporter_failure",
                                () -> PlaytestLog.fields("samples", result.sampledEntities(), "excluded_bosses", result.excludedBosses(),
                                        "errors", result.errors(), "elapsed_ms", (System.nanoTime() - started) / 1_000_000));
                    }
                    if (result.success()) {
                        context.getSource().sendSuccess(() -> Component.literal(
                                "Combat profile: " + result.sampledEntities() + " hostile samples, "
                                        + result.excludedBosses() + " boss exclusions, " + result.errors()
                                        + " sampling issues -> " + result.output()), true);
                        return 1;
                    }
                    context.getSource().sendFailure(Component.literal("Combat profile dump failed: " + result.message()));
                    return 0;
                }));
        if (Boolean.getBoolean("bc.pack_test.debug")) {
            root.then(Commands.literal("geometry")
                    .then(Commands.argument("id", StringArgumentType.word()).executes(context -> {
                        String id = StringArgumentType.getString(context, "id");
                        try {
                            var output = DebugGeometryProbe.capture(context.getSource().getPlayerOrException(), id);
                            context.getSource().sendSuccess(() -> Component.literal("BC_GEOMETRY_PROBE id=" + id + " path=" + output), true);
                            return 1;
                        } catch (Exception failure) {
                            context.getSource().sendFailure(Component.literal("BC_GEOMETRY_PROBE_FAILED id=" + id + " reason=" + failure.getMessage()));
                            return 0;
                        }
                    })));
        }
        event.getDispatcher().register(root);
    }
}
