# ADR 0003: W6 cljs dual-source product-shell for com-cloudflare-compat

- Status: Accepted
- Date: 2026-07-28

## Context

ADR 0002 host-wired pure helpers on the JVM only. `cloudflare.kotoba.oracle`
threw on cljs resource load. com-cloudflare#17 landed the same dual-source pattern.

## Decision

1. Optional cljs oracle load: `register-kir!` / `set-resource-loader!` / node-fs
   `resources/` + `as-i64` / `i64->host`.
2. Dual-source pure helpers via `try-oracle` on `cloudflare.main`:
   constants, paths, clamp-limit, fact-attr, id-with-prefix, as-int/as-bool
   string branches, status codes, message prefixes, health-actor.
3. Still host: entity-specs, handlers/store, now/rand, float coerce, pagination
   folds, WASM L5 packaging.

`nbb.edn` ships resources + kotoba-kir for smoke.

## Evidence

- JVM suite green (authority + parity + main + cljs-load)
- nbb smoke: ready? + collection-path/clamp/status

## Related

- ADR 0002 product-shell host-wire
- com-cloudflare ADR 0014 cljs dual-source
