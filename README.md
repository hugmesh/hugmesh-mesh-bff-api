# hugmesh-mesh-bff-api

The Meshtastic node-directory / caching BFF. It does **not** replace the
optional `meshbridge` daemon (the Matrix<->mesh text bridge) or user-bff's
mesh-device registry. It sits *alongside* both:

- **meshbridge** (generator `specs/cluster/manifests/meshbridge/`) owns the
  radio link and bridges chat text to and from Matrix. It *optionally*
  pushes a periodic node/room snapshot here.
- **user-bff** (`net.hugmesh.userbff.meshdevice`) owns *registration*:
  which platform user claims which node id, and that owner's
  status-sharing `visibility` choice.
- **this service** owns a fast, cached, visibility-gated *read* surface over
  both: which nodes and rooms exist, whether they are online, who (if anyone)
  owns a node, and whether you may be told.

## Where it sits

ClusterIP-only in `hugmesh-backend`. Browsers reach it only through the
fe-gateway's `/bff/mesh/**` route (same-origin with `hugmesh.net`). The
route-access DB holds one allow row for it, `ROLE_MESH`; admins pass because
the gateway lets admins through. A second row denies `ROLE_USER` on
`/bff/mesh/internal/**`; meshbridge calls that ingest in-cluster, not through
the gateway. This service's own `SecurityConfig` re-checks the gate (`MESH`,
`ADMIN` or `SUPERADMIN`) as defence-in-depth.

## Casdoor role model

- `ROLE_MESH` — the ordinary floor. An admin can suspend it with admin-bff's
  `POST /bff/admin/access-roles/{role}/{user}` and restore it with `DELETE` on
  the same path. admin-bff's direct grant endpoint,
  `POST/DELETE /bff/admin/roles/{roleName}/{user}`, does **not** accept
  `ROLE_MESH`. UNVERIFIED: how a user is first granted the role.
- `ROLE_ADMIN` / `ROLE_SUPERADMIN` — chain-level supersets on `/bff/mesh/**`.
  The chain pairs them explicitly, as `/bff/mod/**` does: the role hierarchy
  has **no** `ADMIN > MESH` edge.
- `ROLE_SERVICE` — the **only** role that reaches `/bff/mesh/internal/**`
  (the bridge-sync ingest). The role hierarchy has **no** edge to SERVICE, not
  even from `ROLE_SUPERADMIN`. user-bff-api's `InternalMeshDeviceController`
  takes the same posture.

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

This service alone owns both tables (Liquibase, `db/changelog/`). Writes are
**app-assigned-PK upserts** through `directory.MeshNodeDirectoryDao`. It is a
facade over `@Query` methods with explicit `INSERT ... ON CONFLICT DO UPDATE`
SQL in `directory.MeshNodeDirectoryR2dbcRepository` and
`directory.MeshRoomDirectoryR2dbcRepository`. It deliberately does **not** use
`ReactiveCrudRepository.save()`: its default `isNew()` heuristic treats a
non-null assigned id as "existing" and would silently no-op an INSERT for a
genuinely new row.

## Caching

Every `GET /bff/mesh/**` read comes from an in-memory snapshot, **never** a
per-request datastore query. The shape is cloned from `hugmesh-gateway`'s
`cache.GatewayRouteAccessService`:

- a `volatile` snapshot, seeded at `@PostConstruct`;
- reloaded from Postgres (the source of truth) on a
  `@Scheduled(fixedDelayString=...)` tick: `app.mesh.cache.refresh-interval-ms`
  (default 5000ms) for the node/room directory,
  `app.mesh.devices-cache.refresh-interval-ms` (default 60000ms) for the
  user-bff registration mapping. A node counts as online when `lastSeenAt` is
  within `app.mesh.node-online-window-seconds` (default 300);
- every successful Postgres reload is mirrored to Cassandra, best effort
  (`directory.CassandraDirectoryMirror`, cloned from admin-bff-api's
  `routeaccess.CassandraRouteMirror` + `cassandra.CassandraSessionFactory`,
  same `app.mirror.cassandra.*` properties);
- a failed Postgres reload falls back (`switchIfEmpty`) to the Cassandra
  mirror's read: a last resort, not the primary path;
