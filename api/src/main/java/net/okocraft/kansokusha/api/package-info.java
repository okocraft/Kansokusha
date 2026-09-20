/**
 * Stable, platform-neutral contracts for identifying the local server, registering event types,
 * and submitting events.
 *
 * <p>External providers obtain the active API through
 * {@link net.okocraft.kansokusha.api.Kansokusha#api()}. Types annotated with
 * {@link org.jetbrains.annotations.ApiStatus.Internal} are lifecycle integration
 * points for Kansokusha's platform modules and are not part of the provider API.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package net.okocraft.kansokusha.api;
