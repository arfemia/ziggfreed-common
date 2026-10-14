# objectives/title/command/ (`/zigtitle`)

- `--player` takes a player online or not: `TitleTargetCommand` is the family's fill of zc-core's `AbstractTargetPlayerCommand` in its typed-name form, and `TitlePlayers` says who a name means, in `ClaimTargets`' order (their javadocs are the reference; the profile-service step needs an authenticated server). An online player has their queue paid on their world thread first (`TitleTargetCommand.buildTarget`); for one offline a grant or revoke queues through `ZigTitles` and a list reads the title record.
- `open` (`TitleOpenCommand`) is a player's verb in the engine's adventurer group inside the admin family, so every player can open their own picker.
