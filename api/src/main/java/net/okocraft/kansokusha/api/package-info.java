/**
 * Stable, platform-neutral contracts for identifying the local server, registering event types,
 * and submitting events.
 *
 * <p>External providers obtain the active API through
 * {@link net.okocraft.kansokusha.api.Kansokusha#api()}. Types or members annotated with
 * {@link org.jetbrains.annotations.ApiStatus.Internal} are implementation integration
 * points and are not part of the provider API.</p>
 */
@org.jetbrains.annotations.NotNullByDefault
package net.okocraft.kansokusha.api;
