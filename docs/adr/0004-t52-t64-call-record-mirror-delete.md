# ADR 0004: T5.2 call-record + T6.4 oracle-required (mirror-delete)

- Status: accepted
- Date: 2026-07-31
- Depends: ADR 0002 product-shell; ADR 0003 cljs dual-source; com-cloudflare#18/#19; murakumo T5.2/T6.4
- WBS: T5.2 product host bridge + T6.4 same pure artifact after preload

## Decision

1. **T5.2** — Port `project-field` / `map->args` / `call-record` into
   `cloudflare.kotoba.oracle`. Multi-arg pure hosts use structural maps:
   `collection-path`, `item-path`, `id-with-prefix`, `as-int-string`,
   `as-bool-string`, `clamp-limit`, `fact-attr`.

2. **T6.4** — Delete `try-oracle` + host pure mirrors. Pure helpers call
   `require-ready!` then oracle. Zero-arg tokens stay `oracle/call []`.
   Add `preload!` / `preload-catalog!` for nbb/browser entrypoints.

**Still host:** entity-specs, handlers, `*store*`, `now` / `rand-hex16`,
float coerce, filter/paginate/expand folds.

## Non-claims

- No native guest `[:record …]` parameter wire.
- No T8.3 signed-wasm production readiness.

## Evidence

JVM suite (authority + parity + main + call-record) green.
