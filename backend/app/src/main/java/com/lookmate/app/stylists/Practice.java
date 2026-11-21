package com.lookmate.app.stylists;

import java.util.List;

/**
 * What to expect in a stylist's chair: practice labels, languages besides English, from-prices and the consultation
 * (2026-11-20). The stylist sets all of it, none is needed to go live, and clients see it on the profile only: it is
 * never a directory filter and never reaches ranking or matching (which is why {@link Stylists.LiveProfile} doesn't
 * carry it).
 *
 * @param fromPrices         in the order of the stylist's specialties; only services they offer
 * @param consultation       null when the stylist hasn't said
 * @param priceDependsOnHair "Price depends on length and density. Ask at your consultation."
 */
public record Practice(List<PracticeLabel> labels, List<Language> languages, List<FromPrice> fromPrices,
                       Consultation consultation, boolean priceDependsOnHair) {

    /** A starting price in whole US dollars. */
    public record FromPrice(ServiceType service, int fromDollars) {
    }

    public static final Practice NONE = new Practice(List.of(), List.of(), List.of(), null, false);
}
