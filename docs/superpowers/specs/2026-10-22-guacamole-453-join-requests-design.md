# GUACAMOLE-453: Join requests for in-use connections

Status: design approved, 2026-10-22
Upstream issue: https://issues.apache.org/jira/browse/GUACAMOLE-453

## Problem

When a user opens a connection that someone else is already using, Guacamole
does nothing to coordinate them. For RDP the second user starts a new session,
the server hands the desktop over, and the first user is dropped with
`ERROR_CLIENT_209` ("closed because it conflicts with another connection").
Nobody is asked. Sharing exists, but only the connected user can start it, by
creating a link and sending it out of band.

## Goal

When a user opens a connection that another user is connected to:

1. The newcomer sees **"An active session is running, please wait to be
   admitted"** with two buttons: **Cancel** and **Enforce**.
2. The connected user sees **"<user> wants to join"** with one button per
   sharing profile of the connection (for example "Allow control", "View
   only") and **Deny**.
3. **Admit** joins the newcomer to the *same* live session through the chosen
   sharing profile. The connected user stays connected.
4. **Enforce** ends the existing session(s) and connects the newcomer
   normally.
5. A request nobody answers expires after a timeout (default 120 s). The
   newcomer then sees "No response from the current user" with **Retry** and
   **Enforce**.

## Decisions

| Topic | Decision |
|---|---|
| Meaning of "admit" | Join alongside, via sharing. Not a hand-over. |
| Newcomer's rights | Chosen by the connected user: one button per sharing profile of the connection. |
| Who may Enforce | Any user with READ on the connection, which is the right they need to connect anyway. Every Enforce is logged. |
| Unanswered request | Expires after `join-request-timeout`. No automatic action. |
| Enablement | Global property `join-request-enabled`, default `false`. No per-connection switch. |
| How a join enters the session | Existing sharing profiles and the existing `sharingCredentials` REST endpoint. No profile-less sharing. |
| Same user on a second tab or device | No dialog. Behaviour unchanged. |
| Delivery to the connected user | Browser polling (3 s). No changes to the tunnel or protocol. |

## Architecture

### Server: `guacamole-auth-jdbc-base`, package `org.apache.guacamole.auth.jdbc.join`

| Unit | Responsibility |
|---|---|
| `JoinRequestProperties` | `join-request-enabled` (boolean, default `false`), `join-request-timeout` (seconds, default `120`). |
| `JoinRequest` | Value object: `id`, `connectionIdentifier`, `activeConnectionUuids` (primary sessions at request time), `requester` (username), `created`, `status` (`PENDING`, `ADMITTED`, `DENIED`, `CANCELLED`), `shareKey` (set on admit). |
| `JoinRequestStore` (interface) | Mailbox: `put(JoinRequest)`, `get(id)`, `listFor(activeConnectionUuid)`, `resolve(id, status, shareKey)` (atomic, first writer wins), `remove(id)`. Entries expire after the timeout. |
| `InMemoryJoinRequestStore` | Default implementation. A `ConcurrentHashMap` with expiry on read plus a periodic sweep. |
| `JoinRequestService` | Rules: owner resolution, same-user exemption, authorization, admit/deny/enforce, requester-only delivery. |
| `JoinRequestResource` | REST resource returned by `ModeledUserContext.getResource()`, served under `/api/session/ext/<data-source>/join/`. |

**Clustered builds (fork only):** `RedisJoinRequestStore` implements
`JoinRequestStore` on top of four new `ClusterStore` methods (`putJoinRequest`,
`getJoinRequest`, `listJoinRequests`, `resolveJoinRequest`). It uses the keys
`guac:join:<id>` (hash, TTL = timeout) and `guac:join:idx:<activeConnectionUuid>`
(set, TTL = timeout). `NoOpClusterStore` implements them as unsupported, and
binding selects the in-memory store when not clustered. The upstream PR has no
`ClusterStore`, so it contains the interface and the in-memory store only.

### REST API (`/api/session/ext/<data-source>/join/`)

If `join-request-enabled` is `false`, every endpoint returns 404.

| Method and path | Caller | Behaviour |
|---|---|---|
| `GET status/{connectionId}` | newcomer | `{"state":"free"}` or `{"state":"inUse","users":n}`. `free` when there are no primary active connections, or all of them belong to the caller. Requires READ on the connection. |
| `POST requests` body `{"connectionId"}` | newcomer | Creates a `PENDING` request. Returns `{"id","expires"}`. Requires READ. Returns 409 if the connection is not in use by someone else. |
| `GET requests/{id}` | newcomer | Returns `{"status", "shareKey"?}`. Only the requester can read it. When `ADMITTED`, returns the share key once and then removes the request. Returns `{"status":"FREE"}` if the primary sessions have ended. Returns 404 after expiry. |
| `DELETE requests/{id}` | newcomer | Cancel. Sets `CANCELLED`. Only the requester can do it. |
| `GET incoming` | connected user | Lists `PENDING` requests indexed under active connections that the caller opened as **primary**. Requests for anyone else's sessions are never returned. |
| `POST incoming/{id}` body `{"decision":"admit","shareKey"}` or `{"decision":"deny"}` | connected user | The caller must own a primary active connection that the request targets. Atomic resolve: the first answer wins and later ones get 409. |
| `POST enforce/{connectionId}` | newcomer | Re-checks that the feature is on, the caller has READ, and the connection is active and held by another user. Calls `requestKill` (clustered) or closes the tunnel (non-clustered) for every primary active connection, and sets `CANCELLED` on any pending requests for them. Logs at INFO. Returns 204. |

The share key comes from the connected user's browser calling the **existing**
`GET /api/session/data/<ds>/activeConnections/{id}/sharingCredentials/{profile}`.
That endpoint creates it under the connected user's own permissions. The join
endpoints only carry the key to the requester. They never create one.

### Web app (`guacamole/src/main/frontend`)

- `joinRequestService`: REST wrapper for the endpoints above.
- **Newcomer path:** before the client page creates a managed client for a
  connection, it calls `status`.
  - `inUse` → the waiting dialog via `guacNotification`, with Cancel and Enforce,
    polling `requests/{id}` every 3 s.
  - `ADMITTED` → join with the share key, the same path a share link uses.
  - `DENIED` → "Request declined", with Cancel and Enforce.
  - Expired (404) → "No response from the current user", with Retry and Enforce.
  - `FREE` → connect normally.
- **Connected-user path:** while at least one managed client is `CONNECTED`, poll
  `incoming` every 3 s. Show one prompt at a time: "<user> wants to join", with a
  button per sharing profile of that active connection plus Deny.
  - On a profile choice → call `sharingCredentials/{profile}`, then
    `POST incoming/{id}` with the key.
  - If the connection has no sharing profiles → "<user> wants to join. This
    connection cannot be shared." with OK, which sends a deny.
- Translation keys go under `JOIN.*` in `translations/en.json`. Other languages
  fall back to English.
- If the feature is off, `status` returns 404 and the client connects as today.

## Error handling

| Failure | Behaviour |
|---|---|
| Store unavailable (Redis down) | Join endpoints return 503. The newcomer's client falls back to connecting directly, so a broken mailbox never blocks a connection. Logged at WARN. |
| Connected user lacks permission on the profile, or the profile was removed | Error notification for the connected user. The request stays pending. |
| `requestKill` fails | 500 with a translatable message. The newcomer's dialog stays open to retry. |
| Share key invalid at join (the connected user left) | Standard share error, then a fresh `status` check, which gives `free` and a normal connect. |
| 401 while polling | Polling stops and the normal logout flow handles it. |
| Several connected users answer | Atomic resolve. The first wins and the others get 409, so their prompt is dismissed. |

## Testing

- **Java unit tests:** `JoinRequestService` covering owner resolution, the
  same-user exemption, READ checks on every endpoint, requester-only and
  read-once key delivery, primary-owner-only `incoming`, first-answer-wins,
  expiry, and enforce fan-out to every primary session. `InMemoryJoinRequestStore`
  covering expiry and atomic resolve.
- **Fork:** `RedisJoinRequestStore` against Testcontainers Redis, matching the
  existing cluster tests.
- **JS unit tests:** the waiting-dialog and incoming-prompt state machines with
  mocked HTTP: free, inUse → admitted, denied, expired → retry, enforce, the
  503 fallback, and 401.
- **Manual, two users and one connection:** admit with each profile, deny,
  expire then retry, Enforce, the same-user exemption, the feature off, and the
  two users on different web-app replicas (fork).

## Out of scope

- Push delivery (WebSocket or tunnel instruction). Polling is enough.
- A per-connection enable switch, or a separate permission for Enforce.
- Showing connected users' names in the connection list (GUACAMOLE-1221).
- Creating sharing profiles automatically. Deployments provision them.

## Delivery

- Fork branch `GUACAMOLE-453-join-requests` → PR to fork `main`.
- Upstream PR to `apache/guacamole-client` with only the GUACAMOLE-453 commits.
  It leaves out `ClusterStore` and `RedisJoinRequestStore`, and this document.

## Implementation notes (plan of 2026-10-22)

- **UI lives in the JDBC extension** (`js/joinRequests.js`, registered in the three JDBC manifests), not in the core web app. The feature belongs to the JDBC auth provider, and the core app needs no change.
- **The share key is created server-side.** On admit the connected user posts `{"decision":"admit","sharingProfile":"<id>"}`, and the server calls the existing `ConnectionSharingService.shareConnection` as that user, which is the same path and the same permission checks as `sharingCredentials`. The browser never handles the key on the connected user's side.
- **`ClusterStore` gains five methods:** put, get, list, resolve, remove. Lua scripts make put and resolve atomic.
- **No JavaScript unit tests.** The web app has no AngularJS test harness. The UI is verified manually (see Testing), and all rules sit server-side, where they are unit-tested.
- **Connected users are found per node.** `incoming` lists only requests for sessions served by the node that answers the poll. With sticky load-balancer sessions that is the user's own node. Newcomer status and Enforce are cluster-wide.
- **Build from the root reactor** (`mvn -pl <module> -am …`) so that `guacamole-ext` is built from source rather than resolved from a stale local repository.
