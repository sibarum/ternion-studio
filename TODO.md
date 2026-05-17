# Ternion Studio — TODO

Post-MVP follow-ups. Items recorded as they surface during MVP work; revisit once the 10 MVP phases land.

## Framework (dasum)

- **Render-time culling in `Scroll`.** Right now every glyph in a Scroll's content is submitted to the batcher even when it's outside the visible viewport. For huge logs (or any long-text scenario) this wastes GPU. `Render.renderScrollContents` should clip descendant glyphs / quads against the visible band before submitting.
- **Per-line draw skipping in `Render.renderText`.** Cheaper version of the above — `TextMetrics` already computes per-line rects; `Render.renderText` can skip lines whose rect doesn't intersect the active scissor / clip. Doesn't require Scroll-aware changes anywhere else. Either this or the Scroll-level cull is enough; both is overkill.
- **Dynamic background-color sidecar.** Component variants store their `Color` in the record, which is immutable — so apps that want to react to runtime state (gradient tint on a node, error highlighting on a widget, variant-driven Status ribbon background) currently have to swap whole Components or settle for indirect indicators. A `DynamicColor` sidecar parallel to `DynamicChildren` would solve this: `DynamicColor.set(c, color)` stores an override, `Render`'s `bgColorFor(c)` reads the override first and falls back to the record. Needs the usual `clear(c)` + `migrate(from, to)` hooks so it integrates with `Components.detach` / `Components.migrateState`. Specific motivating use case: Phase 8 wanted true visual gradient-magnitude tinting on Designer nodes; we shipped a numeric `∇` indicator instead because record-color immutability made the visual variant expensive.

## App (ternion-studio)

- **`Status` "Clear log" button** in the popup. UX polish — lets users reset the history during a session without restarting. Small: `Status.clearLog()` + a button in the popup chrome.

## Deferred MVP scope (re-enter when relevant)

These are items pulled out of MVP phases to keep scope bounded; they aren't post-MVP per se, just paused.

- **Properties pane** (was part of Phase 4). Right-side pane reading/writing the selected node's `Configurable.config()` so Linear dims, Parameter shape, IntToVector vocab/dim, VectorToTensor shape, etc. are editable at design time. Currently they're fixed at spawn.
- **Embed / Lookup palette items + shared `SymbolEmbeddingTable`** (was part of Phase 4). Required for the original MVP "backprop on embeddings and KVs" verification — needs a Vocabulary side panel for adding/removing symbols.
