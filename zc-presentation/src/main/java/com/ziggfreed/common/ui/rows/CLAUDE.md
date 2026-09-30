# ui/rows/ - the built-row record and the fixed-slot ledger row

- A `sendUpdate` runs against the DOM of the last full `build`, and a command whose selector resolves to nothing disconnects the player. Address list rows through `BuiltRows`: `indexOf` answering -1, or `moved(id, liveGroup)` answering true, means reopen the page. A new two-panel list page adopts `BuiltRows` rather than keeping its own list of row ids.
- `SummaryRowRenderer` paints a fixed set of slots the consumer's `.ui` pre-declares (each an `ItemGrid #Icon` and a `Label #Name`), never `cmd.append`, and returns the overflow count for a "+N more" line.
- Pass `subLabelId` only when the consumer's `.ui` declares that second Label: a selector the document lacks crashes the client. Rich-text markup has no font-size tag, so a smaller second line needs its own Label.
- `SummaryRow`, `ui/toast/ToastLine` and zc-loot's `RewardChip` stay separate by design: a toast line is welded to the toast transport, a summary row is a persistent fixed-slot ledger row, and a chip is only an icon and a label.
