package com.lookmate.app.stylists;

/**
 * A stylist's new-client status as clients see it.
 *
 * @param weeksOut "usually about N weeks out", only with {@link NewClientStatus#TAKING_WITH_WAIT}; otherwise null
 */
public record NewClients(NewClientStatus status, Integer weeksOut) {

    public static final NewClients TAKING = new NewClients(NewClientStatus.TAKING, null);

    static final int MAX_WEEKS = 26;
}
