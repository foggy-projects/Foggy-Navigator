# GOV-001 P1B-B0 Synthetic Pre-seed Inventory Fixtures

These files are test-only, synthetic examples of
`navi.authorization.preseed-inventory.v1`. They are not a seed source,
credential package, approval record, runtime profile, or configuration input.

| Fixture | Expected classification | Checksum rule |
| --- | --- | --- |
| `valid-s1-s2-candidates.json` | `VALID` | matching canonical SHA-256; includes one synthetic S1 candidate and one synthetic S2 candidate |
| `quarantined-owner-conflict.json` | `QUARANTINED / PRESEED_OWNER_CONFLICT` | matching canonical SHA-256; conflict is deliberately retained for test coverage |
| `invalid-checksum.json` | `INVALID / PRESEED_CHECKSUM_MISMATCH` | deliberately uses an all-zero checksum and must never be corrected |

## Canonical checksum

The checksum is lowercase SHA-256 over UTF-8 JSON after recursively sorting
object keys, preserving array order, serializing without whitespace, and
omitting only the envelope's `checksum` field. The validator returns its
computed checksum, never any raw field values.

Expected matching checksums:

- `valid-s1-s2-candidates.json`: `428d140a6f31f353815de24bfdbfaa2b2f6ea5a0083e05be0e917f3d18519a07`
- `quarantined-owner-conflict.json`: `3843b890cff39d0e07718ec6fbd8face109f360cdf0361c620dd4188d16936db`

## Safety boundary

All references, aliases, approval references, and fingerprint edges are
opaque synthetic values. Do not replace them with real identifiers, profile
content, credentials, verifier/hash material, request bodies, tokens, keys,
passwords, or upstream-user data. A `VALID` result proves only the offline
structure and checksum; it is not an owner approval or permission to seed,
issue a credential, change a route, enable an external surface, or operate a
Worker Gateway.
