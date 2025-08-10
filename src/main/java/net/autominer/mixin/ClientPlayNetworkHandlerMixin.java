package net.autominer.mixin;

import net.autominer.AutoMinerClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {
    
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void interceptChatMessage(String content, CallbackInfo ci) {
        if (content.startsWith("+")) {
            // Handle the +automine command locally and prevent sending to server
            AutoMinerClient.handleAutomineCommand(content);
            ci.cancel(); // Cancel the original method to prevent sending to server
        }
    }
}
