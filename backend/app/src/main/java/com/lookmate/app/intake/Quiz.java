package com.lookmate.app.intake;

/** The quiz's fixed answers. The codes are the ones the V2 check constraints allow (portfolio photos use the same). */
final class Quiz {

    private Quiz() {
    }

    enum HairType { STRAIGHT, WAVY, CURLY, COILY }

    enum Texture { FINE, MEDIUM, THICK }

    enum Length { PIXIE, SHORT, MEDIUM, LONG }

    enum Service { CUT, COLOR, BALAYAGE, HIGHLIGHTS, EXTENSIONS, BRAIDS, LOCS, SILK_PRESS, PERM, TREATMENT, STYLING, OTHER }

    /** What went wrong with past stylists (brief: "what went wrong"). Free text goes in the note beside these. */
    enum PastIssue {
        DIDNT_LISTEN, NOT_THE_LOOK_I_ASKED_FOR, CUT_TOO_SHORT, COLOR_CAME_OUT_WRONG, DAMAGED_MY_HAIR,
        NO_EXPERIENCE_WITH_MY_HAIR_TYPE, RUSHED, PRICE_SURPRISE, HARD_TO_BOOK, OTHER
    }
}
