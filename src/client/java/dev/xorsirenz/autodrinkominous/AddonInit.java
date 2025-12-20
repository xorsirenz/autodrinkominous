package dev.xorsirenz.autodrinkominous;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class AddonInit extends MeteorAddon {
    @Override
    public void onInitialize() { Modules.get().add(new AutoDrinkOminous()); }

    @Override
    public String getPackage() { return "dev.xorsirenz.autodrinkominous"; }
}
