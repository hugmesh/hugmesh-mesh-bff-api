# hugmesh-mesh-bff-api

The Meshtastic node-directory / caching BFF. It does **not** replace the
optional `meshbridge` daemon (the Matrix<->mesh text bridge) or user-bff's
mesh-device registry — it sits *alongside* both:

- **meshbridge** (generator `specs/cluster/manifests/meshbridge/`) owns the
  actual radio link and bridges chat text to/from Matrix. It *optionally*
  pushes a periodic node/room snapshot here.
- **user-bff** (`net.hugmesh.userbff.meshdevice`) owns *registration*:
  which platform user claims which node id, and that owner's
  status-sharing `visibility` choice.
- **this service** owns a fast, cached, visibility-gated *read* surface over
  both: "what nodes/rooms exist and are they online", cross-referenced with
  "who (if anyone) owns this node, and may I be told that".

## Where it sits

ClusterIP-only in `hugmesh-backend`. Browsers reach it only via the fe-gateway's
`/bff/mesh/**` route (same-origin with `hugmesh.net`). The gateway checks the
route-access DB first: one allow row, `ROLE_MESH`. Admins pass because the
gateway lets admins through; the DB has no `ROLE_ADMIN` row for this route.
A second row denies `ROLE_USER` on `/bff/mesh/internal/**`. meshbridge calls
that ingest in-cluster, not through the gateway. This service's own
`SecurityConfig` re-checks the gate (`MESH`, `ADMIN` or `SUPERADMIN`) as
defence-in-depth.

## Casdoor role model

- `ROLE_MESH` — the ordinary floor. An admin can suspend it with admin-bff's
  `POST /bff/admin/access-roles/{role}/{user}` and restore it with `DELETE` on
  the same path. admin-bff's direct grant endpoint,
  `POST/DELETE /bff/admin/roles/{roleName}/{user}`, does **not** accept
  `ROLE_MESH`. UNVERIFIED: how a user is first granted the role.
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
`db/changelog/`). Writes are **app-assigned-PK upserts** through
`directory.MeshNodeDirectoryDao`, a facade over `@Query` methods with explicit
`INSERT ... ON CONFLICT DO UPDATE` SQL in `directory.MeshNodeDirectoryR2dbcRepository`
and `directory.MeshRoomDirectoryR2dbcRepository` — deliberately **not** `ReactiveCrudRepository.save()`, whose default
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
  (default 60000ms) for the user-bff registration mapping. A node counts as
  online when `lastSeenAt` is within `app.mesh.node-online-window-seconds`
  (default 300);
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
  inversion). The cluster manifest does not set the flag, so the cluster runs
  Postgres-only;
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

In code, `Visibility.permits` never looks at the caller's role. It gets the
owner flag, the same-domain flag and the superadmin flag only. So "same domain"
for `channels` means any caller who passed the route gate, including a plain
`ROLE_ADMIN`. A row whose `domain` is blank or `default` counts as the same
domain for everyone, and `BridgeSyncService` writes `default` on every node and
room it ingests. Today that makes `/bff/mesh/rooms` unfiltered and makes
`channels` nodes visible to every caller.

`GET /bff/mesh/devices/mine` is **not** visibility-gated — a caller always
sees their own devices in full, regardless of their own `visibility` setting
(that setting only governs what *other* callers see).

## Bridge-sync contract

`POST /bff/mesh/internal/bridge-sync` (ROLE_SERVICE only) is the meshbridge
daemon's `MeshBffSync.push_snapshot` push
(`specs/cluster/manifests/meshbridge/app/main.py`, gated on
`MESHBRIDGE_MESHBFF_SYNC_URL` being set, every `MESHBRIDGE_SYNC_PUSH_SECONDS`,
default 60 — mesh-bff-api is its own optional
service, so meshbridge runs fine without it). Field names match that
producer's payload **exactly**:

```json
{
  "nodes": [{"nodeId": "!a1b2c3d4", "longName": "...", "shortName": "...",
             "lastSeenAt": "...", "batteryPct": 87, "snr": 4.5,
             "hopsAway": 2, "viaDevice": "wifi0"}],
  "rooms": [{"roomId": "!room:hugmesh.net", "device": "wifi0", "meshChannel": 3}]
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
- Every accepted node and room is stored with `domain` = `default`.

## The user-bff internal client

`GET /bff/mesh/devices/mine` and the `registeredTo` attribution on
`GET /bff/mesh/nodes` both depend on `devices.UserBffMeshDeviceClient`, a
server-to-server GET against user-bff's REAL internal contract:

```
GET https://hugmesh-user-bff-api.hugmesh-backend.svc.cluster.local:8080/bff/user/internal/mesh-devices
  -> 200 [{"nodeId", "localpart", "displayName", "visibility"}, ...]
