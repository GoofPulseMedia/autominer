package net.autominer;

import net.fabricmc.api.ClientModInitializer;

public class AutoMiner implements ClientModInitializer {
    public static final String MOD_ID = "autominer";

    @Override
    public void onInitializeClient() {
        System.out.println("[AutoMiner] Initializing with chat interception...");
        
        // Initialize AutoMinerClient components  
        AutoMinerClient autoMinerClient = new AutoMinerClient();
        autoMinerClient.onInitializeClient();
        
        System.out.println("[AutoMiner] ✓ Chat message interception active!");
        System.out.println("[AutoMiner] Commands available (WON'T be sent to server):");
        System.out.println("  Type '+automine select' or press F1 - Start area selection");
        System.out.println("  Type '+automine start' or press F2 - Start mining");
        System.out.println("  Type '+automine stop' or press F3 - Stop mining");
        System.out.println("  Type '+automine pause' or press F4 - Pause mining");
        System.out.println("  Type '+automine resume' or press F5 - Resume mining");
        System.out.println("  Type '+automine cancel' or press F6 - Cancel selection");
        System.out.println("  Type '+automine config show' - Show configuration");
        System.out.println("  Type '+automine config set <setting> <value>' - Change settings");
        System.out.println("[AutoMiner] All +automine commands are intercepted and handled locally!");
    }
}
