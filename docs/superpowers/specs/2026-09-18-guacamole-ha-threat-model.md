# Guacamole HA clustering — threat model

Written 2026-09-18, after P4b merged and before the P5a controls were built, so
that the controls can be judged against a stated model rather than defended one
commit at a time.

Scope is the attack surface **clustering added**. Guacamole's own authentication
surface — SAML, TOTP, the login flow, the JDBC extension's permission model — is
unchanged by this programme and is not reviewed here.

- Design: `2026-09-06-guacamole-ha-clustering-design.md`
- Status: `../HA-CLUSTERING-STATUS.md`
- Controls implemented by: `../plans/2026-09-18-guacamole-ha-p5a.md`

## 1. Assets

Taken from `ClusterKeys` and `RedisClusterStore.putToken`, not from memory.
Everything below lives in one Redis keyspace, all of it under `guac:`.

| Asset | Key | Contents | Why it matters |
|---|---|---|---|
| Session identity | `guac:token:<sha256(token)>` | `username`, `authProvider`, `authenticatedTime`, `remoteAddress`, `remoteHostname` | Names who is logged in and from where. Added by P4b; the reason this document exists. |
| Share keys | `guac:share:<key>` | Connection, guacd connection id, sharer, sharing profile | A share key is a bearer credential for a live desktop session. |
| Tunnel registry | `guac:tunnel:<uuid>` | Owner, connection, guacd endpoint | Names which user holds which connection. |
| Indexes | `guac:idx:all`, `guac:idx:conn:*`, `guac:idx:group:*`, `guac:idx:user:*`, `guac:idx:guacd:*` | Sorted sets scored by heartbeat | Same disclosure as the registry, plus the guacd topology. |
| guacd routes | `guac:route:<guacd connection id>` | Which guacd pod holds a session | Integrity: a forged route sends a join to the wrong guacd. |
| Seat counters | `guac:seat:user:*` | Per-user, per-connection counts | Integrity only: corrupting them defeats concurrency limits. |
| Auth failure counters | `guac:authfail:<address>` | Failure counts per address | Integrity only: clearing them defeats brute-force banning. |
| Connection records | `guac:record:<uuid>` | In-progress history records | Integrity of the audit trail. |
| Channels | `guac:kill`, `guac:logout`, `guac:share:revoke` | Kill, logout, revocation broadcasts | A publisher can log users out or kill their sessions; a subscriber sees the same identifiers as the keys. |
| The Redis credential | Env var, Secret, process memory, `guacamole.properties` | — | Grants every row above. |

The token key is a SHA-256 of the auth token, so reading the keyspace does not
yield a usable token. It yields the identity behind one.

## 2. Trust boundaries

Three. Every control hangs off one of them.

1. **Guacamole replica → Redis.** Crosses the pod boundary. Unauthenticated and
   unencrypted by default today: the URI is whatever the operator wrote, and
   nothing inspected it.
2. **Anything else in the namespace → Redis.** Kubernetes allows pod-to-pod
   traffic by default, so every pod in the namespace can reach the port. A
   NetworkPolicy is the only control and it is optional.
3. **Operator → logs.** Anyone who can read pod logs, or the aggregator they
   ship to. This set is wider than the set who can read the Secret, which is
   what makes a credential in a log line worse than it first appears.

A fourth boundary — end user → Guacamole — is unchanged by clustering and is
out of scope.

## 3. Threats and controls

One row per threat that is real for this deployment. The numbering is
load-bearing: P5a's tasks cite it.

| # | Threat | Boundary | Control | Task |
|---|---|---|---|---|
| C1 | Token store deployed against an unauthenticated or unencrypted Redis, exposing session identity to anything that can reach the port | 1 | Refuse to start unless the URI is authenticated and encrypted, or insecure operation is explicitly opted into | 2 |
| C2 | An over-tight or wrong ACL disables clustering silently, because every failure is caught and degraded by design | 1 | Exercise the permission surface at startup and report exactly what is denied | 3 |
| C3 | Credentials reach logs or exception messages, where the audience is wider than the Secret's | 3 | Redact at every site that prints a URI, and verify the client library does the same | 4 |
| C4 | Credentials and authenticated connections outlive the web application that opened them | 1 | Release cluster resources on undeploy | 5 |
| C5 | A vulnerable dependency ships unnoticed — this fork adds Lettuce, Netty and Reactor, inside nested extension jars | supply chain | Scan dependencies and the image in CI, with a failing gate | 6 |
| C6 | Anything in the namespace reads or writes the keyspace | 2 | NetworkPolicy, already written in `../deploy/redis-hardened.yaml`; verified as an abuse case | 7 |

**C6 was measured and does not hold on the devqa cluster.** The NetworkPolicy
exists and its spec is correct, but the VPC CNI node agent runs with
`--enable-network-policy=false`, so the API server accepts the object and
nothing enforces it -- a bystander pod connects to Redis. See
`../deploy/README.md` §12.6. Until enforcement is enabled, authentication and
the ACL are the only controls on trust boundary 2, and a NetworkPolicy visible
in `kubectl get` must not be read as evidence of one.
| C7 | An operator cannot tell whether any of the above are active | 3 | Startup states the security posture in one line, redacted | 2 |

### What an attacker gets at each level

Stated explicitly, because "Redis is exposed" is not a severity.

- **Read the keyspace:** who is logged in, from which address, to which
  connections, and every live share key. Share keys are usable — they are bearer
  credentials — so read access is not merely disclosure.
- **Write the keyspace:** forge routes, clear ban counters, inflate or zero seat
  counters. Cannot forge a session: `getToken` returns identity that the replica
  re-derives authorization for from the database on every rebuild, so a forged
  `guac:token:*` entry yields a session for a user who must still exist and whose
  permissions are read fresh.
- **Publish to the channels:** log out arbitrary users and kill arbitrary
  sessions. Denial of service, not escalation.

## 4. Accepted risks

A threat model that lists no accepted risk is not finished.

- **Session identity is not encrypted at rest.** Anyone holding the Redis
  credential reads it. Accepted: encrypting it needs a key every replica shares,
  which moves the problem to key distribution rather than solving it, and the
  data is identity rather than secrets. The credential, the ACL and the
  NetworkPolicy are the controls.
- **A tunnel already opened from a revoked share key survives on another
  replica** until its session ends. Revocation prevents new joins, not existing
  ones. Recorded in P3b; unchanged here.
- **`ban-max-addresses` no longer bounds memory** when clustering is on, because
  the counters live in Redis rather than in a bounded local map (P4a). Accepted,
  with ingress rate limiting as the outer defence and `noeviction` making the
  growth visible rather than silent.
- **A remote kill issued during a Redis outage returns 404** rather than the
  designed timeout (P3a). Accepted: it never reports success falsely.
- **`FLUSHALL` is denied by the ACL but exists in the code** for the test suite.
  Accepted: the ACL is the control, and the test path is unreachable without the
  permission.
- **Redis Cluster mode is not supported**, so redundancy means Sentinel or a
  managed primary. Accepted as a capability limit, not a security one.

## 5. Out of scope

- Guacamole's own authentication surface, as stated above.
- guacd itself. It has no authentication of its own and is protected by network
  placement, exactly as in a single-replica deployment.
- Encryption at rest, per §4.
- The PostgreSQL database, which clustering did not change.
