package net.autominer.mixin;

import net.autominer.AutoMinerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {
    
    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void interceptChatMessage(String chatText, boolean addToHistory, CallbackInfo ci) {
        if (chatText.startsWith("+")) {
            // Handle the +automine command locally and prevent sending to server
            handleAutomineCommand(chatText);
            ci.cancel(); // Cancel the original method
        }
    }
    
    private void handleAutomineCommand(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        String[] parts = message.split(" ");
        
        if (parts.length < 2) {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c+automine usage: select, start, stop, pause, resume, cancel"), false);
            }
            return;
        }
        
        String command = parts[1].toLowerCase();
        
        switch (command) {
            case "select":
                AutoMinerClient.selectArea();
                break;
            case "start":
                AutoMinerClient.startMining();
                break;
            case "stop":
                AutoMinerClient.stopMining();
                break;
            case "pause":
                AutoMinerClient.pauseMining();
                break;
            case "resume":
                AutoMinerClient.resumeMining();
                break;
            case "cancel":
                AutoMinerClient.cancelSelection();
                break;
            default:
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§cUnknown +automine command: " + command), false);
                }
                break;
        }
    }
}
