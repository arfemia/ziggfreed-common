# cost/ - the one price

- Price anything, a reroll or an unlock included, with a `Cost`: never add a one-currency-and-amount pair, which leaves a multi-currency price unauthorable.
- Scale a price once, in `ShopEngine.priceFor`: a grown `Cost` keeps its curve, so scaling it again compounds it. A folded price carries no curve and never scales.
