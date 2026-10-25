package com.bettercontent.runtimetestsupport.mixin.fixture;

import com.bettercontent.runtimetestsupport.fixture.FixtureClientInput;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Offline test launches must not attempt online authentication with a deliberately empty token.
 * This supplement never ships in production; the plugin and runtime identity check both fail closed. */
@Mixin(Minecraft.class)
public abstract class OfflineUserApiMixin {
    @Redirect(method = "createUserApiService", at = @At(value = "INVOKE", remap = false,
        target = "Lcom/mojang/authlib/yggdrasil/YggdrasilAuthenticationService;createUserApiService(Ljava/lang/String;)Lcom/mojang/authlib/minecraft/UserApiService;"))
    private UserApiService bc$explicitOfflineClient(YggdrasilAuthenticationService service, String token,
            YggdrasilAuthenticationService originalService, GameConfig config) {
        FixtureClientInput binding = FixtureClientInput.fromProperties(System.getProperties());
        binding.requireActual(config.location.gameDirectory.toPath(), config.user.user.getProfileId(),
            config.user.user.getName(), token);
        LogManager.getLogger("OfflineFixtureClient").info("BC_OFFLINE_CLIENT_BOUND run={} nonce={} uuid={} root={} empty_token=true online_request=false",
            binding.run(), binding.nonce(), binding.playerId(), binding.client());
        return UserApiService.OFFLINE;
    }
}
