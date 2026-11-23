package com.lookmate.app.intake;

/**
 * The quiz's own fixed answers. Hair type, texture, length and service are the stylists module's enums
 * ({@code HairType}, {@code Texture}, {@code HairLength}, {@code ServiceType}), so a value added there is accepted
 * here (review 2026-11-22, #38).
 */
final class Quiz {

    private Quiz() {
    }

    /**
     * What went wrong with past stylists (brief: "what went wrong"), plus what the client asks of the next one
     * ({@link #isAsk()}: a quiet appointment, honest advice; docs/research/beyond-the-hair.md, items 2 and 3). Free text
     * goes in the note beside these. None of them feeds ranking: they're shown to the admin, the asks are echoed back to
     * the client as a booking note, and all are kept after anonymisation for the fairness metrics.
     */
    enum PastIssue {
        DIDNT_LISTEN, NOT_THE_LOOK_I_ASKED_FOR, CUT_TOO_SHORT, COLOR_CAME_OUT_WRONG, DAMAGED_MY_HAIR,
        NO_EXPERIENCE_WITH_MY_HAIR_TYPE, RUSHED, PRICE_SURPRISE, HARD_TO_BOOK, OTHER,
        QUIET_APPOINTMENT, HONEST_ADVICE;

        /** A request for the next appointment rather than something that went wrong. */
        boolean isAsk() {
            return this == QUIET_APPOINTMENT || this == HONEST_ADVICE;
        }
    }
}
