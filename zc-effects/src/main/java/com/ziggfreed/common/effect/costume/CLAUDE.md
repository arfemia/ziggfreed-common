# effect/costume/ - the costume a player can always take off

- A costume is an effect that changes the model and is not a `Debuff` (`CostumeRules`); `ZigCostume` puts one on the player a chain targets and tells them `/zigcostume off` takes it off, and taking off goes by that rule, never by a record.
- A different costume is refused while one is worn: the engine keeps the model from before the first model change to restore, and a swap inside one tick would record the first costume's model instead.
- zc-cast is this module's one other library edge, for that Type.
