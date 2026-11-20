package com.lookmate.app.identity;

/**
 * What kind of org this is; members get the matching role. A stylist's org is their business (one suite, one
 * booking link); usually one login, but an assistant or a second login can join by invite.
 */
public enum OrgType {
    STYLIST;

    public Role role() {
        return switch (this) {
            case STYLIST -> Role.STYLIST;
        };
    }
}
