package com.bettercontent.betterruntimediagnostics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** A fixture-only value persisted by Minecraft's ordinary world-save path. */
public final class DebugWorldMarker extends SavedData {
    private static final String NAME = "better_runtime_diagnostics_debug_world_marker";
    private String value = "";

    public static DebugWorldMarker get(final ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(DebugWorldMarker::load, DebugWorldMarker::new, NAME);
    }

    public String value() {
        return value;
    }

    public void setValue(final String marker) {
        value = marker;
        setDirty();
    }

    @Override
    public CompoundTag save(final CompoundTag tag) {
        tag.putString("Marker", value);
        return tag;
    }

    private static DebugWorldMarker load(final CompoundTag tag) {
        final DebugWorldMarker marker = new DebugWorldMarker();
        marker.value = tag.getString("Marker");
        return marker;
    }
}
