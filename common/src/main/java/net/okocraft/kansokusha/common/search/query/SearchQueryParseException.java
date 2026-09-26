package net.okocraft.kansokusha.common.search.query;

import org.jetbrains.annotations.ApiStatus;

/**
 * Indicates invalid search-query syntax or values.
 */
@ApiStatus.Internal
public final class SearchQueryParseException extends IllegalArgumentException {

    public SearchQueryParseException(String message) {
        super(message);
    }

    public SearchQueryParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
