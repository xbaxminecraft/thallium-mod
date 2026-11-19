package dev.thallium.mixin;

import com.mojang.authlib.GameProfile;
import dev.thallium.ThalliumClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Shadow
    public abstract GameProfile getProfile();

    // Oyuncunun görünümünü (skin, pelerin) veren yer: Thallium pelerini seçmişse pelerin dokusunu değiştir
    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void thallium$cape(CallbackInfoReturnable<SkinTextures> cir) {
        Identifier cape = ThalliumClient.capeFor(getProfile().getId());
        if (cape == null) return;
        SkinTextures o = cir.getReturnValue();
        cir.setReturnValue(new SkinTextures(o.texture(), o.textureUrl(), cape, o.elytraTexture(), o.model(), o.secure()));
    }
}
