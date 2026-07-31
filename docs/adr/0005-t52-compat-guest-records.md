# ADR 0005: T5.2 native guest records — compat multi-arg pure

- Status: accepted
- Date: 2026-08-01
- Depends: ADR 0004 call-record + mirror-delete
- WBS: T5.2 residual multi-arg pure (com-cloudflare-compat)

## Decision

1. Add `cloudflare.kotoba.oracle/record` (same wire shape as com-cloudflare/murakumo).
2. Fold multi-arg pure on `compat_core`:

| Export | Schema |
|--------|--------|
| `parse-nat` | `:compat/digits-go` (`s`/`acc`) |
| `fact-attr` | `:compat/entity-field` |
| `id-with-prefix` | `:compat/id-prefix` |

3. Bump test compiler `98b56bdb` + kotoba-kir `767f2f2` for record-get sugar.

## Non-claims

- entity-specs / handlers / store / UUID stay host
- T8.3 / W4 residuals unchanged

## Evidence

- KIR regenerated `compat_core`
- 20 tests / 213 assertions green
