# shop/asset/

- The file name is the id and the folders under a type root are organisation only, so two files with one name are one id and only one of them loads.
- An offer id is what a player's purchase count is filed under: renaming it, including by widening a generator's `IdPattern`, resets that count.
- A generator row value keeps its JSON type through a one-token substitution, so write a price bare (`"tokens": 75`): `Cost.Currencies` is a number codec, and a quoted price fails the decode of every offer that row writes. A reward `Params` value decodes quoted or bare.
- A generator's `Source` reads the same registered value lists the quest generators read (`CommerceCatalogs.installAxisValues`); never register a list a second time per content type.
- A ladder of near-identical offers is `Listing.Chains`, not a storefront grouping mode.
- `Categories` (what a shelf is called) and `CategoryOrder` (where it sits) are separate storefront leaves, and a shelf's name lives on the storefront, never on each offer.
