package com.bettercontent.runtimetestsupport.fixture;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** No injection at all outside an explicitly hash-bound disposable offline client. */
public final class OfflineClientMixinPlugin implements IMixinConfigPlugin {
    private boolean enabled;
    @Override public void onLoad(String name) {
        try { FixtureClientInput.fromProperties(System.getProperties()); enabled = true; }
        catch (IllegalArgumentException absentOrInvalidFixture) { enabled = false; }
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        return enabled && "net.minecraft.client.Minecraft".equals(target)
            && "com.bettercontent.runtimetestsupport.mixin.fixture.OfflineUserApiMixin".equals(mixin);
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
