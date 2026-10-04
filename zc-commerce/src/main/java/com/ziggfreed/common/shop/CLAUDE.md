# shop/ - the purchase engine

- A queued reward counts as delivered: a purchase refunds only when its offer pays out something and none of it was delivered or queued. An offer that pays out nothing still completes.
- A new question on `ShopOffer` is a new default method, never a new required one.
- `ShopOffer.storefrontRequires()` is asked before the offer's own `requires()`, so a storefront's lock is the first reason a buyer reads; a storefront's lock locks its stock, it never shuts the page.
