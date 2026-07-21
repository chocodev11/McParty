package dev.epicc.board.dice;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Left/right click on the roll Interaction settles the active dice session. */
public final class DiceClickListener implements Listener {

    private final DicePresenter presenter;

    public DiceClickListener(DicePresenter presenter) {
        this.presenter = presenter;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        handle(event.getPlayer(), event.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof Interaction)) {
            return;
        }
        if (presenter.trySettleFromEntity(player, event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private void handle(Player player, Entity entity) {
        if (!(entity instanceof Interaction)) {
            return;
        }
        presenter.trySettleFromEntity(player, entity);
    }
}
