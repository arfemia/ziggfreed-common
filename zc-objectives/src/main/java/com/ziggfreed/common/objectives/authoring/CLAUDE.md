# objectives/authoring/ - the quest editor

- `QuestWizard` is an `AuthoringWizard` on zc-presentation's frame (`authoring/`); `QuestEditorBootstrap` registers it with its tool (`AuthoringTools.wizardTool`), and every save goes through zc-core's `ServerContent` (the quest file and its words), never a direct store write.
- `QuestDraft` is the one reader and writer of the quest file the editor saves, pure: it writes over the file it edits, so a leaf the editor has no row for survives, and it writes every reward as a `Claim` (M428). `QuestDraftTest` is the gate on that file's shape.
- A step's key is minted once (`QuestDraft.stepKey`, kept in the session) and never renamed: progress is saved under it. A page's dynamic rows (a slot's heading, a reward kind's parameters) are labelled with `WizardField.withLabel`, their field id as the label key.
- The frame repaints a page only on Back, Next or a search, so a row that depends on another's value (a target on its step's kind, a parameter on its reward's kind) sits on the page after it.
