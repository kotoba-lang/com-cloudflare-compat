# ADR 0002: W6 product-shell oracle authority (cloudflare-compat)

- Status: Accepted
- Date: 2026-07-28

## Context

ADR 0001 landed `kotoba/compat_core.kotoba` with KIR parity for coerce/path/
limit pure helpers. Product `cloudflare.main` still reimplemented those values.

## Decision

Ship precompiled KIR under `resources/cloudflare/oracle/compat_core.kir.edn`,
load via `cloudflare.kotoba.oracle`, and host-wire pure helpers on the JVM:

- constants: ns-prefix, tier, limits, status codes, health-actor, message prefixes
- coerce: as-int-string / as-bool-string (string branch)
- clamp-limit for pagination
- collection-path / item-path / fact-attr / id-with-prefix

### Still host

- entity-specs, handlers, *store*, now/rand
- float coerce, non-string as-bool branches
- cljs mirrors

### Regeneration

```bash
clojure -M:oracle-gen
```

## Evidence

- authority + parity + existing main tests
- com-cloudflare#14 product-shell pattern

## Related

- ADR 0001 compat kotoba oracle (parity)
- murakumo / com-cloudflare dual-source trail
