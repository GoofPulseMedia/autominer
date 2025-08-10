package net.autominer;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // This method tells Mod Menu which screen to open when the user clicks
        // the "Configure" button for this mod in the mod menu
        return parent -> new AutoMinerConfigScreen(parent, AutoMinerClient.getConfig());
    }
}
