package net.okocraft.kansokusha.api.actor;

/**
 * Platform-neutral identity of who or what directly performed an event.
 *
 * <p>Only the direct actor is recorded. Indirect attribution, such as the player who shot a
 * projectile or lit a TNT, is left to the event payload.</p>
 */
public sealed interface EventActor permits PlayerActor, EntityActor, BlockActor {
}
