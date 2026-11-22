package dev.thallium.mixin;

import dev.thallium.ThalliumClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    // Tab tuşuyla açılan oyuncu listesindeki isim
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void thallium$prefix(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (ThalliumClient.isThallium(entry.getProfile().getId())) {
            cir.setReturnValue(ThalliumClient.prefix(entry.getProfile().getId()).append(cir.getReturnValue()));
        }
    }
}