```

(verified against `InternalMeshDeviceController.BridgeMapping` in
hugmesh-user-bff-api's main, and independently against the identical
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
clean — every node then reads as anonymous. The cluster manifest turns the leg
on (`APP_MESH_USER_BFF_ENABLED=true`) but leaves the outbound mTLS gate
(`hugmesh.mtls.client.enabled`, env `HUGMESH_MTLS_CLIENT_ENABLED`) at its
default, off.

## Host wiring (cluster)

Paths are in `hugmesh-generator`, under `specs/cluster/` unless noted.

- **Deployment/Service**: `manifests/mesh-bff-api/` (cloned
  from `manifests/mod-bff-api/`) + a dedicated Postgres,
  `manifests/mesh-bff-db/` (cloned from `manifests/mod-bff-db/`).
- **ArgoCD apps**: `apps/34c-mesh-bff-db.yaml` (sync-wave 2) +
  `apps/34d-mesh-bff-api.yaml` (sync-wave 3). No `templates/apps/*.hbs` twin exists.
- **MCS categories**: `s0:c80` (mesh-bff-api), `s0:c81` (mesh-bff-db), the two
  that follow `attestation-server`'s `s0:c79` (see `docs/SECURITY.md`).
- **Gateway route**: fe-gateway `/bff/mesh/**` ->
  `https://hugmesh-mesh-bff-api.hugmesh-backend.svc.cluster.local:8080`,
  declared before the `/bff/**` admin-bff catch-all in `apps/20-gateway-fe.yaml`.
  No template or compose fixture carries it.
- **route-access seed**: in `manifests/route-access/initdb/20-schema.sql`, an
  allow row `ROLE_MESH` on `/bff/mesh/**` for `hugmesh-fe-gateway`, and a deny
  row `ROLE_USER` on `/bff/mesh/internal/**`. `hugmesh-gateway`'s fallback seed
  (`cache.CanonicalSeed.fe()`) and its `compose/route-access-seed.sql` carry only
  the deny row.
- **Casdoor**: `ROLE_MESH` role in `manifests/casdoor/init-data-configmap.yaml`.
  The client-credentials application that mints the SERVICE bearer is
  **manually provisioned**, as meshbridge's `app-meshbridge` is. Its id and
  secret go to Vault path `hugmesh/mesh-bff-casdoor-client`.
- **ESO**: in `manifests/external-secrets/secretstore.yaml`: `mesh-bff-db-auth`
  (DB owner), `mesh-bff-db-app-auth` (least-privilege app role) and
  `mesh-bff-casdoor-client`. In `manifests/external-secrets/pki-externalsecrets.yaml`:
  the mTLS certificates `hugmesh-mesh-bff-api-mtls` and `mesh-bff-db-mtls`.
  There is no separate outbound-client certificate; the outbound client reads
  the same `/stores` keystore. (A file named `mesh-bff-externalsecrets.yaml`
  does not exist, though comments in `application.yml` and the generator's
  `deployment.yaml` still name it.)

## Build & test

```
./mvnw -s settings.xml test      # unit + @DataR2dbcTest (Testcontainers Postgres) + full-context slices
./mvnw -s settings.xml verify    # what the GitLab CI test job runs (./mvnw verify -B -s settings.xml)
```

`.github/workflows/release.yml` is a manual image build and runs no tests.

No live Postgres/Cassandra/Casdoor/user-bff is required for `test`, but
`MeshNodeDirectoryDaoTest` starts a Testcontainers Postgres, so Docker must run. The
`net.hugmesh` libraries come from `~/.m2` or, failing that, the GitLab registry
named in `settings.xml`. The
`test` Spring profile disables Liquibase + points R2DBC at a throwaway H2
store for full-context boots (`OpenApiSpecDumpTest`, `SecurityMatrixTest`);
the repository slice (`MeshNodeDirectoryDaoTest`) provisions its own
Testcontainers Postgres and runs the REAL Liquibase changelog over it.

The `Makefile` also has local pen-test targets that mirror the centralized
`.gitlab-ci.yml` `pentest:sca`/`pentest:dast` stage (both delegate to the
generator's shared `/tests/pentest/` harness — see `Makefile` for details):

```
make pentest         # SCA (OWASP Dependency-Check) + DAST; missing tooling or target is skipped, not fatal
make pentest-sca     # just the OWASP Dependency-Check self-scan (fails on CVSS >= 9)
make pentest-dast    # authenticated ZAP + probes + nuclei, requires DAST_TARGET_URL
```

## Layout

```
config/     SecurityConfig, CommonsSecurityConfig, CacheResilienceConfig, OpenApiConfig
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
- The mesh-bff Casdoor client is manually provisioned (no automated seed job),
  like meshbridge's `app-meshbridge`. The signup gate's `app-signup-gate` is
  seeded by the `casdoor-init` job, so this is not true of every ROLE_SERVICE
  client.
