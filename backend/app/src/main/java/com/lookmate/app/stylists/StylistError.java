package com.lookmate.app.stylists;

/** Codes the web app can branch on ({@link com.lookmate.app.api.ApiException}). */
enum StylistError {
    /** A profile field is invalid (booking link, Instagram handle, location). */
    INVALID_FIELD,
    /** Publishing, or keeping a LIVE profile LIVE, needs something that's missing. */
    NOT_READY_TO_PUBLISH,
    /** No profile yet: save one first. */
    NO_PROFILE,
    /** The stylist has as many photos as they may keep. */
    PHOTO_LIMIT,
    /** Too many upload attempts today. */
    UPLOAD_RATE,
    /** Not a JPEG or PNG. */
    UNSUPPORTED_TYPE,
    /** Bigger than the upload limit. */
    TOO_LARGE,
    /** Confirmed before anything was uploaded. */
    NOT_UPLOADED,
    /** The upload isn't a readable JPEG or PNG, or isn't what was declared. */
    NOT_AN_IMAGE,
    /** The same photo is already in the portfolio. */
    DUPLICATE_PHOTO,
    /** The same photo is already in another stylist's portfolio. */
    PHOTO_ELSEWHERE,
    /** Another stylist already has that page address (slug). */
    SLUG_TAKEN,
    /** Too many photos being checked at once on this server. */
    BUSY,
    /** The photo's state doesn't allow this (e.g. tagging a pending upload). */
    WRONG_STATE,
    /** A before/after pair that can't be made. */
    INVALID_PAIR,
    /** The new order doesn't list exactly the stylist's photos. */
    INVALID_ORDER
}
