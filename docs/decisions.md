# Things deliberately not built

Each of these was considered and rejected for a reason that still holds. They are recorded so the
next person does not spend an afternoon rediscovering the argument — and so that if a premise
changes, it is obvious which decision to reopen.

## Room

The original plan called for a database, on the assumption of an unbounded backlog of unseen posts
to track. Measurement removed that assumption.

The Following timeline emits roughly 186 posts/hour, about 4,500 a day. A full day's fetch would cost
around $669/month. One evening's watching needs about 57 posts, which covers the most recent ~18
minutes. Content arrives roughly **80× faster than it can be watched**.

So there is no backlog to model: there is a fixed head-budget reel and one cursor, and DataStore
holds both comfortably. Room becomes justified again the moment durable per-item history lands —
starring, hiding, watch counts — because that is genuinely relational and genuinely unbounded.

## A navigation rail

With one source there is nothing to navigate between. It would be ceremonial chrome plus one more
place for D-pad focus to get lost. The sibling PHTV/91TV apps have one because they have several
channels; XTV has Following and nothing else.

## Bookmarks and Likes as channels

Measured on the account this was built against: 0 bookmarks and 9 likes. Not enough to be a channel.
Following is the only real source.

## QR code or phone pairing for credentials

Installation is over adb already, so the credentials may as well arrive the same way. A pairing flow
would be a second transport to build and maintain for a step the user is necessarily at a terminal
for.

## DreamService screensaver and Google TV "Watch Next"

Both push this app's content into the system UI of a living-room device shared with other people.
Deliberately not done.

## A "refresh on launch"

Cold start must never spend. Opening the app, resuming a reel and browsing the grid all make zero
requests; every fetch sits behind a keypress whose card already stated its price. A background
refresh would quietly break that contract, which is the one thing making the spending predictable.

## "You're caught up"

At 80:1 it would be a lie. The reel is finite and ends because a fixed budget from the head of the
timeline is a cost necessity, not a style choice.

---

# Still open

- **The 30-minute soak test has never run to completion.** Repeated reinstalls interrupted every
  attempt. Worth doing before trusting the app to play unattended for a whole evening.
- **Reel position is throttled to every third item** (`MainActivity`, `onProgress`). The comment
  claims at most one item is lost on a hard kill; in practice a normal exit can lose up to two,
  because the save only fires on indices divisible by three. Harmless, but the comment overstates it.
