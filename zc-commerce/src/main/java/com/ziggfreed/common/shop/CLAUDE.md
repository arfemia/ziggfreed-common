# shop/ - the purchase engine

- A queued reward counts as delivered: a purchase refunds only when its offer pays out something and none of it was delivered or queued. An offer that pays out nothing still completes.
- A new question on `ShopOffer` is a new default method, never a new required one.