- when Cassandra is not configured (`app.mirror.cassandra.enabled=false`, the
  default), `directory.NoopDirectoryMirror` wins via `@ConditionalOnMissingBean`.
  Every write is a no-op, every read is empty, and the cache runs on Postgres
  alone (cloned from hugmesh-federation-api's `cache.NoopManifestCache`). The
  cluster manifest does not set the flag, so the cluster runs Postgres-only;
- a `ReactiveCircuitBreaker` (`config.CacheResilienceConfig`, cloned from
  hugmesh-gateway's `config.GatewayResilienceConfig`) guards every reload, so a
  stalled datastore never blocks a reload tick indefinitely.

The registration mapping (`devices.RegistrationCache`, from user-bff's
`GET /bff/user/internal/mesh-devices`) uses the same volatile snapshot and
scheduled reload, but has **no** Cassandra fallback. A failed reload
**keeps the last-good snapshot** (fail-soft). An empty *initial* snapshot is
a legitimate "no registrations known yet" state, not an error.

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
- `ROLE_SUPERADMIN` bypasses all four rules above unconditionally. This is
  **not** one of the four v1 rules. It is a deliberate platform-convention
  addition: every other cross-tenant/visibility predicate on the platform
  (`security.Roles#domainApplies`, the gateway's `decide()`) gives SUPERADMIN
  an unconditional bypass. It is named here rather than folded silently into
  the "four rules".

In code, `Visibility.permits` never checks the caller's role, only the owner,
same-domain and superadmin flags. So `channels` reaches any caller who passed
the route gate, a plain `ROLE_ADMIN` included. A row whose `domain` is blank or
`default` counts as the same domain for everyone, and `BridgeSyncService`
writes `default` on every node and room. Today that leaves `/bff/mesh/rooms`
unfiltered and every `channels` node visible to every caller.

`GET /bff/mesh/devices/mine` is **not** visibility-gated. A caller always
sees their own devices in full, whatever their own `visibility` setting is
(that setting governs only what *other* callers see).

## Bridge-sync contract

`POST /bff/mesh/internal/bridge-sync` (ROLE_SERVICE only) is the meshbridge
daemon's `MeshBffSync.push_snapshot` push
(`specs/cluster/manifests/meshbridge/app/main.py`). meshbridge sends it only
when `MESHBRIDGE_MESHBFF_SYNC_URL` is set, every `MESHBRIDGE_SYNC_PUSH_SECONDS`
(default 60). mesh-bff-api is its own optional service, so meshbridge runs fine
without it. Field names match that producer's payload **exactly**:

```json
{
  "nodes": [{"nodeId": "!a1b2c3d4", "longName": "...", "shortName": "...",
             "lastSeenAt": "...", "batteryPct": 87, "snr": 4.5,
             "hopsAway": 2, "viaDevice": "wifi0"}],
  "rooms": [{"roomId": "!room:hugmesh.net", "device": "wifi0", "meshChannel": 3}]
}
```

Response: `200 {"nodesUpserted": n, "roomsUpserted": m}`. The service is
TOLERANT/CLAMPED and never fails the whole request for one bad row:

- `nodeId` is normalized (a missing `!` and mixed hex case are both tolerated)
  to the canonical `"!"+8-lowercase-hex` form. A row that still fails
  `^![0-9a-f]{8}$` afterward is **skipped** (not counted, not an error).
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

(checked against `InternalMeshDeviceController.BridgeMapping` in
hugmesh-user-bff-api's main, and against the identical shape that meshbridge's
own `matrix_cs.UserBffClient.fetch_mappings()` parses).
Auth is the platform's usual ROLE_SERVICE pattern, the same one meshbridge's
own `CasdoorTokenSource`/`UserBffClient` present to this endpoint:

- an mTLS client identity (`devices.UserBffHttpClientSsl`, cloned from
  mod-bff-api's `federation.FederationHttpClientSsl`), PLUS
- a SERVICE bearer minted by Casdoor `client_credentials`
  (`devices.UserBffMeshDeviceConfig`, cloned from admin-bff-api's
  `notification.UserBffNotificationConfig`).

GATED, default OFF (`app.mesh.user-bff.enabled=false`), so a user-bff-less
dev/test/CI run boots clean; every node then reads as anonymous. The cluster
manifest turns the leg on (`APP_MESH_USER_BFF_ENABLED=true`) but leaves the
outbound mTLS gate (`hugmesh.mtls.client.enabled`, env
`HUGMESH_MTLS_CLIENT_ENABLED`) at its default, off.

## Host wiring (cluster)

Paths are in `hugmesh-generator`, under `specs/cluster/` unless noted.

- **Deployment/Service**: `manifests/mesh-bff-api/` (cloned from
  `manifests/mod-bff-api/`) + a dedicated Postgres, `manifests/mesh-bff-db/`
  (cloned from `manifests/mod-bff-db/`).
- **ArgoCD apps**: `apps/34c-mesh-bff-db.yaml` (sync-wave 2) +
  `apps/34d-mesh-bff-api.yaml` (sync-wave 3). No `templates/apps/*.hbs` twin
  exists.
- **MCS categories**: `s0:c80` (mesh-bff-api), `s0:c81` (mesh-bff-db), the two
  that follow `attestation-server`'s `s0:c79` (see `docs/SECURITY.md`).
- **Gateway route**: fe-gateway `/bff/mesh/**` ->
  `https://hugmesh-mesh-bff-api.hugmesh-backend.svc.cluster.local:8080`,
  declared before the `/bff/**` admin-bff catch-all in `apps/20-gateway-fe.yaml`.
  No template or compose fixture carries it.
- **route-access seed**: in `manifests/route-access/initdb/20-schema.sql`, an
  allow row `ROLE_MESH` on `/bff/mesh/**` for `hugmesh-fe-gateway`, and a deny
  row `ROLE_USER` on `/bff/mesh/internal/**`. `hugmesh-gateway`'s fallback seed
  (`cache.CanonicalSeed.fe()`) and its `compose/route-access-seed.sql` carry
  only the deny row.
- **Casdoor**: `ROLE_MESH` role in `manifests/casdoor/init-data-configmap.yaml`.
  The client-credentials application that mints the SERVICE bearer is
  **manually provisioned**, as meshbridge's `app-meshbridge` is. Its id and
  secret go to Vault path `hugmesh/mesh-bff-casdoor-client`.
- **ESO**: in `manifests/external-secrets/secretstore.yaml`: `mesh-bff-db-auth`
  (DB owner), `mesh-bff-db-app-auth` (least-privilege app role) and
  `mesh-bff-casdoor-client`. In `manifests/external-secrets/pki-externalsecrets.yaml`:
  the mTLS certificates `hugmesh-mesh-bff-api-mtls` and `mesh-bff-db-mtls`.
  There is no separate outbound-client certificate; the outbound client reads
  the same `/stores` keystore. No file named `mesh-bff-externalsecrets.yaml`
  exists, though comments in `application.yml` and the generator's
  `deployment.yaml` still name it.

## Build & test

```
./mvnw -s settings.xml test      # unit + @DataR2dbcTest (Testcontainers Postgres) + full-context slices
./mvnw -s settings.xml verify    # what the GitLab CI test job runs (./mvnw verify -B -s settings.xml)
```

`.github/workflows/release.yml` builds and pushes the image and runs no tests
(see Release below).

`test` needs no live Postgres, Cassandra, Casdoor or user-bff, but Docker must
run. The `test` Spring profile disables Liquibase and points R2DBC at a
throwaway H2 store for full-context boots (`OpenApiSpecDumpTest`,
`SecurityMatrixTest`). The repository slice (`MeshNodeDirectoryDaoTest`)
starts its own Testcontainers Postgres and runs the REAL Liquibase changelog
over it. The `net.hugmesh` libraries come from `~/.m2` or, failing that, the
GitLab registry named in `settings.xml`.

The `Makefile` also has local pen-test targets that mirror the centralized
`.gitlab-ci.yml` `pentest:sca`/`pentest:dast` stage (both delegate to the
generator's shared `/tests/pentest/` harness — see `Makefile` for details):

```
make pentest         # SCA (OWASP Dependency-Check) + DAST; missing tooling or target is skipped, not fatal
make pentest-sca     # just the OWASP Dependency-Check self-scan (fails on CVSS >= 9)
make pentest-dast    # authenticated ZAP + probes + nuclei, requires DAST_TARGET_URL
```

## Release

The version lives in one place: `pom.xml` `<project><version>`, currently
`0.2.0-beta.1` (form `MAJOR.MINOR.PATCH-beta.N`, never `-SNAPSHOT`). The pom
pins `hugmesh-parent 2.9.0-beta.1` and the `hugmesh-commons 2.2.0-beta.1`
tests jar. The library chain (build-config, parent, commons) must publish both
before the image can build.

`.github/workflows/release.yml` pushes `ghcr.io/hugmesh/hugmesh-mesh-bff-api`:

| Trigger | What is pushed |
|---|---|
| push to `main`, or a manual run on `main` | `:main`, `:sha-<12>` |
| push of tag `v<version>`, or a manual run on that tag | `:<version>`, `:beta`, `:sha-<12>`, and a GitHub Release marked prerelease and not latest |
| anything else | refused, nothing pushed |

`scripts/release-version.sh` makes that decision. It refuses a tag that is not
exactly `v` + the pom version. The image job also refuses to push a version
that already exists in GHCR. A beta never moves `:latest`. Test the guard with
`bash scripts/test-release-version.sh`.

To publish a beta, merge the version change, then push an annotated tag
`v<version>` on that `main` commit. Each job uses the runner list in the
repository variable `RUNS_ON_PUBLISH` only for `main` and `v*` tags. In every
other case, or when the variable is unset, it runs on GitHub-hosted
`ubuntu-latest`.

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
