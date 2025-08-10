package net.autominer.mixin;

import net.autominer.AutoMinerClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {

    @Inject(method = "damage", at = @At("HEAD"))
    private void onDamage(ServerWorld world, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        // FIX: Added ServerWorld parameter for MC 1.21.8 compatibility
        // FIX: Replaced the damage type check with a more robust string comparison.
        if (source.getName().equals("fall")) {
            if (AutoMinerClient.getMiningLogic() != null) {
                AutoMinerClient.getMiningLogic().onFallDamage();
            }
        }
    }
}
