# hugmesh-mesh-bff-api

The Meshtastic node-directory / caching BFF. It does **not** replace the
optional `meshbridge` daemon (the Matrix<->mesh text bridge) or user-bff's
mesh-device registry — it sits *alongside* both:

- **meshbridge** (generator `specs/cluster/manifests/meshbridge/`) owns the
  actual radio link and bridges chat text to/from Matrix. It *optionally*
  pushes a periodic node/room snapshot here.
- **user-bff** (`systems.hugmesh.userbff.meshdevice`) owns *registration*:
  which platform user claims which node id, and that owner's
  status-sharing `visibility` choice.
- **this service** owns a fast, cached, visibility-gated *read* surface over
  both: "what nodes/rooms exist and are they online", cross-referenced with
  "who (if anyone) owns this node, and may I be told that".

## Where it sits

ClusterIP-only in `snapdragon-backend`. Reachable only via the fe-gateway's
`/bff/mesh/**` route (same-origin with `snapdragon.systems`), which enforces
the Casdoor role rules (`ROLE_MESH` + `ROLE_ADMIN` superset) from the
route-access DB first — this service's own `SecurityConfig` re-checks the
same gate as defence-in-depth.

## Casdoor role model

- `ROLE_MESH` — the ordinary floor. Grantable/revocable by an admin via
  admin-bff's `POST/DELETE /bff/admin/roles/{roleName}/{user}` (once wired —
  see the platform-wiring section of the task this repo shipped with).
- `ROLE_ADMIN` / `ROLE_SUPERADMIN` — chain-level supersets on `/bff/mesh/**`
  (paired explicitly, mirroring `/bff/mod/**`'s convention — the role
  hierarchy has **no** `ADMIN > MESH` edge).
- `ROLE_SERVICE` — the **only** role that reaches `/bff/mesh/internal/**`
  (the bridge-sync ingest). The role hierarchy has **no** edge to SERVICE —
  not even `ROLE_SUPERADMIN` reaches it, mirroring user-bff-api's
  `InternalMeshDeviceController` posture exactly.

## Surface

| Method | Path | Role | What |
|---|---|---|---|
| GET | `/bff/mesh/nodes` | MESH/ADMIN/SUPERADMIN | Cached node directory, visibility-gated attribution |
| GET | `/bff/mesh/rooms` | MESH/ADMIN/SUPERADMIN | Domain-scoped mesh-enabled room list |
| GET | `/bff/mesh/status/{nodeId}` | MESH/ADMIN/SUPERADMIN | One node's status, same visibility gate |
| GET | `/bff/mesh/devices/mine` | MESH/ADMIN/SUPERADMIN | Caller's own registered devices + live status |
| POST | `/bff/mesh/internal/bridge-sync` | SERVICE only | The bridge's periodic node/room snapshot push |

## Tables

- `mesh_node_directory` — `node_id` (PK, `"!"+8` lowercase hex, DB CHECK-enforced
  on Postgres), `long_name`, `short_name`, `last_seen_at`, `battery_pct` (0-101,
  101 = externally powered), `snr`, `hops_away`, `via_device`, `domain`
  (brand/tenant scope, default `"default"`), `updated_at`.
