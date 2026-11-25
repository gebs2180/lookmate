package com.lookmate.app.stylists;

import java.util.List;

/**
 * What to expect in a stylist's chair: practice labels, languages besides English, from-prices and the consultation
 * (2026-11-20), the stylist's own first-visit notes and whether they're taking new clients (2026-11-25). The stylist sets
 * all of it, none is needed to go live, and clients see it on the profile and cards only: it is never a directory filter
 * and never reaches ranking or matching (which is why {@link Stylists.LiveProfile} doesn't carry it).
 *
 * @param fromPrices         in the order of the stylist's specialties; only services they offer
 * @param consultation       null when the stylist hasn't said
 * @param priceDependsOnHair "Price depends on length and density. Ask at your consultation."
 * @param firstVisit         the stylist's own "Before your first visit" facts and note
 * @param newClients         whether they're taking new clients; shown only, never a reason to leave them out
 */
public record Practice(List<PracticeLabel> labels, List<Language> languages, List<FromPrice> fromPrices,
                       Consultation consultation, boolean priceDependsOnHair, FirstVisit firstVisit,
                       NewClients newClients) {

    /** A starting price in whole US dollars. */
    public record FromPrice(ServiceType service, int fromDollars) {
    }

    /** The practice section a stylist left empty (it doesn't touch the new-client status, which is saved apart). */
    static Practice none(NewClients newClients) {
        return new Practice(List.of(), List.of(), List.of(), null, false, FirstVisit.NONE, newClients);
    }
}
