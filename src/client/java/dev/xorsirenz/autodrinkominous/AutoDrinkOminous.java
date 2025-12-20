package dev.xorsirenz.autodrinkominous;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.option.GameOptions;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.OminousBottleAmplifierComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.lang.reflect.Field;
import java.util.Map;

public class AutoDrinkOminous extends Module {
    public enum Prioritization {
        LEFTMOST("Leftmost"),
        HIGHEST_LEVEL("Highest Level");

        private final String d;
        Prioritization(String d){this.d=d;}
        @Override public String toString(){return d;}
    }
    public enum AutoDisable {
        DISABLED("None"),
        AFTER_TIME("Time"),
        AFTER_CONSUMPTIONS("Consumptions");

        private final String d;
        AutoDisable(String d){this.d=d;}
        @Override public String toString(){return d;}
    }

    private static final int HOLD_USE_TICKS = 45;
    private static final int RESTORE_SWAP_DELAY_TICKS = 3;
    private static final int COOLDOWN_TICKS = 0;

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Boolean> pullFromInventory = sg.add(new BoolSetting.Builder()
        .name("Pull From Inventory")
        .defaultValue(true)
        .description("If no bottles found in hotbar, move from inventory to hotbar as needed.")
        .build());

    private final Setting<Prioritization> prioritization = sg.add(new EnumSetting.Builder<Prioritization>()
        .name("Prioritize")
        .defaultValue(Prioritization.LEFTMOST)
        .description("Pick the leftmost bottle or the highest Bad Omen level (1 + amplifier).")
        .build());

    private final Setting<AutoDisable> autoDisable = sg.add(new EnumSetting.Builder<AutoDisable>()
        .name("Auto Disable")
        .defaultValue(AutoDisable.DISABLED)
        .description("Disable automatically after a time or a number of bottle consumptions.")
        .build());

    private final Setting<Integer> autoDisableSeconds = sg.add(new IntSetting.Builder()
        .name("Duration (Seconds)")
        .defaultValue(60)
        .min(1).max(Integer.MAX_VALUE)
        .sliderRange(1,100)
        .description("Automatically disable the module after a set amount of time in seconds.")
        .visible(() -> autoDisable.get() == AutoDisable.AFTER_TIME)
        .build());

    private final Setting<Integer> autoDisableConsumptions = sg.add(new IntSetting.Builder()
        .name("Max Consumptions")
        .defaultValue(5)
        .min(1).max(Integer.MAX_VALUE)
        .sliderRange(1,100)
        .description("Automatically disable the module after consuming a set amount of Ominous Bottles.")
        .visible(() -> autoDisable.get() == AutoDisable.AFTER_CONSUMPTIONS)
        .build());

    private int ticksToReleaseUse = -1;
    private int ticksToSwapBack = -1;
    private int prevSlot = -1;
    private boolean useWasForced = false;
    private long lastTriggerTick = -1000;

    private long activationWorldTick = -1;
    private int consumptions = 0;
    private boolean victoryBarVisibleLastTick = false;
    private boolean firedThisVictory = false;

    public AutoDrinkOminous() {
        super(meteordevelopment.meteorclient.systems.modules.Categories.Player,
            "Auto Drink Ominous",
            "Automatically drinks an Ominous Bottle when a raid ends.");
    }

