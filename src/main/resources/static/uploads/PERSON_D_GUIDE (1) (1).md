# Person D — Budget, Calendar & Community

**Screens you own:** Trip Budget & Cost Breakdown · Trip Calendar/Timeline
View · Shared/Public Itinerary View · Community Tab
**Models you touch:** `Expense`, `CommunityPost`, `Comment`, `Like`, `Trip`
(read + `isPublic`/`shareToken` fields)
**Route files you own:** `routes/expenses.js` (nested under trips, plus
`/budget-summary`), `routes/community.js`, and the sharing bits of
`routes/trips.js` (`/share`, `/copy`, `/public/:shareToken`, `/calendar`
— coordinate with Person B since these live in their file; ask them to
stub these five routes early so you can build against them)

Read `DATABASE_SCHEMA.md` and `API_ARCHITECTURE.md` first. Auth is
cookie-based and already built by Person A — every fetch you make to a
protected route needs `credentials: 'include'`, never an `Authorization`
header. The public share route is the one exception with no auth at all.

---

## Build order

1. Budget & Cost Breakdown (depends on Person C's `ItineraryItem.cost` data
   existing, but you can build the UI against mock numbers first)
2. Calendar View (depends on Person B's `Trip` data)
3. Sharing (`/share`, `/copy`, public view)
4. Community Tab (can be built anytime — it's fairly standalone)

---

## 1. Trip Budget & Cost Breakdown

**Endpoints:**
- `GET /api/trips/:tripId/expenses`
- `POST /api/trips/:tripId/expenses` `{ category, amount, description?, date? }`
- `PATCH /api/trips/:tripId/expenses/:id`
- `DELETE /api/trips/:tripId/expenses/:id`
- `GET /api/trips/:tripId/budget-summary` — computed, see below

**What `budget-summary` needs to return:**
```json
{
  "byCategory": {
    "TRANSPORT": 120.00,
    "ACCOMMODATION": 400.00,
    "MEALS": 80.00,
    "ACTIVITIES": 250.00,
    "OTHER": 30.00
  },
  "total": 880.00,
  "totalBudget": 1000.00,
  "averagePerDay": 125.71,
  "perDay": [
    { "day": 1, "spend": 90.00, "overBudget": false },
    { "day": 2, "spend": 210.00, "overBudget": true }
  ]
}
```
`ACTIVITIES` in `byCategory` is `SUM(ItineraryItem.cost)` across all of the
trip's stops — **not** a stored `Expense` row, so don't double-count if a
user also manually logs an `ACTIVITIES`-category expense; keep those two
sums separate in the query and only combine them at the `byCategory.ACTIVITIES`
level, documenting that clearly in your route so the next person
maintaining it doesn't "fix" it into a double-count.

**UI checklist:** pie chart (by category), bar chart or list (per-day
spend, flagging over-budget days), a form to add manual expenses
(transport/stay/meals/other).

**Prompt:**
```
Build the Trip Budget & Cost Breakdown screen for GlobeTrotter.

Context:
- Auth is cookie-based, all fetches need credentials: 'include'.
- Endpoints:
  GET /api/trips/:tripId/expenses
  POST /api/trips/:tripId/expenses { category, amount, description?, date? }
    category is one of TRANSPORT | ACCOMMODATION | ACTIVITIES | MEALS | OTHER
  PATCH /api/trips/:tripId/expenses/:id
  DELETE /api/trips/:tripId/expenses/:id
  GET /api/trips/:tripId/budget-summary -> { byCategory, total, totalBudget,
    averagePerDay, perDay: [{ day, spend, overBudget }] }
- On the backend, compute budget-summary as: manual Expense sums grouped
  by category, PLUS SUM(ItineraryItem.cost) across all of the trip's
  TripStops folded into byCategory.ACTIVITIES. total = sum of all
  categories. overBudget per day compares that day's spend against
  totalBudget / number of trip days.
- Use a charting library (recharts or chart.js).

Build:
1. A pie chart of byCategory.
2. A bar chart (or annotated list) of perDay spend, highlighting days
   where overBudget is true.
3. A simple form to log a manual expense (category dropdown, amount,
   description, date) that POSTs and refetches the summary.
4. Implement GET /api/trips/:tripId/budget-summary on the backend with
   Prisma groupBy + aggregate queries exactly as described above.
```

---

## 2. Trip Calendar / Timeline View

**Endpoint:** `GET /api/trips/calendar` (ask Person B to add this to their
`routes/trips.js` early, or build it yourself in that file if you're
pairing — it's a two-line Prisma query: the current user's trips with just
`id, name, startDate, endDate`)

**UI checklist:** month grid, previous/next navigation, trip names shown
across the date cells they span (matching the wireframe: "PARIS TRIP",
"NYC — GETAWAY", "JAPAN ADVENTURE").

**Prompt:**
```
Build the Calendar View screen for GlobeTrotter.

Context:
- Auth is cookie-based, all fetches need credentials: 'include'.
- GET /api/trips/calendar -> [{ id, name, startDate, endDate }] for the
  logged-in user's trips.

Build:
1. A month-grid calendar (7 columns, Sun-Sat) with previous/next month
   navigation, matching the wireframe.
2. For each trip returned, render its name across the calendar cells its
   date range spans (a single-day trip gets one cell, a multi-day trip
   spans a horizontal bar across its cells like "NYC - GETAWAY" in the
   wireframe).
3. Clicking a trip name navigates to /trips/:id.
4. Use a lightweight approach (CSS grid) rather than a heavy calendar
   library unless the team already has one in place.
```

---

## 3. Sharing (Shared/Public Itinerary View)

**Endpoints:**
- `POST /api/trips/:id/share` (owner) — toggles `isPublic`, generates/
  returns `shareToken` if not already set
- `GET /api/trips/public/:shareToken` (public, no auth) — read-only trip
  detail, **must check `isPublic === true` server-side**, not just that
  the token matched
- `POST /api/trips/:id/copy` (auth) — clones a public trip's stops/items
  into the current user's own trips

**UI checklist:** a "Share" toggle/button on the trip view (wherever
Person C's Itinerary View lives) that shows the public URL once enabled; a
standalone read-only public page at that URL with a "Copy Trip" button.

**Prompt:**
```
Build trip sharing for GlobeTrotter: a share toggle plus a public
read-only itinerary page.

Context:
- POST /api/trips/:id/share (owner-only) toggles Trip.isPublic; if
  turning it on and shareToken is null, generate one (crypto.randomUUID()
  is fine) and persist it, then return { isPublic, shareToken }.
- GET /api/trips/public/:shareToken is public (no auth), returns the full
  trip with stops and itinerary items IF isPublic is true, otherwise 404.
  This check must happen server-side even if the token is correct.
- POST /api/trips/:id/copy (auth) accepts either a shareToken or a public
  tripId, and creates a new Trip + TripStops + ItineraryItems owned by
  req.user.id, copied from the source trip.

Build:
1. A "Share" button/toggle (place it on the Itinerary View screen) that
   calls POST /api/trips/:id/share and displays the resulting public URL
   (e.g. /trip/shared/:shareToken) once enabled, with a copy-to-clipboard
   action.
2. A standalone public page at /trip/shared/:shareToken that fetches
   GET /api/trips/public/:shareToken with no credentials needed, renders
   a read-only version of the itinerary, and shows a "Copy Trip" button
   (visible whether or not the viewer is logged in — clicking it while
   logged out should prompt login first, then call
   POST /api/trips/:id/copy).
3. Implement all three backend routes above with the ownership and
   isPublic checks exactly as described.
```

---

## 4. Community Tab

**Endpoints:**
- `GET /api/community/posts?search=&sortBy=`
- `POST /api/community/posts` `{ title, content, imageUrl?, tripId? }`
- `GET /api/community/posts/:id`
- `PATCH/DELETE /api/community/posts/:id` (owner)
- `POST /api/community/posts/:id/comments`
- `DELETE /api/community/comments/:id` (owner)
- `POST /api/community/posts/:id/like` (toggle)

**UI checklist:** search/group/filter/sort bar, a scrollable feed of
posts (image + text), like/comment affordances per post, a "share your
trip" composer that can optionally attach one of the user's own trips
(`tripId`).

**Prompt:**
```
Build the Community Tab for GlobeTrotter.

Context:
- Auth is cookie-based, all fetches need credentials: 'include'. Reading
  posts can be public; posting/liking/commenting requires auth.
- Endpoints:
  GET /api/community/posts?search=&sortBy=  -> paginated feed
  POST /api/community/posts { title, content, imageUrl?, tripId? }
  GET /api/community/posts/:id  -> post + comments + likeCount +
    likedByMe
  PATCH/DELETE /api/community/posts/:id (owner only)
  POST /api/community/posts/:id/comments { content }
  DELETE /api/community/comments/:id (owner only)
  POST /api/community/posts/:id/like  -> toggles a Like row on
    [postId, userId], returns { liked, likeCount }

Build:
1. Feed screen: search bar + group/filter/sort controls at top, then a
   vertical stack of post cards (image, title, content preview, like
   count, comment count), matching the wireframe's simple boxed layout.
2. A "New Post" composer (title, content, optional image URL, optional
   "attach a trip" dropdown pulling from the user's own trips) that
   POSTs to /api/community/posts.
3. Post detail view: full content, comment list, a comment input box,
   and a like button that optimistically toggles then confirms against
   the response.
4. Implement all backend routes above, using a unique constraint on
   [postId, userId] for Like to make the toggle an upsert-or-delete.
```

---

## Definition of done for your slice

- [ ] Budget summary correctly sums manual expenses + itinerary item
      costs without double-counting Activities.
- [ ] Calendar renders real trip date ranges, spanning multiple cells
      correctly for multi-day trips.
- [ ] Public share links work for a logged-out visitor and correctly
      404 when `isPublic` is false, even with a guessed/valid-looking token.
- [ ] "Copy Trip" produces a fully independent copy owned by the copying
      user (no shared foreign keys back to the original trip's stops/items).
- [ ] Community feed, posting, commenting, and liking all work end-to-end.

## Handoffs

- **C → you:** your budget summary is only correct once
  `ItineraryItem.cost` is being saved for real — coordinate on a shared
  test trip to verify the sum matches what's shown in Itinerary View.
- **B → you:** you need `Trip.startDate/endDate` and the `/calendar`
  endpoint from their route file — ask them to stub it early rather than
  waiting until Phase 3.
- **You → A:** none — your slice doesn't depend on Admin/Profile at all.
