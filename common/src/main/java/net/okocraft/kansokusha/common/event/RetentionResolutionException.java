package net.okocraft.kansokusha.common.event;

import org.jetbrains.annotations.NotNullByDefault;

@NotNullByDefault
public final class RetentionResolutionException extends Exception {

    public RetentionResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
