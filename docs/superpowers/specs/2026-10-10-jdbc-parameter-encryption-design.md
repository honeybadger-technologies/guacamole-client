# Encrypted storage of JDBC connection secrets — design

- **Issue:** [GUACAMOLE-558](https://issues.apache.org/jira/browse/GUACAMOLE-558) "Password encryption" (open since 2018; GUACAMOLE-1832 is a duplicate).
- **Scope:** `guacamole-auth-jdbc` (PostgreSQL, MySQL, SQL Server).
- **Status:** design approved, implementation plan pending.

## 1. Problem

Connection and sharing-profile parameters are stored as plain text in `guacamole_connection_parameter.parameter_value` and `guacamole_sharing_profile_parameter.parameter_value`. That includes the passwords, passphrases and private keys Guacamole uses to log in to remote desktops. Anyone who can read those tables, or a dump, backup or snapshot of them, can read every stored credential.

Hashing is not an option. Guacamole is the client: guacd must present the real secret to the RDP/SSH/VNC server, so the value has to be stored in a recoverable form.

## 2. Goals and non-goals

**Goals**
- Secret parameter values are stored encrypted (AES-256-GCM). Non-secret values (hostname, port, …) stay plain text, so operational SQL queries keep working.
- The data-only threat is covered: a database dump, backup, snapshot, read-only database access, or leaked database credentials yield no usable secrets unless the key also leaks.
- REST API, admin UI and parameter tokens behave exactly as today. They see decrypted values in memory.
- Existing plain-text rows keep working, and are encrypted automatically once a key is configured.
- The feature is opt-in. With no key configured, behaviour is byte-for-byte what it is today.
- Key rotation without downtime. Loss of a key is a clear, documented, per-connection failure, never silent corruption.
- No new dependency in the upstreamable part. The JDK provides AES-GCM.

**Non-goals**
- Protecting against an attacker who controls the web application process. The application must decrypt to connect, so that attacker has key and data alike.
- Encrypting user attributes, connection attributes, history, or any table other than the two parameter tables.
- Encrypting traffic between the web application and guacd. That is a separate concern (guacd SSL).

## 3. Components

All in `guacamole-auth-jdbc-base` unless noted.

| Unit | Responsibility |
|---|---|
| `ParameterKeyProvider` (interface) | `String currentKeyId()`; `Optional<SecretKey> keyFor(String keyId)`. |
| `PropertyParameterKeyProvider` | Default provider. Reads the keyring from properties (§7). No keys configured = feature off. |
| `KmsParameterKeyProvider` | **Separate optional module** (`guacamole-auth-jdbc-kms`, not part of the upstream contribution). Same keyring properties, but each value is a data key encrypted by AWS KMS. Each is unwrapped once at startup with KMS `Decrypt`, using the ambient AWS credentials (e.g. IRSA), and the plain keys are held only in memory. |
| `ParameterCipher` | AES-256-GCM, 96-bit random IV, 128-bit tag. Associated data: `owner-type ‖ owner-id ‖ parameter-name`. Stored form: `base64(IV ‖ ciphertext ‖ tag)`. |
| `SecretParameterPolicy` | `isSecret(parameterName)`. True for the name of any field of type `PASSWORD` in any loaded protocol descriptor (connection and sharing-profile forms); for `private-key`, `sftp-private-key`, `client-key`; and for names in `*-parameter-encryption-extra-names`. Independent of protocol: parameter names mean the same thing in every protocol, so no protocol lookup is needed at write or sweep time. |
| `ParameterEncryptionService` | The only entry point used by the services: `protectConnectionParameters(models)` / `protectSharingProfileParameters(models)` before insert; after select, `reveal(...)` on the session path (throws on failure) and `revealOrOmit(...)` on the admin path (omits the value and logs an ERROR). |
| `ParameterEncryptionSweep` | Background startup task that encrypts, re-keys or decrypts existing rows (§6). |

**Integration points.** These are the only call sites that read or write parameter rows:
- `ConnectionService`: parameter inserts in `createObject`/`updateObject`; `retrieveParameters` (REST/admin read).
- `SharingProfileService`: the same three operations.
- `AbstractGuacamoleTunnelService.getGuacamoleConfiguration`: reads connection and sharing-profile parameters straight from the mappers when a session is opened. This is the read that reaches guacd.

`ConnectionParameterModel` and `SharingProfileParameterModel` gain an `encryption` (key ID) field.

The provider implementation is selected by `*-parameter-encryption-provider` (default `property`). The KMS module registers `kms`.

## 4. Schema

These changes are appended to each database's `schema/upgrade/upgrade-pre-1.6.1.sql` and folded into `001-create-schema.sql`. They apply to both `guacamole_connection_parameter` and `guacamole_sharing_profile_parameter`.

| Database | `parameter_value` | New column |
|---|---|---|
| PostgreSQL | `varchar(4096)` → `varchar(8192)` | `parameter_encryption varchar(64) NULL` |
| MySQL | `varchar(4096)` → `varchar(8192)` | `parameter_encryption varchar(64) NULL` |
| SQL Server | `nvarchar(4000)` → `nvarchar(max)` | `parameter_encryption nvarchar(64) NULL` |

- `parameter_encryption` is the key ID; NULL means plain text. There is no backfill.
- The upgrade is additive: older versions ignore the new column.
- Widening is needed because a 4096-bit RSA PEM key (~3.2 KB) grows to ~4.4 KB once encrypted and base64-encoded.
- A secret value whose plain text exceeds **6,000 bytes** is rejected on save with a clear error, so the encrypted form always fits in 8,192 characters. The same limit applies on SQL Server for consistency.
- **Effective limit.** The existing connection parameter validation already caps every value at 4,096 characters (HTTP 413). The cipher additionally caps secret values at 6,000 UTF-8 bytes (HTTP 400). The second limit matters only for multi-byte text, where 4,096 characters can exceed 6,000 bytes.

## 5. Data flow

**Write** (create or update a connection or sharing profile). Updates already delete and re-insert all parameters, so each save uses a fresh IV and the current key.

```
service.create/updateObject
  → getParameterModels(...)                  (owner id known)
  → ParameterEncryptionService.protect…Parameters(models)
       secret && key configured → value = b64(IV‖ct‖tag), encryption = currentKeyId
       otherwise                → unchanged, encryption = NULL
  → parameterMapper.insert(models)
```

**Read**

```
parameterMapper.select(id)
  → ParameterEncryptionService.reveal(...)        (session path)
    or ParameterEncryptionService.revealOrOmit(...) (admin path)
       encryption NULL → value as stored
       encryption kid  → keyFor(kid) → AES-GCM decrypt with associated data
                         missing key / tag mismatch → reveal: GuacamoleServerException
                                                      revealOrOmit: value omitted, ERROR logged
  → GuacamoleConfiguration → parameter tokens → guacd
```

**Failure rule.** A row that cannot be decrypted (missing key, tag mismatch):
- **Session path** (tunnel): opening that connection or sharing profile fails with `GuacamoleServerException`. The message names the owner, the parameter and the key ID, never the value. Ciphertext is never passed to guacd.
- **Admin/REST path** (`retrieveParameters`, which cannot throw): the parameter is omitted from the returned map and an ERROR is logged with the same details. An administrator who saves the connection afterwards writes it without that value. The value was unrecoverable anyway, and the earlier ERROR records why.

**Compatibility with older versions**
- An older version reads the ciphertext as the value. It passes it to the remote server and the login fails; it does not crash.
- If an administrator saves that connection in the older version, the ciphertext is stored as plain text with `parameter_encryption` NULL. It then becomes the password the newer version sends, and the newer version's sweep encrypts it again as if it were the real secret. The original secret is lost for that connection.
- Hence: before any older version serves requests, run `decrypt` mode (§6) and confirm the summary reports `remaining encrypted 0`.

## 6. Startup sweep

The sweep runs in the background after startup, only when a key provider is configured. It never blocks readiness: reads already handle both plain-text and encrypted rows.

When no key provider is configured, the same background task only counts rows with `parameter_encryption IS NOT NULL`. If any exist it logs at ERROR: `<N> stored parameters are encrypted but parameter encryption is not configured; those connections cannot be used.`

- **Candidate rows (encrypt mode):** `(parameter_encryption IS NOT NULL AND parameter_encryption <> currentKeyId) OR (parameter_encryption IS NULL AND parameter_name IN (…) AND parameter_value <> '')`.
  - Every row on a non-current key is re-keyed, whether or not its name is still secret. A row encrypted under a name that later left the policy (extra names changed, a protocol descriptor removed) would otherwise stay on the old key, and retiring that key would lose it.
  - Secret names are passed to the query, so no protocol join is needed.
- **Candidate rows (decrypt mode):** `parameter_encryption IS NOT NULL`.
- **Write is a compare-and-set:**
  `UPDATE … SET parameter_value = :new, parameter_encryption = :kid WHERE <primary key> AND parameter_value = :old AND parameter_encryption IS NOT DISTINCT FROM :oldKid`. SQL Server and MySQL use equivalent NULL-safe comparisons.
  - Concurrent sweeps on several replicas, or an admin saving mid-sweep, make the losing update affect 0 rows. No database lock is needed, and the code path is identical on all three databases.
- **Each row is its own compare-and-set statement**, so no transaction spans rows; progress is logged every 100 rows.
- **Errors:** a failing row is logged (owner, parameter name, key ID, or the exception class for a database error) and skipped; the sweep continues.
- **Summary, encrypt mode:** one INFO line, `encrypted N, re-keyed M, skipped K, failed F, remaining plain-text secrets R, remaining on non-current keys C`. `C` is a re-count after the sweep of encrypted rows whose key ID is not current.
- **Summary, decrypt mode:** one WARN line, `decrypted D, skipped K, failed F, remaining encrypted E`, followed by `Downgrading to a version without parameter encryption is safe only when remaining encrypted is 0 and no other node runs in encrypt mode.` `E` is a re-count after the sweep of rows still encrypted.

**Modes** (`*-parameter-encryption-mode`):

| Mode | Effect |
|---|---|
| `encrypt` (default when keys are configured) | Encrypts plain-text secrets and re-keys rows on non-current keys. |
| `decrypt` | Writes plain text back for every encrypted row. Logged at WARN with counts, including `remaining encrypted`. This is the supported path before downgrading or abandoning the feature; new writes are plain text while it is set. The keyring must stay configured in this mode, since it is needed to decrypt. |

**Rotation** (two phases; required for multi-node deployments, harmless for one node)
1. **Phase 1 — distribute the key.** Add the new key to the keyring on every node, leaving `current-key` unchanged. Roll out fully, so that every node can read the new key before any row uses it.
2. **Phase 2 — switch.** Set `current-key` to the new ID on every node and roll out. The sweep re-keys every row on a non-current key.
3. Confirm the summary reports `remaining on non-current keys 0` before removing the old key from the live keyring.
4. Retired keys are **archived, not destroyed**, for at least the database backup retention period. A restored backup still needs them.

Why two phases: a node that lacks the new key cannot read rows another node has already re-keyed. Sessions on those connections fail there, and an administrator who saves such a connection on that node writes it without the omitted value, which is then lost.

**Key loss.** Values under a lost key are unrecoverable by design. Those connections fail with a clear error until an administrator re-enters the secrets.

## 7. Configuration

`<db>` is `postgresql`, `mysql` or `sqlserver`, following the existing property naming. Each property also has the usual environment-variable form.

| Property | Default | Meaning |
|---|---|---|
| `<db>-parameter-encryption-keys` | (none — feature off) | Keyring as `kid:base64key[,kid:base64key…]`. Each key is 32 bytes. With the KMS provider, each value is a base64 KMS ciphertext blob instead. |
| `<db>-parameter-encryption-current-key` | (required if keys set) | Key ID used for new encryption. It must exist in the keyring, or startup fails. |
| `<db>-parameter-encryption-provider` | `property` | `property`, or `kms` when that module is installed. |
| `<db>-parameter-encryption-mode` | `encrypt` | `encrypt` or `decrypt` (§6). |
| `<db>-parameter-encryption-extra-names` | (none) | Comma-separated extra parameter names to treat as secret. |

**Startup validation fails fast** with a clear message when:
- the keyring is unset but `current-key` or `extra-names` is set, or `provider` or `mode` differs from its default (most likely a secret that failed to render; running with encryption silently off would leave encrypted connections unusable and store new secrets as plain text);
- a keyring entry's key identifier is invalid (reported by entry position, never by its text, since a reversed entry would put the key there);
- a key does not decode;
- a key is not 32 bytes;
- the current key ID is missing from the keyring;
- KMS cannot unwrap a key.

A misconfigured key must never degrade silently to plain text.

## 8. Testing

**Unit (JUnit 5)**
- **`ParameterCipher`:**
  - round trip;
  - a fresh IV on every encryption;
  - a value moved to a different owner or parameter name fails to decrypt;
  - any flipped byte fails;
  - a wrong key fails;
  - the 6,000-byte limit is enforced.
- **`PropertyParameterKeyProvider`:** each startup-validation failure in §7, plus feature-off.
- **`SecretParameterPolicy`:**
  - every `PASSWORD` field in every bundled protocol descriptor is secret;
  - so are the three private-key names and the extra names;
  - `hostname` and `port` are not.
- **`ParameterEncryptionService`:** plain-text pass-through; a missing key ID yields the per-connection failure.
- **`ParameterEncryptionSweep`:**
  - two concurrent sweeps encrypt each row exactly once;
  - rotation;
  - `decrypt` mode;
  - an admin write racing the sweep.
- **KMS module:** provider behaviour against a mocked KMS client.

**End-to-end (Docker, each of PostgreSQL, MySQL, SQL Server)**
1. Start from the previous schema with plain-text connections. Apply the upgrade and start with a key.
2. The sweep reports `R = 0`. Raw SQL shows base64 plus a key ID for secret parameters only.
3. A real SSH session works using the decrypted password.
4. Create and edit a connection through REST; the admin API returns decrypted values.
5. Rotate the key and confirm every row is re-keyed.
6. Use `decrypt` mode, then confirm the previous version connects again.

**Scale:** time the sweep over ~600 synthetic connections.

## 9. Rollout guidance (for operators)

1. Generate a key, or a KMS-wrapped data key, and store it separately from database credentials, with an off-host backup.
2. Apply the upgrade SQL.
   - The upgrade is **required for this version even with encryption disabled**, because the mappers read and write `parameter_encryption`.
   - If parts of `upgrade-pre-1.6.1.sql` were already applied by an earlier upgrade, apply only its "Allow secret connection and sharing profile parameters to be stored encrypted" section.
3. Deploy with the key configured.
   - In a multi-replica deployment, stop all old replicas before new ones start. An old replica reads ciphertext as soon as any new replica's sweep runs.
4. Confirm the sweep reports `R = 0`, and spot-check sessions for each protocol in use.
5. **Backups taken before the sweep still contain plain text.**
   - Protection of backups is complete only once every pre-sweep backup has expired.
   - Manual snapshots and exports taken before the sweep must be deleted, or replaced after it.

## 10. Upstream boundary

The contribution to Apache Guacamole under GUACAMOLE-558 is everything except the KMS module:
- interface;
- property provider;
- cipher;
- policy;
- service;
- sweep;
- modes;
- schema for all three databases;
- tests.

The KMS provider stays downstream as an optional module, because it adds the AWS SDK.

The schema changes share `upgrade-pre-1.6.1.sql` with the GUACAMOLE-1743 and GUACAMOLE-829 contributions. Whichever merges later appends to that file.