- `mesh_room` — `room_id` (PK, the bridge's Matrix room id), `device`,
  `mesh_channel`, `domain`, `updated_at`.

Both tables are owned exclusively by this service (Liquibase,
`db/changelog/`). Writes are **app-assigned-PK upserts** via
`directory.MeshNodeDirectoryDao`'s explicit `INSERT ... ON CONFLICT DO UPDATE`
SQL — deliberately **not** `ReactiveCrudRepository.save()`, whose default
`isNew()` heuristic treats a non-null assigned id as "existing" and would
silently no-op an INSERT for a genuinely new row.

## Caching

Every `GET /bff/mesh/**` read is served from an in-memory snapshot —
**never** a per-request datastore query. The shape is cloned from
`hugmesh-gateway`'s `cache.GatewayRouteAccessService`:

- a `volatile` snapshot, seeded at `@PostConstruct`;
- reloaded on a `@Scheduled(fixedDelayString=...)` tick from Postgres (the
  source of truth) — `app.mesh.cache.refresh-interval-ms` (default 5000ms)
  for the node/room directory, `app.mesh.devices-cache.refresh-interval-ms`
  (default 60000ms) for the user-bff registration mapping;
- every successful Postgres reload is best-effort mirrored to Cassandra
  (`directory.CassandraDirectoryMirror`, cloned from admin-bff-api's
  `routeaccess.CassandraRouteMirror` + `cassandra.CassandraSessionFactory`
  shape — same `app.mirror.cassandra.*` property namespace);
- a failed Postgres reload falls back (`switchIfEmpty`) to the Cassandra
  mirror's own read — a fallback-of-last-resort, not the primary path;
- when Cassandra is not configured (`app.mirror.cassandra.enabled=false`,
  the default), `directory.NoopDirectoryMirror` wins via
  `@ConditionalOnMissingBean` — every write is a no-op and every read is
  empty, so the cache runs Postgres-only and degrades cleanly (cloned from
  hugmesh-federation-api's `cache.NoopManifestCache` bean-selection
  inversion);
- every reload is guarded by a `ReactiveCircuitBreaker`
  (`config.CacheResilienceConfig`, cloned from hugmesh-gateway's
  `config.GatewayResilienceConfig`) so a stalled datastore never blocks a
  reload tick indefinitely.

The registration mapping (`devices.RegistrationCache`, user-bff's
`GET /bff/user/internal/mesh-devices`) uses the same volatile-snapshot +
scheduled-reload shape, but has **no** Cassandra fallback — a failed reload
simply **keeps the last-good snapshot** (fail-soft; an empty *initial*
snapshot, before the first successful reload, is a legitimate "no
registrations known yet" state, not an error).

## Visibility v1 (deliberate, honest — documented verbatim; see `directory.Visibility`)

- **Anonymous** (no active registration for the node id in user-bff) — ALWAYS
  listed, WITHOUT attribution. The raw radio telemetry is mesh
  infrastructure data; there is no owner to protect.
- **`private`** — attribution AND status shown ONLY to the owner (caller's
  JWT `localpart` claim equals the registration's `localpart`). Every other
  caller sees the row reduced to a bare node id (telemetry nulled,
  `registeredTo=null`).
- **`channels`** — visible (attribution + status) to ANY `ROLE_MESH` caller
  in the SAME domain as the node's directory row. This is a v1
  **APPROXIMATION** of "any caller who shares a mesh-enabled room/channel
  with the owner" — precise room-membership-based visibility is **not**
  implemented (queued; this BFF has no room-membership source to query yet).
- **`friends`** — v1 **= `private`** (owner only). The platform has **no**
  friendship/follow primitive to query, and one is deliberately **not**
  invented here — queued alongside precise `channels` room-membership.
- An unrecognized/malformed visibility value fails CLOSED (treated as
  `private`).
- `ROLE_SUPERADMIN` bypasses all four rules above unconditionally — this is
  **not** one of the four v1 rules; it is a deliberate platform-convention
  addition (every other cross-tenant/visibility predicate on the platform —
  `security.Roles#domainApplies`, the gateway's `decide()` — gives
  SUPERADMIN an unconditional bypass), called out explicitly rather than
  folded silently into the "four rules".

`GET /bff/mesh/devices/mine` is **not** visibility-gated — a caller always
sees their own devices in full, regardless of their own `visibility` setting
(that setting only governs what *other* callers see).

## Bridge-sync contract

`POST /bff/mesh/internal/bridge-sync` (ROLE_SERVICE only) is the meshbridge
daemon's `MeshBffSync.push_snapshot` push
(`specs/cluster/manifests/meshbridge/app/main.py`, gated on
`MESHBRIDGE_MESHBFF_SYNC_URL` being set — mesh-bff-api is its own optional
service, so meshbridge runs fine without it). Field names match that
producer's payload **exactly**:

```json
{
  "nodes": [{"nodeId": "!a1b2c3d4", "longName": "...", "shortName": "...",
             "lastSeenAt": "...", "batteryPct": 87, "snr": 4.5,
             "hopsAway": 2, "viaDevice": "wifi0"}],
  "rooms": [{"roomId": "!room:snapdragon.systems", "device": "wifi0", "meshChannel": 3}]
}
```

Response: `200 {"nodesUpserted": n, "roomsUpserted": m}`. TOLERANT/CLAMPED,
never fails the whole request for one bad row:

- `nodeId` is normalized (missing `!`, mixed hex case both tolerated) to the
  canonical `"!"+8-lowercase-hex` form; a row that still fails
  `^![0-9a-f]{8}$` after normalization is **skipped** (not counted, not an
  error).
- `batteryPct` is clamped to `0..101`.
- A blank `roomId` is skipped.

## The user-bff internal client

`GET /bff/mesh/devices/mine` and the `registeredTo` attribution on
`GET /bff/mesh/nodes` both depend on `devices.UserBffMeshDeviceClient`, a
server-to-server GET against user-bff's REAL internal contract:

```
GET https://snapdragon-user-bff-api.snapdragon-backend.svc.cluster.local:8080/bff/user/internal/mesh-devices
  -> 200 [{"nodeId", "localpart", "displayName", "visibility"}, ...]
```

(verified against `InternalMeshDeviceController.BridgeMapping` in
snapdragon-user-bff-api's main, and independently against the identical
shape meshbridge's own `matrix_cs.UserBffClient.fetch_mappings()` parses).
Auth is the platform's established ROLE_SERVICE pattern: an mTLS client
identity (`devices.UserBffHttpClientSsl`, cloned from mod-bff-api's
`federation.FederationHttpClientSsl`) PLUS a Casdoor `client_credentials`-minted
SERVICE bearer (`devices.UserBffMeshDeviceConfig`, cloned from
admin-bff-api's `notification.UserBffNotificationConfig` — the platform's
canonical "call another BFF's ROLE_SERVICE internal surface" shape), mirroring
the exact posture meshbridge's own `CasdoorTokenSource`/`UserBffClient`
present to this same endpoint. GATED, default OFF
(`app.mesh.user-bff.enabled=false`) so a user-bff-less dev/test/CI run boots
clean — every node then reads as anonymous.

## Host wiring (cluster)

- **Deployment/Service**: `specs/cluster/manifests/mesh-bff-api/` (cloned
  from `manifests/mod-bff-api/`) + a dedicated Postgres,
  `specs/cluster/manifests/mesh-bff-db/` (cloned from `manifests/mod-bff-db/`).
- **ArgoCD apps**: `specs/cluster/apps/39a-mesh-bff-db.yaml` (sync-wave 1) +
  `39b-mesh-bff-api.yaml` (sync-wave 0), + the `templates/apps/*.hbs` twins.
- **MCS categories**: `s0:c80` (mesh-bff-api), `s0:c81` (mesh-bff-db) — next
  free after `attestation-server`'s `s0:c79` (see `docs/SECURITY.md`).
- **Gateway route**: fe-gateway `/bff/mesh/**` ->
  `https://snapdragon-mesh-bff-api.snapdragon-backend.svc.cluster.local:8080`,
  declared before the `/bff/**` admin-bff catch-all
  (`specs/cluster/apps/20-gateway-fe.yaml` + the compose test fixture + the
  `.hbs` template) and in `snapdragon-gateway`'s in-repo
  `cache.CanonicalSeed.fe()` fallback rows.
- **route-access seed**: `ROLE_MESH` + `ROLE_ADMIN` rows for
  `snapdragon-fe-gateway` / `/bff/mesh/**` in
  `specs/cluster/manifests/route-access/initdb/20-schema.sql`.
- **Casdoor**: `ROLE_MESH` role in
  `specs/cluster/manifests/casdoor/init-data-configmap.yaml`; the
  `app-mesh-bff` client-credentials client is **manually provisioned**
  (same posture as meshbridge's own `app-meshbridge` client — see
  `specs/cluster/manifests/external-secrets/mesh-bff-externalsecrets.yaml`'s
  header for the exact steps).
- **ESO**: `specs/cluster/manifests/external-secrets/mesh-bff-externalsecrets.yaml`
  — DB owner + least-privilege app creds, the server mTLS cert, the outbound
  client mTLS cert, and the `app-mesh-bff` Casdoor client id/secret, all in
  one file (mirrors the `meshbridge-externalsecrets.yaml` optional-service
  shape).

## Build & test

```
./mvnw -s settings.xml test      # unit + @DataR2dbcTest (Testcontainers Postgres) + full-context slices
./mvnw -s settings.xml verify    # what CI runs
```

No live Postgres/Cassandra/Casdoor/user-bff is required for `test` — the
`test` Spring profile disables Liquibase + points R2DBC at a throwaway H2
store for full-context boots (`OpenApiSpecDumpTest`, `SecurityMatrixTest`);
the repository slice (`MeshNodeDirectoryDaoTest`) provisions its own
Testcontainers Postgres and runs the REAL Liquibase changelog over it.

The `Makefile` also has local pen-test targets that mirror the centralized
`.gitlab-ci.yml` `pentest:sca`/`pentest:dast` stage (both delegate to the
generator's shared `/tests/pentest/` harness — see `Makefile` for details):

```
make pentest         # SCA (OWASP Dependency-Check) + DAST, non-fatal locally
make pentest-sca     # just the OWASP Dependency-Check self-scan
make pentest-dast    # authenticated ZAP + probes + nuclei, requires DAST_TARGET_URL
```

## Layout

```
config/     SecurityConfig, CommonsSecurityConfig, CacheResilienceConfig
security/   Roles (Authentication-typed domain-scoping helpers)
web/        ApiExceptionHandler, ValidationExceptionHandler
cassandra/  CassandraSessionFactory (best-effort mirror session builder)
directory/  node/room rows + repositories + upsert DAO + cache + mirror +
            visibility + the GET /bff/mesh/{nodes,rooms,status} controller
sync/       the bridge-sync ingest (POST /bff/mesh/internal/bridge-sync)
devices/    the user-bff internal client + registration cache + the
            GET /bff/mesh/devices/mine controller
```

## Honest deferrals

- `channels` visibility is domain-scoped, not room-membership-scoped (no
  room-membership source exists here yet).
- `friends` visibility has no backing primitive and is treated as `private`.
- The `app-mesh-bff` Casdoor client is manually provisioned (no automated
  seed job), matching every other ROLE_SERVICE machine-client on the
  platform (`app-meshbridge`, `app-signup-gate`, ...).
