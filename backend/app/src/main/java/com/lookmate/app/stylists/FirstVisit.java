package com.lookmate.app.stylists;

/**
 * The stylist's own "Before your first visit" facts and note (idea 1, docs/research/first-visit-notes.md). All
 * optional; clients see them before the generic notes, and they win where the two disagree.
 *
 * @param colourConsultationFirst "New colour clients need a consultation first"
 * @param depositForNewClients    "Takes a deposit from new clients": a fact only, Combpatible never collects money
 * @param note                    a short free-text note ("Park in the back lot; I'm in loft 12"), or null
 */
public record FirstVisit(boolean colourConsultationFirst, boolean depositForNewClients, String note) {

    public static final FirstVisit NONE = new FirstVisit(false, false, null);

    static final int MAX_NOTE = 280;
}
