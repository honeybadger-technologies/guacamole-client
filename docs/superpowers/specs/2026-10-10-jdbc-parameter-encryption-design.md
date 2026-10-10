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
| `SecretParameterPolicy` | `isSecret(protocol, parameterName)`. True for any field of type `PASSWORD` in the loaded protocol descriptors; for `private-key`, `sftp-private-key` and `client-key`; and for any name in the `*-parameter-encryption-extra-names` property. |
| `ParameterEncryptionService` | The only entry point used by the services: `encrypt(models)` before insert, `decrypt(models)` after select. |
| `ParameterEncryptionSweep` | Background startup task that encrypts, re-keys or decrypts existing rows (§6). |

**Integration points.** These are the only call sites that read or write parameter rows:
- `ConnectionService`: parameter inserts in `createObject`/`updateObject`, and `retrieveParameters`.
- `SharingProfileService`: the same three operations.

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

## 5. Data flow

**Write** (create or update a connection or sharing profile). Updates already delete and re-insert all parameters, so each save uses a fresh IV and the current key.

```
service.create/updateObject
  → getParameterModels(...)                  (owner id known)
  → ParameterEncryptionService.encrypt(models)
       secret && key configured → value = b64(IV‖ct‖tag), encryption = currentKeyId
       otherwise                → unchanged, encryption = NULL
  → parameterMapper.insert(models)
```

**Read**

```
parameterMapper.select(id)
  → ParameterEncryptionService.decrypt(models)
       encryption NULL → value as stored
       encryption kid  → keyFor(kid) → AES-GCM decrypt with associated data
                         missing key / tag mismatch → GuacamoleServerException
  → GuacamoleConfiguration → parameter tokens → guacd
```

**Failure rule.** A row that cannot be decrypted fails only the connection or sharing profile that owns it. The error and log name the owner, the parameter name and the key ID, never the value. Ciphertext is never passed on as if it were a password.

**Compatibility with older versions**
- An older version that re-saves a connection writes plain-text rows with `parameter_encryption` NULL. That is correct.
- An older version reading encrypted rows passes ciphertext to the remote server and the login fails. It does not crash.
- Downgrading after encryption therefore requires `decrypt` mode first (§6).

## 6. Startup sweep

The sweep runs in the background after startup, only when a key provider is configured. It never blocks readiness: reads already handle both plain-text and encrypted rows.

- **Candidate rows:** `parameter_encryption IS NULL OR parameter_encryption <> currentKeyId`. Rows are joined to the owning connection's protocol; for sharing profiles, that is the primary connection's protocol. `SecretParameterPolicy` then filters them in Java.
- **Write is a compare-and-set:**
  `UPDATE … SET parameter_value = :new, parameter_encryption = :kid WHERE <primary key> AND parameter_value = :old AND parameter_encryption IS NOT DISTINCT FROM :oldKid`. SQL Server and MySQL use equivalent NULL-safe comparisons.
  - Concurrent sweeps on several replicas, or an admin saving mid-sweep, make the losing update affect 0 rows. No database lock is needed, and the code path is identical on all three databases.
- **Batches** of 100 rows per transaction.
- **Errors:** a failing row is logged (owner, parameter name, key ID) and skipped; the sweep continues.
- **Summary:** one INFO line, `encrypted N, re-keyed M, decrypted D, skipped K, failed F, remaining plain-text secrets R`.

**Modes** (`*-parameter-encryption-mode`):

| Mode | Effect |
|---|---|
| `encrypt` (default when keys are configured) | Encrypts plain-text secrets and re-keys rows on non-current keys. |
| `decrypt` | Writes plain text back for every encrypted row. Logged at WARN with counts. This is the supported path before downgrading or abandoning the feature; new writes are plain text while it is set. The keyring must stay configured in this mode, since it is needed to decrypt. |

**Rotation**
1. Add the new key, keep the old one, and set `current-key` to the new ID. Roll out.
2. The sweep re-keys every row.
3. When `SELECT count(*) … WHERE parameter_encryption = '<old>'` returns 0, remove the old key from the live keyring.
4. Retired keys are **archived, not destroyed**, for at least the database backup retention period. A restored backup still needs them.

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
