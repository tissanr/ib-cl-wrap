# Downstream Migration Guide

This document is the central migration artifact for downstream consumers of
`ib-cl-wrap`.

It should be updated whenever a stabilization phase introduces user-visible
changes that require consumer action.

## Purpose

Use this file to document:

- breaking API changes
- deprecations
- result-shape migrations
- renamed metrics or events
- IB API compatibility changes
- required downstream code updates

`CHANGELOG.md` should summarize changes. This file should explain how to adapt.

## Expected Phase Usage

### Phase 1

Document:

- which APIs are stable
- which APIs are experimental or transitional
- which IB API jar versions are supported
- whether result-shape changes are still expected in Phase 2

Current Phase 1 downstream actions:

- treat [docs/spec-surface.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/spec-surface.md)
  as the stable API boundary
- treat market data, contract details, reconnect helpers, and order-placement
  helpers as experimental during `0.x`
- stop treating visible helper namespaces such as `ib.events` as stable API
- prefer `:request-id` over legacy event keys such as `:req-id`
- treat connection handles from `connect!` as opaque maps
- expect Phase 2 to normalize duplicate contract-details and snapshot result
  conventions before `1.0`

### Phase 2

Current Phase 2 downstream actions:

- normalize snapshot callers to expect `{:ok true ...}` / `{:ok false :error ...}` envelopes
- prefer `:request-id` in snapshot results; keep reading `:req-id` only as a compatibility field during `0.x`
- migrate contract detail lookups to `ib.contract/contract-details-snapshot!`
- migrate diagnostic reads from `ib.client/dropped-event-count` to `ib.client/dropped-event-total`

#### Snapshot Result Envelopes

Old behavior:
- `ib.positions/positions-snapshot!` returned either a raw vector of positions or an `{:type :ib/error ...}` map

New behavior:
- `ib.positions/positions-snapshot!` now returns `{:ok true :positions [...]}` on success
- failures now follow the same `{:ok false :error ...}` shape used by the other snapshot helpers

Who is affected:
- any caller branching on vector-vs-error behavior

Required downstream change:
- replace checks such as `(vector? result)` and `(:type result)` with `(:ok result)` and the success payload key

Classification:
- breaking

Release:
- current `Unreleased`

#### Canonical Request Id In Snapshot Results

Old behavior:
- account and contract snapshot helpers surfaced `:req-id` as the primary result key

New behavior:
- account, contract, and market-data snapshot helpers now expose `:request-id` as the canonical key
- `:req-id` remains present as a compatibility field during `0.x`

Who is affected:
- callers reading snapshot metadata directly

Required downstream change:
- switch to `:request-id`

Classification:
- additive now, breaking later when compatibility fields are removed

Release:
- current `Unreleased`

#### Contract Details API Consolidation

Old behavior:
- both `ib.market-data/contract-details-snapshot!` and `ib.contract/contract-details-snapshot!` were callable

New behavior:
- `ib.contract/contract-details-snapshot!` is the canonical API
- `ib.market-data/contract-details-snapshot!` remains only as a deprecated compatibility wrapper

Who is affected:
- callers still resolving contracts through `ib.market-data`

Required downstream change:
- move imports and call sites to `ib.contract/contract-details-snapshot!`

Classification:
- deprecated compatibility shim

Release:
- current `Unreleased`

#### Diagnostic Counter Rename

Old behavior:
- the public counter name was `ib.client/dropped-event-count`

New behavior:
- the canonical name is `ib.client/dropped-event-total`
- `ib.client/dropped-event-count` remains as a deprecated alias

Who is affected:
- callers reading queue-drop diagnostics

Required downstream change:
- rename reads to `dropped-event-total`

Classification:
- deprecated compatibility shim

Release:
- current `Unreleased`

### Phase 3

Document:

- stricter validation rules
- newly rejected invalid inputs
- spec-related assumptions downstream code must now satisfy

### Phase 4

Document:

- reconnect behavior changes
- diagnostics or counter meaning changes
- operational assumptions downstream systems must update

## Recommended Entry Format

For each downstream-impacting change, record:

1. the old behavior
2. the new behavior
3. who is affected
4. the required downstream change
5. whether the change is breaking, deprecated, or additive
6. which release first contains the change

## Initial Known Impact Areas

Likely downstream-impact areas during stabilization:

- snapshot result-shape normalization
- duplicate contract-details API removal or deprecation
- diagnostic counter renames or semantic corrections
- standardization around canonical request-id fields
- stronger validation of public option maps
- clarified IB API jar version support
