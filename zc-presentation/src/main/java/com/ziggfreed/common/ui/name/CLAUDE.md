# ui/name/ - how a menu names a player

- `PlayerDisplayNames.displayName(playerId, storedName)` is the one name a menu or leaderboard row paints: the live username, else the stored name, else the first eight characters of the id, decorated by whatever a higher module filled (`fillDecorator`; titles fill it). Paint it on a Label's `.TextSpans`: a decorated name is a parameterized Message, which a `.Text` sink prints with its `{0}` showing.
- A decorator runs on the viewer's world thread for players who may be on another world: it reads process-wide state only, never an entity store, and one that throws costs the row its decoration, never its name.
- Menus and leaderboards only: a chat line or a notice names a player plainly and never calls this seam.
