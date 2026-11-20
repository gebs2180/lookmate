# Stylist Matching App — Project Brief

The original project brief (2026-11-20). Decisions made since are in `CLAUDE.md` at the repo root.

## What we're building

An app that matches hair clients with stylists based on the look the client wants. The match comes from the stylist's actual portfolio work, not just distance and price.

- Client picks a target style (uploads an inspiration photo or chooses from a library) and gives their hair type and texture.
- *(Phase 2)* Client previews the style on their own photo with an AI try-on.
- The app ranks stylists whose portfolio photos best match that style **on similar hair types**, and shows why ("12 similar cuts on wavy hair").
- The recommendation is a suggestion. Clients can browse and pick any stylist.
- Booking hands off to the stylist's existing booking link (Booksy, Vagaro, StyleSeat, GlossGenius, etc.). We do **not** build a calendar.

## Context

- **Market:** Columbus, Ohio first.
- **Supply side:** Independent stylists at Salon Lofts (suite rentals; HQ in Columbus, ~20 Columbus-area locations). A team member is a working stylist on the Salon Lofts advisory board.
- **Why it matters:** Clients often leave stylists because the stylist "didn't listen" or the fit was wrong. A shared reference image plus portfolio-based matching addresses that.
- **Competitors:** Booksy, StyleSeat, Fresha and Vagaro run marketplaces with distance and price search. None match on style and hair type the way we plan to.

## Phase 1: Pilot MVP (build this first)

Goal: a 90-day pilot with 20–40 stylists. Matching is partly manual. Keep it simple and fast to change.

### Must have
1. **Stylist onboarding:** profile (name, Salon Lofts location, specialties, booking URL, Instagram) and portfolio upload of 10–20 photos.
2. **Photo tagging:** for each photo, the stylist sets service type (cut, color, balayage, extensions, braids, etc.), hair type/texture (straight / wavy / curly / coily, fine / medium / thick), and whether it's a before/after pair.
3. **Client intake:** short style quiz (hair type, texture, length, budget, location/neighborhood, what went wrong with past stylists) plus one inspiration photo.
4. **Matching v1:**
   - Compute image embeddings for portfolio photos and the inspiration photo (an off-the-shelf model such as CLIP or SigLIP; no custom training).
   - Filter by hair-type compatibility and service first, then rank by visual similarity.
   - Return the top 3–5 stylists with a short "why" explanation and the matching portfolio photos.
5. > **Changed 2026-11-20:** matching publishes automatically and the admin monitors after the fact; review before
   > publish is a switch, off (`CLAUDE.md`, Decisions: "Matching publishes automatically; admin monitors").

   **Admin review screen:** an experienced stylist can review, reorder or override matches before the client sees them.
6. **Handoff and tracking:** "Book" goes to the stylist's booking URL with UTM or referral tracking. Record clicks; record bookings and rebookings by manual entry for now.
7. **Basic metrics dashboard:** stylists active, clients matched, booking clicks, confirmed bookings, rebooks.

### Out of scope for Phase 1
- Swipe UI (nice to have; a ranked list is fine for the pilot)
- AI try-on
- In-app booking, payments or messaging
- Native mobile apps (a mobile-first responsive web app is fine)

## Phase 2: After the pilot
- **AI try-on:** license it; don't build it. Perfect Corp / YouCam AI Hair API supports preset styles and "Customize" from a reference photo. Test realism on curly, coily and textured hair before committing.
- Swipe-style browsing of stylists and looks
- Attach the try-on image to the booking so the stylist sees the target look
- Expand beyond Salon Lofts and beyond Columbus

## Suggested stack (open to change; confirm with the team)
- Next.js (TypeScript) web app, mobile-first
- Postgres with `pgvector` for embeddings (Supabase is a quick option: auth, storage, Postgres and pgvector in one)
- Object storage for photos
- Embedding service: a hosted model API or a small Python worker running CLIP/SigLIP

## Data model sketch
- `stylists`: id, name, location, specialties[], booking_url, instagram, active
- `portfolio_photos`: id, stylist_id, image_url, service, hair_type, texture, is_before_after, embedding (vector)
- `clients`: id, contact, hair_type, texture, length, budget, neighborhood, notes
- `match_requests`: id, client_id, inspiration_image_url, embedding, status
- `matches`: id, match_request_id, stylist_id, score, reason, rank, admin_override
- `events`: id, match_id, type (viewed / clicked_book / booked / rebooked), timestamp

## Privacy and trust requirements
- Client face photos are sensitive. Store them privately (no public URLs), delete inspiration and selfie images after a set period, and say so in the privacy policy.
- Ohio has no biometric privacy law, but Illinois (BIPA) and Texas do. Design for them before expanding.
- Portfolio honesty: encourage before/after pairs. Later, consider flagging duplicate or reposted images.
- Check matching quality across hair types so the system doesn't serve textured hair poorly.

## Success metrics for the pilot
| Metric | Target |
| --- | --- |
| Active stylists | 20+ |
| Matched clients who book | 100+ |
| Matched clients who rebook with the same stylist | 50%+ |
| Stylists who'd keep using it | 75%+ |

## Open questions
- App name (not chosen yet). *Since decided: Combpatible, 2026-11-20 (see `CLAUDE.md`).*
- Team roles and equity between the founders
- Whether to stay Salon Lofts–only after the pilot
- Business model: stylist subscription, per-booking fee, or a Salon Lofts partnership deal

## Suggested first tasks for this session
1. Propose and confirm the stack; scaffold the repo.
2. Set up the database schema above, with pgvector.
3. Build stylist onboarding and portfolio upload with tagging.
4. Build client intake with the inspiration upload.
5. Implement matching v1 and the admin review screen.
6. Add booking-link handoff with click tracking.
