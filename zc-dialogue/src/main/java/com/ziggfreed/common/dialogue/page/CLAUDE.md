# dialogue/page/ - the conversation page and its opener

- `DialogueOpener` resolves a routed beat before the page exists, so a `Pick` draws once per open.
- `DialoguePage` reads the player from its `PlayerRef`, never from the `ref` it was opened on, which is the NPC (`DialoguePageRenderSubjectTest`).
- Node text goes on `#NodeText.TextSpans`, never `.Text`, so markup renders. An option row is a `TextButton`, so an option's colour comes from its `DialogueOptionStyle` or its `Presentation`, never markup.
