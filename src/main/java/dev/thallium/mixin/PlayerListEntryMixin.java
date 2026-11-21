package dev.thallium.mixin;

import dev.thallium.ThalliumClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void thallium$entrySkin(CallbackInfoReturnable<SkinTextures> cir) {
        PlayerListEntry self = (PlayerListEntry) (Object) this;
        UUID id = self.getProfile().getId();
        Identifier skin = ThalliumClient.skinFor(id);
        Identifier cape = ThalliumClient.capeFor(id);
        if (skin == null && cape == null) return;

        SkinTextures o = cir.getReturnValue();
        SkinTextures.Model model = skin == null ? o.model()
                : (ThalliumClient.slimFor(id) ? SkinTextures.Model.SLIM : SkinTextures.Model.WIDE);

        cir.setReturnValue(new SkinTextures(
                skin != null ? skin : o.texture(),
                skin != null ? null : o.textureUrl(),
                cape != null ? cape : o.capeTexture(),
                o.elytraTexture(),
                model,
                o.secure()
        ));
    }
}
