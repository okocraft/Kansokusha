package net.okocraft.kansokusha.paper.builtin;

import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

final class PaperGenericEntityBreakEventFixture extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Entity entity;
    private final Entity remover;
    private final DamageSource damageSource;
    private final RemoveCause cause;
    private boolean cancelled;
    private int removerReads;

    PaperGenericEntityBreakEventFixture(
        Entity entity,
        Entity remover,
        DamageSource damageSource,
        RemoveCause cause
    ) {
        this.entity = entity;
        this.remover = remover;
        this.damageSource = damageSource;
        this.cause = cause;
    }

    public Entity getEntity() {
        return this.entity;
    }

    public Entity getRemover() {
        this.removerReads++;
        return this.remover;
    }

    public DamageSource getDamageSource() {
        return this.damageSource;
    }

    public RemoveCause getCause() {
        return this.cause;
    }

    int removerReads() {
        return this.removerReads;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    static HandlerList getHandlerList() {
        return HANDLERS;
    }

    enum RemoveCause {
        ENTITY,
        EXPLOSION
    }
}
