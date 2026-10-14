# ui/rows/ - the built-row record and the fixed-slot ledger row

- A `sendUpdate` runs against the DOM of the last full `build`, and a command whose selector resolves to nothing disconnects the player. A new list is the kit's (`ui/kit/LedgerPainter`) and addresses its rows only through the `LedgerIndex` its last full `LedgerPainter.paint` returned. `BuiltRows` is for a list outside the kit, and no library page uses it: `indexOf` answering -1, or `moved(id, liveGroup)` answering true, means reopen the page.
- `SummaryRowRenderer` paints a fixed set of slots the consumer's `.ui` pre-declares (each an `ItemGrid #Icon` and a `Label #Name`), never `cmd.append`, and returns the overflow count for a "+N more" line.
- Pass `subLabelId` only when the consumer's `.ui` declares that second Label: a selector the document lacks crashes the client. Rich-text markup has no font-size tag, so a smaller second line needs its own Label.
- `SummaryRow`, `ui/toast/ToastLine` and zc-loot's `RewardChip` stay separate by design: a toast line is welded to the toast transport, a summary row is a persistent fixed-slot ledger row, and a chip is only an icon and a label.
