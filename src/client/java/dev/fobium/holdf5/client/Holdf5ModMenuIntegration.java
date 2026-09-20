package dev.fobium.holdf5.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class Holdf5ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> Holdf5ConfigScreen.create(parent, Holdf5Client.getConfig());
    }
}
