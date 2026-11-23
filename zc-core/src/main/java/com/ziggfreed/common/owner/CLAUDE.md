# owner/ - who administers the server, and which owner files an editor may write

- `OwnerAudience` is the one "may this player change the server's settings" answer: a consumer fills it once at setup, else a player holding `OwnerAudience.NODE` (`/zigadmin`'s engine node) is an owner. Every owner screen asks it on open and on every event, and each library owner page's own audience seam defaults to it.
- An editor writes an owner file only through its `OwnerFileBinding`, registered in `OwnerFileBindings` by the mod that reads the file: the binding owns the path, the Default reading, the writer and the reload, so a form's text never builds a path. A keyed store binds with one `OwnerFileBinding.keyedStore` line; a consumer's own file type passes its own `Below` and `Writer`.
