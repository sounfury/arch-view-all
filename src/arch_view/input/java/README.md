# Java adapter (reserved)

Java analysis is not implemented yet. Add `dependency_extract.clj` here and
register it in `arch-view.input.languages` when ready.

The adapter's `build-module-graph` accepts a project path and source paths and
returns `:nodes` (set of dotted module names), `:edges` (set of `{:from ... :to ...}`),
`:abstract-modules` (set), and `:module->source-file` (absolute source paths).
The shared model, layout, and renderer consume this graph without language logic.
