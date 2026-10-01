# subject/ - who an engine operation is about

- Read a handle only through `Subject.handleAs`, never the raw `handle()` component: a rich handle answers through `HandleFacets` (the direct cast wins, a wrong-typed facet is discarded, a null facet is no answer), and a subject built by `withFacets` holds a layered wrapper.
- A pass-scoped collector rides the Subject's handle beside the player, layered with `Subject.withFacets`, because every grant and every nested pass a grant opens already carries the same Subject. The original handle answers first, then the first extra of the asked type; never give a pass its own copy of a registry to reach a collector.
