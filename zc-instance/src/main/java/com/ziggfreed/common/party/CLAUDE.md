# party/

- A consumer holds one `PartyService` and supplies policy plus pre-built `Message`s (`PartyMessages`, `page/PartyScreenMessages`); nothing mod-specific enters this package.
- Delivery is packet-only (`Universe.getPlayer` plus `Notify`/`EventTitles`), never a `Store` or `Ref` read. Mutations are `synchronized`, and a player belongs to one party at a time (`partyOf`).
- A party queues as a unit: the Queue button calls the consumer's `PartyQueueHandler`, which routes into `LobbyService.queueParty`. A private party queues under `QueueKey.privateQueue(gameId, presetId, partyId)`, and a null `presetId` (the page opened standalone) falls back to the consumer's default preset.
- Party settings are pack-authored under `Server/ZiggfreedCommon/Party/`; read `PartySettingsConfig.getInstance().resolveOrDefault(...)` lazily, after assets load.
- The invite search is the shared `ZigSearchRow`: Search submits and nothing binds per keystroke.
