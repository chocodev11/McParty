package dev.epicc.minigame;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Snapshots and restores a player's inventory, gamemode, flying state, and attributes.
 */
public final class PlayerStateSnapshot {

    private final ItemStack[] contents;
    private final ItemStack[] armor;
    private final ItemStack offHand;
    private final GameMode gameMode;
    private final double health;
    private final int foodLevel;
    private final float exp;
    private final int level;
    private final float walkSpeed;
    private final float flySpeed;
    private final boolean allowFlight;
    private final boolean isFlying;
    private final Collection<PotionEffect> activePotionEffects;

    private PlayerStateSnapshot(Player player) {
        this.contents = cloneItemStacks(player.getInventory().getContents());
        this.armor = cloneItemStacks(player.getInventory().getArmorContents());
        this.offHand = player.getInventory().getItemInOffHand().clone();
        this.gameMode = player.getGameMode();
        this.health = player.getHealth();
        this.foodLevel = player.getFoodLevel();
        this.exp = player.getExp();
        this.level = player.getLevel();
        this.walkSpeed = player.getWalkSpeed();
        this.flySpeed = player.getFlySpeed();
        this.allowFlight = player.getAllowFlight();
        this.isFlying = player.isFlying();
        this.activePotionEffects = new ArrayList<>(player.getActivePotionEffects());
    }

    public static PlayerStateSnapshot capture(Player player) {
        return new PlayerStateSnapshot(player);
    }

    /**
     * Clears inventory, removes active potion effects, and sets player to SURVIVAL mode for a new phase.
     */
    public static void preparePhase(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.getInventory().clear();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFireTicks(0);
        try {
            player.setHealth(player.getMaxHealth());
        } catch (Exception ignored) {
        }
        player.setFoodLevel(20);
    }

    public void restore(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.getInventory().clear();
        player.getInventory().setContents(cloneItemStacks(contents));
        player.getInventory().setArmorContents(cloneItemStacks(armor));
        player.getInventory().setItemInOffHand(offHand != null ? offHand.clone() : null);

        player.setGameMode(gameMode);
        try {
            player.setHealth(Math.min(health, player.getMaxHealth()));
        } catch (Exception ignored) {
        }
        player.setFoodLevel(foodLevel);
        player.setExp(exp);
        player.setLevel(level);
        player.setWalkSpeed(walkSpeed);
        player.setFlySpeed(flySpeed);
        player.setAllowFlight(allowFlight);
        player.setFlying(isFlying);

        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : activePotionEffects) {
            player.addPotionEffect(effect);
        }
    }

    private static ItemStack[] cloneItemStacks(ItemStack[] original) {
        if (original == null) {
            return new ItemStack[0];
        }
        ItemStack[] copy = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            if (original[i] != null) {
                copy[i] = original[i].clone();
            }
        }
        return copy;
    }
}