    @Override
    public void onActivate() {
        MinecraftClient mc = MinecraftClient.getInstance();
        activationWorldTick = (mc != null && mc.world != null) ? mc.world.getTime() : -1;
        consumptions = 0;
        firedThisVictory = false;
        victoryBarVisibleLastTick = false;
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive e) {
        if (!(e.packet instanceof PlaySoundS2CPacket pkt)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        try {
            SoundEvent se = pkt.getSound().value();
            String id = Registries.SOUND_EVENT.getId(se).toString();
            if (id.endsWith("event.raid.victory")) {
                if (!firedThisVictory) tryDrink(mc);
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler
    private void onTick(TickEvent.Pre e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.interactionManager == null || mc.inGameHud == null) return;

        if (autoDisable.get() == AutoDisable.AFTER_TIME && activationWorldTick >= 0) {
            long elapsed = mc.world.getTime() - activationWorldTick;
            if (elapsed >= (long) autoDisableSeconds.get() * 20L) {
                say("Auto Drink Ominous: auto-disabled (time elapsed).");
                toggle();
                return;
            }
        }

        boolean victoryNow = isRaidVictoryBarVisible(mc);
        if (!victoryNow) {
            firedThisVictory = false;
        } else if (!victoryBarVisibleLastTick && !firedThisVictory) {
            tryDrink(mc);
        }
        victoryBarVisibleLastTick = victoryNow;

        if (useWasForced) {
            if (ticksToReleaseUse <= 0 || !mc.player.isUsingItem()) {
                mc.options.useKey.setPressed(false);
                useWasForced = false;
                ticksToSwapBack = RESTORE_SWAP_DELAY_TICKS;

                consumptions++;
                if (autoDisable.get() == AutoDisable.AFTER_CONSUMPTIONS && consumptions >= autoDisableConsumptions.get()) {
                    say("Auto Drink Ominous: auto-disabled (consumption limit reached).");
                    toggle();
                    return;
                }
            } else {
                ticksToReleaseUse--;
            }
        } else if (ticksToSwapBack >= 0) {
            if (ticksToSwapBack == 0) {
                if (prevSlot >= 0) {
                    mc.player.getInventory().selectedSlot = prevSlot;
                    if (mc.getNetworkHandler() != null) {
                        mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot));
                    }
                }
                prevSlot = -1;
                ticksToSwapBack = -1;
            } else {
                ticksToSwapBack--;
            }
        }
    }

    private boolean isRaidVictoryBarVisible(MinecraftClient mc) {
        try {
            BossBarHud hud = mc.inGameHud.getBossBarHud();
            for (Field f : hud.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(hud);
                if (v instanceof Map<?, ?> m) {
                    for (Object o : m.values()) {
                        if (o instanceof ClientBossBar bar) {
                            if (isVictoryText(bar.getName())) return true;
                        }
                    }
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isVictoryText(Text t) {
        if (t == null) return false;
        String s = t.getString().toLowerCase();
        return s.contains("raid") && s.contains("victory");
    }

    private void tryDrink(MinecraftClient mc) {
        firedThisVictory = true;
        long now = mc.world.getTime();
        if (now - lastTriggerTick < COOLDOWN_TICKS) return;
        lastTriggerTick = now;

        int slot = selectBottleHotbarSlot(mc);
        if (slot == -1 && pullFromInventory.get()) {
            int tgt = firstEmptyHotbar(mc);
            if (tgt == -1 && mc.player.getInventory().getStack(8).isEmpty()) tgt = 8;
            if (tgt == -1) {
                for (int i = 0; i < 9 && tgt == -1; i++) {
                    if (isOminousBottle(mc.player.getInventory().getStack(i))) tgt = i;
                }
            }
            if (tgt == -1) tgt = (mc.player.getInventory().selectedSlot + 1) % 9;

            int invSlot = findBottleInInventory(mc);
            if (invSlot != -1) {
                InvUtils.move().from(invSlot).toHotbar(tgt);
                slot = tgt;
            }
        }

        if (slot == -1) {
            if (pullFromInventory.get())
                say("No Ominous Bottles found!");
            else
                say("No Ominous Bottles found in hotbar!");
            return;
        }

        int prevSlot = mc.player.getInventory().selectedSlot;
        mc.player.getInventory().selectedSlot = slot;
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        }

        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        GameOptions opts = mc.options;
        opts.useKey.setPressed(true);
        useWasForced = true;
        ticksToReleaseUse = HOLD_USE_TICKS;
    }

    private int selectBottleHotbarSlot(MinecraftClient mc) {
        if (prioritization.get() == Prioritization.LEFTMOST) return findFirstBottleHotbarSlot(mc);
        int best = -1, bestLevel = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && isOminousBottle(st)) {
                int lvl = getOminousLevelSafely(st);
                if (lvl < 0) lvl = 0;
                if (best == -1 || lvl > bestLevel) { best = i; bestLevel = lvl; }
            }
        }
        return best;
    }

    private int findFirstBottleHotbarSlot(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (!st.isEmpty() && isOminousBottle(st)) return i;
        }
        return -1;
    }

    private int firstEmptyHotbar(MinecraftClient mc) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        return -1;
    }

    private int findBottleInInventory(MinecraftClient mc) {
        int best = -1, bestLevel = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack st = mc.player.getInventory().getStack(i);
            if (st.isEmpty() || !isOminousBottle(st)) continue;
            if (prioritization.get() == Prioritization.LEFTMOST) return i;
            int lvl = getOminousLevelSafely(st);
            if (lvl < 0) lvl = 0;
            if (best == -1 || lvl > bestLevel) { best = i; bestLevel = lvl; }
        }
        if (best != -1) return best;
        FindItemResult r = InvUtils.find(this::isOminousBottle);
        if (r.found() && !r.isHotbar()) return r.slot();
        return -1;
    }

    private boolean isOminousBottle(ItemStack st) {
        Item item = st.getItem();
        if (item == Items.OMINOUS_BOTTLE) return true;
        try {
            String id = Registries.ITEM.getId(item).getPath();
            String key = item.getTranslationKey();
            String name = st.getName().getString().toLowerCase();
            return (id != null && id.contains("ominous_bottle"))
                || (key != null && key.toLowerCase().contains("ominous_bottle"))
                || name.contains("ominous bottle");
        } catch (Throwable ignored) {}
        return false;
    }

    private int getOminousLevelSafely(ItemStack stack) {
        try {
            OminousBottleAmplifierComponent comp = stack.get(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER);
            int amp = comp != null ? comp.value() : 0;
            return 1 + amp;
        } catch (Throwable t) { return -1; }
    }

    @Override
    public void onDeactivate() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.options.useKey.setPressed(false);
        useWasForced = false;
        ticksToReleaseUse = -1;
        ticksToSwapBack = -1;
        prevSlot = -1;
        firedThisVictory = false;
        victoryBarVisibleLastTick = false;
    }

    private void say(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }
}
