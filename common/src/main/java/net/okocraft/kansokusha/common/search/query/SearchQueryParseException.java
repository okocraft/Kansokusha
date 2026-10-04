package net.okocraft.kansokusha.common.search.query;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/**
 * Indicates invalid search-query syntax or values, with a localized user-facing reason.
 */
@ApiStatus.Internal
@NotNullByDefault
public final class SearchQueryParseException extends IllegalArgumentException {

    private final Component reason;
    private final @Nullable String expected;

    public SearchQueryParseException(String message, Component reason) {
        this(message, reason, null, null);
    }

    public SearchQueryParseException(String message, Component reason, Throwable cause) {
        this(message, reason, cause, null);
    }

    private SearchQueryParseException(
        String message,
        Component reason,
        @Nullable Throwable cause,
        @Nullable String expected
    ) {
        super(message, cause);
        this.reason = reason;
        this.expected = expected;
    }

    static SearchQueryParseException missing(String expected) {
        return new SearchQueryParseException(
            "missing " + expected, SearchQueryMessages.MISSING.apply(expected), null, expected
        );
    }

    public Component reason() {
        return this.reason;
    }

    @Nullable String expected() {
        return this.expected;
    }
}
