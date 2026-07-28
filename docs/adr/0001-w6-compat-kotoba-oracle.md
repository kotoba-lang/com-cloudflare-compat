# ADR 0001: W6 cloudflare-compat pure coerce/path kotoba oracle

- Status: Accepted
- Date: 2026-07-28

## Context

W6 cloudflare path inventory lists `com-cloudflare-compat` as a host-mechanism
L5 clean-room actor with pure coerce folds and a data-driven route table
eligible for optional oracle work. Handlers stay host-bound (`*store*`, clock,
RNG).

## Decision

Port scalar cores to `kotoba/compat_core.kotoba`:

| function | notes |
|---|---|
| `as-int-string` / `as-bool-string` | string branch of cljc coerce |
| `clamp-limit` | paginate limit policy |
| `collection-path` / `item-path` | `/v1/{plural}` route surface |
| `fact-attr` / `id-with-prefix` | emit-facts key + id shape |
| constants | ns-prefix, tier, limits, HTTP statuses |

### Not ported

- `entity-specs` / `routes` vector assembly
- CRUD handlers, filters, expand, `*store*`
- `as-float`, number-typed `as-int`/`as-bool` branches
- `now` / `rand-hex16`

## Evidence

- `test/cloudflare/compat_kotoba_parity_test.clj`

## Related

- W6 `lang/w6-cloudflare-path-inventory.edn` compat vertical
