package dev.thallium.mixin;

import dev.thallium.ThalliumClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    // Oyuncunun kafasının üstündeki isim etiketi bu metni kullanır
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void thallium$prefix(CallbackInfoReturnable<Text> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (ThalliumClient.isThallium(self.getUuid())) {
            cir.setReturnValue(ThalliumClient.prefix().append(cir.getReturnValue()));
        }
    }
}
