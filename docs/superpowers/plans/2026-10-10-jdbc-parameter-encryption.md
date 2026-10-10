# JDBC Connection Secret Encryption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store secret connection and sharing-profile parameters (passwords, passphrases, private keys) encrypted with AES-256-GCM in the JDBC auth extension. Encryption is opt-in, existing plain-text rows are swept to encrypted form, keys rotate, and there is a `decrypt` mode for rollback.

**Architecture:** A small `encryption` package in `guacamole-auth-jdbc-base` handles all of it:
- a cipher;
- a key-provider SPI, discovered through `ServiceLoader`;
- a secret-name policy derived from the protocol descriptors;
- a facade service, called from every place that reads or writes parameter rows;
- a background sweep that uses compare-and-set row updates, so no database lock is needed.

A nullable `parameter_encryption` column (key ID) marks encrypted rows, and `parameter_value` is widened. An optional fork-only module adds an AWS KMS key provider.

**Tech Stack:** Java 8 source level (built with JDK 21), Guice, MyBatis, JUnit 5, `javax.crypto` (AES/GCM/NoPadding). The KMS module only uses the AWS SDK v2 (`kms`, `sts`, `url-connection-client`).

**Spec:** `docs/superpowers/specs/2026-10-10-jdbc-parameter-encryption-design.md`. Read it first. Task 1 corrects three points in it.

## Global Constraints

- Every commit message: `GUACAMOLE-558: <Capitalized summary ending with a period.>`, then a blank line, a body if useful, and the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Branch: `GUACAMOLE-558-parameter-encryption`, a dedicated git worktree. The repo is **PUBLIC**. Never write internal company, account, cluster or environment names into code, commits or PR text.
- Java 8 APIs only (`-source/-target 1.8`). No `var`, no `List.of`, no `Optional.isEmpty`.
- Every new file carries the ASF license header; copy it from an existing file. Apache RAT fails the build otherwise.
- No new dependency in `guacamole-auth-jdbc-base` or the database modules. The AWS SDK appears only in Task 9's module.
- Schema changes are additive: nullable new column, widened value column, no backfill. They are appended to `schema/upgrade/upgrade-pre-1.6.1.sql` and folded into `schema/001-create-schema.sql`, for **postgresql, mysql and sqlserver**.
- Constants (from the spec, verbatim):
  - AES-256-GCM, 96-bit random IV, 128-bit tag;
  - stored form `base64(IV ‖ ciphertext ‖ tag)`;
  - associated data `owner-type ‖ owner-id ‖ parameter-name`;
  - plain-text limit for secret values: **6,000 bytes**;
  - widened column `varchar(8192)` (PostgreSQL, MySQL), `nvarchar(max)` (SQL Server);
  - key column `parameter_encryption varchar(64)` / `nvarchar(64)`, NULL meaning plain text;
  - sweep batch size **100**.
- Properties, where `<db>` is `postgresql` | `mysql` | `sqlserver`:
  - `<db>-parameter-encryption-keys`
  - `<db>-parameter-encryption-current-key`
  - `<db>-parameter-encryption-provider` (default `property`)
  - `<db>-parameter-encryption-mode` (`encrypt` | `decrypt`, default `encrypt`)
  - `<db>-parameter-encryption-extra-names`
- Built-in secret names (in addition to every `PASSWORD`-typed field): `private-key`, `sftp-private-key`, `client-key`.
- Never log, or put into an exception message, a parameter value or key material. Log only the owner, the parameter name and the key ID.
- Build with the shim `~/.local/bin/mvn` (JDK 21). Never `mvn install`: other worktrees share `~/.m2`. Use `verify` or `test` with `-pl … -am`, or a full `docker build --progress=plain .`, which runs every module's tests on Linux. The webapp module needs `-Dexec.skip=true -Dskip.npm -Dskip.installnodenpm`.

## Review Focus

1. **A connection whose secret parameter cannot be decrypted** (missing key, tampered row). Opening it must fail with a clear error naming the connection, the parameter and the key ID. Ciphertext must never reach guacd as a password. → Task 7, `TunnelDecryptFailureTest`.
2. **An admin opens an undecryptable connection in the editor and clicks Save.** The REST read omits the value, so the save writes the connection back without it. This is expected, since the secret was unrecoverable, but it must be logged at ERROR before the save, not happen silently. → Task 6 `unknownKeyFailsWithoutLeaking` (revealOrOmit returns null); Task 10 Step 2.10 observes the ERROR line in the real log.
3. **A secret value over 6,000 bytes** (e.g. an 8 KB private key) must be rejected on save with a client error that names the parameter. It must not be truncated, and must not overflow the column. → Task 6, `rejectsOversizedSecret`.
4. **A value that already looks like base64** (e.g. a password `QUJD…==`) with `parameter_encryption` NULL must be treated as plain text and encrypted, not "decrypted". → Task 6, `nullKeyIdIsPlainTextEvenIfBase64`.
5. **Empty or absent parameters.** The services skip empty values on insert today. Encryption must not turn "empty" into an encrypted empty string. → Task 6, `emptyValueIsNotEncrypted`.

---

## File Structure

New package `org.apache.guacamole.auth.jdbc.encryption` (in `guacamole-auth-jdbc-base`):

| File | Responsibility |
|---|---|
| `ParameterOwner.java` | Enum `CONNECTION("connection")` / `SHARING_PROFILE("sharing-profile")`. Supplies the owner-type part of the associated data. |
| `ParameterCipher.java` | Pure AES-GCM encrypt/decrypt of one value with associated data. No I/O. |
| `ParameterKeyProvider.java` | SPI: `currentKeyId()`, `keyFor(kid)`. |
| `ParameterKeyProviderFactory.java` | SPI factory: `getName()`, `create(JDBCEnvironment)`. Discovered via `ServiceLoader`. |
| `Keyring.java` | Parses `kid:base64,…` into an immutable `Map<String, byte[]>` and validates it. Shared by both providers. |
| `PropertyParameterKeyProvider.java` (+ `…Factory`) | Default provider: the keys come straight from the property. |
| `SecretParameterPolicy.java` | `isSecret(name)`, built from the protocol descriptors, the built-in names and the extra names. |
| `ParameterEncryptionService.java` | Facade used by the services and the tunnel: `protect`, `reveal`, `revealOrOmit`, list helpers. |
| `ParameterRow.java` | Plain row model used by the sweep (owner ID, name, value, key ID). |
| `ParameterEncryptionMapper.java` | MyBatis interface: select candidate rows, and the compare-and-set update for both tables. |
| `ParameterEncryptionSweep.java` | Background sweep: `run()` returns `SweepResult`. |
| `SweepStarter.java` | Eager singleton that starts the sweep on a daemon thread when encryption is enabled. |
| `META-INF/services/org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProviderFactory` | Registers the property factory. |

Modified:

| File | Change |
|---|---|
| `connection/ConnectionParameterModel.java`, `sharingprofile/SharingProfileParameterModel.java` | Add the `encryption` field and accessors. |
| `connection/ConnectionService.java` | Protect before insert (create and update); revealOrOmit in `retrieveParameters`. |
| `sharingprofile/SharingProfileService.java` | Same changes. |
| `tunnel/AbstractGuacamoleTunnelService.java` | `getGuacamoleConfiguration` reveals, and gains `throws GuacamoleException`. |
| `JDBCEnvironment.java` + `PostgreSQLEnvironment`, `MySQLEnvironment`, `SQLServerEnvironment` and each `*GuacamoleProperties` | Five property getters. |
| `JDBCAuthenticationProviderModule.java` | Bind the key provider, policy and service; `addMapperClass(ParameterEncryptionMapper.class)`; `bind(SweepStarter.class).asEagerSingleton()`. |
| Per database: `ConnectionParameterMapper.xml`, `SharingProfileParameterMapper.xml`; new `encryption/ParameterEncryptionMapper.xml`; `schema/001-create-schema.sql`; `schema/upgrade/upgrade-pre-1.6.1.sql` | Columns and SQL. |

Fork-only module `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-kms/` (Task 9): `KmsParameterKeyProvider`, its factory, and the services registration.

Tests go under `guacamole-auth-jdbc-base/src/test/java/org/apache/guacamole/auth/jdbc/encryption/`.

---

### Task 1: Correct the spec

**Files:**
- Modify: `docs/superpowers/specs/2026-10-10-jdbc-parameter-encryption-design.md`

**Interfaces:** none.

- [ ] **Step 1: Apply three corrections**

In §3 "Integration points", replace the bullet list with:

```markdown
- `ConnectionService`: parameter inserts in `createObject`/`updateObject`; `retrieveParameters` (REST/admin read).
- `SharingProfileService`: the same three operations.
- `AbstractGuacamoleTunnelService.getGuacamoleConfiguration`: reads connection and sharing-profile parameters straight from the mappers when a session is opened. This is the read that reaches guacd.
```

In §3, replace the `SecretParameterPolicy` row with:

```markdown
| `SecretParameterPolicy` | `isSecret(parameterName)`. True for the name of any field of type `PASSWORD` in any loaded protocol descriptor (connection and sharing-profile forms); for `private-key`, `sftp-private-key`, `client-key`; and for names in `*-parameter-encryption-extra-names`. Independent of protocol: parameter names mean the same thing in every protocol, so no protocol lookup is needed at write or sweep time. |
```

In §5, replace the "Failure rule" paragraph with:

```markdown
**Failure rule.** A row that cannot be decrypted (missing key, tag mismatch):
- **Session path** (tunnel): opening that connection or sharing profile fails with `GuacamoleServerException`. The message names the owner, the parameter and the key ID, never the value. Ciphertext is never passed to guacd.
- **Admin/REST path** (`retrieveParameters`, which cannot throw): the parameter is omitted from the returned map and an ERROR is logged with the same details. An administrator who saves the connection afterwards writes it without that value. The value was unrecoverable anyway, and the earlier ERROR records why.
```

In §6, replace "Rows are joined to the owning connection's protocol; for sharing profiles, that is the primary connection's protocol. `SecretParameterPolicy` then filters them in Java." with "Secret names are passed to the query (`parameter_name IN (…)`), so no protocol join is needed."

In §6, replace "**Batches** of 100 rows per transaction." with "**Each row is its own compare-and-set statement**, so no transaction spans rows; progress is logged every 100 rows."

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-10-10-jdbc-parameter-encryption-design.md
git commit -m "GUACAMOLE-558: Correct design for the tunnel read path and protocol-independent policy." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Schema, models and mapper columns

**Files:**
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-{postgresql,mysql,sqlserver}/schema/001-create-schema.sql`
- Modify: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-{postgresql,mysql,sqlserver}/schema/upgrade/upgrade-pre-1.6.1.sql`
- Modify: `…/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/connection/ConnectionParameterModel.java`
- Modify: `…/guacamole-auth-jdbc-base/src/main/java/org/apache/guacamole/auth/jdbc/sharingprofile/SharingProfileParameterModel.java`
- Modify: `…/guacamole-auth-jdbc-{db}/src/main/resources/org/apache/guacamole/auth/jdbc/connection/ConnectionParameterMapper.xml` (×3)
- Modify: `…/guacamole-auth-jdbc-{db}/src/main/resources/org/apache/guacamole/auth/jdbc/sharingprofile/SharingProfileParameterMapper.xml` (×3)

**Interfaces:**
- Produces: `ConnectionParameterModel.getEncryption()` / `setEncryption(String)`; the same on `SharingProfileParameterModel`. Mapper `select` fills `encryption`, and `insert` writes it.

- [ ] **Step 1: Models.** In both model classes, add after the `value` field:

```java
    /**
     * The identifier of the key the value is encrypted with, or null if the
     * value is stored as plain text.
     */
    private String encryption;

    /**
     * Returns the identifier of the key the value is encrypted with.
     *
     * @return
     *     The key identifier, or null if the value is plain text.
     */
    public String getEncryption() {
        return encryption;
    }

    /**
     * Sets the identifier of the key the value is encrypted with.
     *
     * @param encryption
     *     The key identifier, or null if the value is plain text.
     */
    public void setEncryption(String encryption) {
        this.encryption = encryption;
    }
```

- [ ] **Step 2: Upgrade SQL.** Append to each `upgrade-pre-1.6.1.sql`.

PostgreSQL:
```sql

--
-- Allow secret connection and sharing profile parameters to be stored
-- encrypted. parameter_encryption holds the identifier of the key; NULL
-- denotes a plain-text value.
--

ALTER TABLE guacamole_connection_parameter
    ALTER COLUMN parameter_value TYPE varchar(8192),
    ADD COLUMN parameter_encryption varchar(64);

ALTER TABLE guacamole_sharing_profile_parameter
    ALTER COLUMN parameter_value TYPE varchar(8192),
    ADD COLUMN parameter_encryption varchar(64);
```

MySQL:
```sql

--
-- Allow secret connection and sharing profile parameters to be stored
-- encrypted. parameter_encryption holds the identifier of the key; NULL
-- denotes a plain-text value.
--

ALTER TABLE guacamole_connection_parameter
    MODIFY `parameter_value` varchar(8192) NOT NULL,
    ADD COLUMN `parameter_encryption` varchar(64);

ALTER TABLE guacamole_sharing_profile_parameter
    MODIFY `parameter_value` varchar(8192) NOT NULL,
    ADD COLUMN `parameter_encryption` varchar(64);
```

SQL Server:
```sql

--
-- Allow secret connection and sharing profile parameters to be stored
-- encrypted. parameter_encryption holds the identifier of the key; NULL
-- denotes a plain-text value.
--

ALTER TABLE [guacamole_connection_parameter]
    ALTER COLUMN [parameter_value] [nvarchar](max) NOT NULL;
ALTER TABLE [guacamole_connection_parameter]
    ADD [parameter_encryption] [nvarchar](64);
GO

ALTER TABLE [guacamole_sharing_profile_parameter]
    ALTER COLUMN [parameter_value] [nvarchar](max) NOT NULL;
ALTER TABLE [guacamole_sharing_profile_parameter]
    ADD [parameter_encryption] [nvarchar](64);
GO
```

- [ ] **Step 3: Create-schema.** In each `001-create-schema.sql`, change both `parameter_value` definitions to the widened type, and add `parameter_encryption` right after each:
  - PostgreSQL: `parameter_value varchar(8192) NOT NULL,` then `parameter_encryption varchar(64),`.
  - MySQL: `` `parameter_value` varchar(8192) NOT NULL, `` then `` `parameter_encryption` varchar(64), ``.
  - SQL Server: `[parameter_value] [nvarchar](max) NOT NULL,` then `[parameter_encryption] [nvarchar](64),`.

  Keep the alignment style of the surrounding columns.

- [ ] **Step 4: Mapper XML (×6).** In every `ConnectionParameterMapper.xml` and `SharingProfileParameterMapper.xml`:
  - add `<result column="parameter_encryption" property="encryption" jdbcType="VARCHAR"/>` to the result map;
  - add `parameter_encryption` to the `select` column list;
  - add it to the `insert` column list, with value `#{parameter.encryption,jdbcType=VARCHAR}` as the last tuple element.

  For example, PostgreSQL `ConnectionParameterMapper.xml` `insert` becomes:
```xml
        INSERT INTO guacamole_connection_parameter (
            connection_id,
            parameter_name,
            parameter_value,
            parameter_encryption
        )
        VALUES
            <foreach collection="parameters" item="parameter" separator=",">
                (#{parameter.connectionIdentifier,jdbcType=INTEGER}::integer,
                 #{parameter.name,jdbcType=VARCHAR},
                 #{parameter.value,jdbcType=VARCHAR},
                 #{parameter.encryption,jdbcType=VARCHAR})
            </foreach>
```
  MySQL and SQL Server use their existing identifier casting and quoting; copy the existing lines and append the fourth value.

- [ ] **Step 5: Verify the SQL on real databases.** Apply the old schema (from `origin/main`) and then the upgrade, and separately the new create-schema, on each database:

```bash
S=extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-postgresql/schema
docker run -d --name pg558 --tmpfs /var/lib/postgresql/data -e POSTGRES_PASSWORD=x postgres:16
until docker exec pg558 pg_isready -q; do sleep 1; done; sleep 2
p(){ docker exec -i pg558 psql -q -v ON_ERROR_STOP=1 -U postgres -d $1; }
docker exec pg558 createdb -U postgres up; docker exec pg558 createdb -U postgres fresh
{ git show origin/main:$S/001-create-schema.sql; git show origin/main:$S/002-create-admin-user.sql; } | p up
p up < <(sed -n '/Allow secret connection/,$p' $S/upgrade/upgrade-pre-1.6.1.sql)
cat $S/001-create-schema.sql $S/002-create-admin-user.sql | p fresh
for d in up fresh; do docker exec pg558 psql -U postgres -d $d -Atc "select '$d', count(*) from information_schema.columns where column_name='parameter_encryption'; select '$d', character_maximum_length from information_schema.columns where table_name='guacamole_connection_parameter' and column_name='parameter_value'"; done
docker rm -f pg558
```
Expected: both databases report `2` and `8192`.

`origin/main`'s 001 already contains the 1743 and 829 changes, so only the new 558 section is applied to `up`. Repeat for MySQL (`mysql:8.0` with `--tmpfs /var/lib/mysql`, check `parameter_encryption` count 2 and `CHARACTER_MAXIMUM_LENGTH` 8192) and SQL Server (`mcr.microsoft.com/mssql/server:2022-latest`, `sqlcmd -b`, check `max_length` −1 in `sys.columns`). Expected: no errors on any database.

- [ ] **Step 6: Build**

Run: `~/.local/bin/mvn -q verify -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-postgresql,extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-mysql,extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-sqlserver -am -Dexec.skip=true -Dskip.npm -Dskip.npx -Dskip.installnodenpm`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-558: Add nullable parameter_encryption column and widen parameter values." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `ParameterOwner` and `ParameterCipher`

**Files:**
- Create: `…/jdbc/encryption/ParameterOwner.java`
- Create: `…/jdbc/encryption/ParameterCipher.java`
- Create: `…/jdbc/encryption/package-info.java` (copy the header and style from `jdbc/reset/package-info.java`)
- Test: `…/src/test/java/org/apache/guacamole/auth/jdbc/encryption/ParameterCipherTest.java`

**Interfaces:**
- Produces:
  - `enum ParameterOwner { CONNECTION, SHARING_PROFILE; String getType(); }`
  - `final class ParameterCipher`:
    - `static final int MAX_PLAINTEXT_BYTES = 6000;`
    - `static final int KEY_BYTES = 32;`
    - `static String encrypt(byte[] key, ParameterOwner owner, String ownerId, String name, String plaintext)`
    - `static String decrypt(byte[] key, ParameterOwner owner, String ownerId, String name, String stored) throws GeneralSecurityException`

    `encrypt` throws `IllegalArgumentException` if the plain text is over 6,000 UTF-8 bytes. `decrypt` throws `GeneralSecurityException` (including `AEADBadTagException`) on a wrong key, wrong associated data or tampering, and on malformed base64 or length.

- [ ] **Step 1: Write the failing test**

```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

public class ParameterCipherTest {

    private static byte[] key() {
        byte[] k = new byte[ParameterCipher.KEY_BYTES];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    public void roundTrip() throws Exception {
        byte[] k = key();
        String stored = ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "password", "s3cret ü");
        assertEquals("s3cret ü", ParameterCipher.decrypt(k, ParameterOwner.CONNECTION, "7", "password", stored));
    }

    @Test
    public void freshIvEveryTime() {
        byte[] k = key();
        String a = ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "password", "same");
        String b = ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "password", "same");
        assertNotEquals(a, b);
    }

    @Test
    public void boundToOwnerName() {
        byte[] k = key();
        String stored = ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "password", "x");
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(k, ParameterOwner.CONNECTION, "8", "password", stored));
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(k, ParameterOwner.CONNECTION, "7", "passphrase", stored));
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(k, ParameterOwner.SHARING_PROFILE, "7", "password", stored));
    }

    @Test
    public void ownerIdAndNameCannotBeShifted() {
        // "1"+"2x" must not equal "12"+"x": associated data is length-prefixed
        byte[] k = key();
        String stored = ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "1", "2x", "v");
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(k, ParameterOwner.CONNECTION, "12", "x", stored));
    }

    @Test
    public void tamperedByteFails() {
        byte[] k = key();
        byte[] raw = Base64.getDecoder().decode(
            ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "password", "x"));
        for (int i = 0; i < raw.length; i++) {
            byte[] copy = Arrays.copyOf(raw, raw.length);
            copy[i] ^= 1;
            String bad = Base64.getEncoder().encodeToString(copy);
            assertThrows(GeneralSecurityException.class,
                () -> ParameterCipher.decrypt(k, ParameterOwner.CONNECTION, "7", "password", bad));
        }
    }

    @Test
    public void wrongKeyFails() {
        String stored = ParameterCipher.encrypt(key(), ParameterOwner.CONNECTION, "7", "password", "x");
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(key(), ParameterOwner.CONNECTION, "7", "password", stored));
    }

    @Test
    public void malformedStoredValueFails() {
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(key(), ParameterOwner.CONNECTION, "7", "password", "not base64!"));
        assertThrows(GeneralSecurityException.class,
            () -> ParameterCipher.decrypt(key(), ParameterOwner.CONNECTION, "7", "password", "QUJD"));
    }

    @Test
    public void sizeLimit() {
        byte[] k = key();
        char[] max = new char[ParameterCipher.MAX_PLAINTEXT_BYTES];
        Arrays.fill(max, 'a');
        String stored = ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "private-key", new String(max));
        assertTrue(stored.length() <= 8192, "encrypted form must fit varchar(8192)");
        char[] over = new char[ParameterCipher.MAX_PLAINTEXT_BYTES + 1];
        Arrays.fill(over, 'a');
        assertThrows(IllegalArgumentException.class,
            () -> ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "private-key", new String(over)));
        // limit is in bytes, not chars: 3001 two-byte chars = 6002 bytes
        char[] wide = new char[3001];
        Arrays.fill(wide, 'é');
        assertEquals(6002, new String(wide).getBytes(StandardCharsets.UTF_8).length);
        assertThrows(IllegalArgumentException.class,
            () -> ParameterCipher.encrypt(k, ParameterOwner.CONNECTION, "7", "private-key", new String(wide)));
    }

}
```

- [ ] **Step 2: Run, verify it fails**

Run: `cd extensions/guacamole-auth-jdbc && ~/.local/bin/mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest=ParameterCipherTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol: class ParameterCipher`.

- [ ] **Step 3: Implement**

`ParameterOwner.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

/**
 * The kinds of object whose parameters may be stored encrypted. The type
 * string is bound into each ciphertext, so a value cannot be moved between
 * a connection and a sharing profile.
 */
public enum ParameterOwner {

    /** Parameters of a connection (guacamole_connection_parameter). */
    CONNECTION("connection"),

    /** Parameters of a sharing profile (guacamole_sharing_profile_parameter). */
    SHARING_PROFILE("sharing-profile");

    private final String type;

    ParameterOwner(String type) {
        this.type = type;
    }

    /**
     * Returns the stable type string bound into ciphertexts.
     *
     * @return
     *     The type string.
     */
    public String getType() {
        return type;
    }

}
```

`ParameterCipher.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of a single parameter value, bound to its owner and
 * parameter name through the associated data. Stored form is
 * base64(IV || ciphertext || tag).
 */
public final class ParameterCipher {

    /** Largest plain text, in UTF-8 bytes, whose stored form fits 8192 chars. */
    public static final int MAX_PLAINTEXT_BYTES = 6000;

    /** Required key length in bytes (AES-256). */
    public static final int KEY_BYTES = 32;

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ParameterCipher() {}

    /**
     * Builds length-prefixed associated data so that no two distinct
     * (owner, id, name) triples share an encoding.
     */
    private static byte[] associatedData(ParameterOwner owner, String ownerId,
            String name) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(owner.getType());
            out.writeUTF(ownerId);
            out.writeUTF(name);
            return bytes.toByteArray();
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Encrypts the given value.
     *
     * @return
     *     The stored form, base64(IV || ciphertext || tag).
     *
     * @throws IllegalArgumentException
     *     If the value exceeds MAX_PLAINTEXT_BYTES or the key is not
     *     KEY_BYTES long.
     */
    public static String encrypt(byte[] key, ParameterOwner owner,
            String ownerId, String name, String plaintext) {

        byte[] data = plaintext.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_PLAINTEXT_BYTES)
            throw new IllegalArgumentException("Value exceeds "
                    + MAX_PLAINTEXT_BYTES + " bytes.");
        if (key.length != KEY_BYTES)
            throw new IllegalArgumentException("Key must be "
                    + KEY_BYTES + " bytes.");

        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData(owner, ownerId, name));
            byte[] sealed = cipher.doFinal(data);
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length)
                            .put(iv).put(sealed).array());
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable.", e);
        }

    }

    /**
     * Decrypts a stored value.
     *
     * @throws GeneralSecurityException
     *     If the value is malformed, was encrypted under another key, or does
     *     not belong to this owner and parameter name.
     */
    public static String decrypt(byte[] key, ParameterOwner owner,
            String ownerId, String name, String stored)
            throws GeneralSecurityException {

        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(stored);
        }
        catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Stored value is not base64.");
        }
        if (raw.length < IV_BYTES + TAG_BITS / 8)
            throw new GeneralSecurityException("Stored value is too short.");

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
        cipher.updateAAD(associatedData(owner, ownerId, name));
        byte[] data = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
        return new String(data, StandardCharsets.UTF_8);

    }

}
```

- [ ] **Step 4: Run, verify it passes**

Run: the same command as Step 2. Expected: `Tests run: 8, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base/src/{main,test}/java/org/apache/guacamole/auth/jdbc/encryption
git commit -m "GUACAMOLE-558: Add AES-256-GCM cipher for parameter values bound to owner and name." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Properties, keyring and the property key provider

**Files:**
- Modify: `…/jdbc/JDBCEnvironment.java`
- Modify: `…/postgresql/conf/PostgreSQLEnvironment.java`, `PostgreSQLGuacamoleProperties.java`, and the MySQL and SQL Server equivalents
- Create: `…/jdbc/encryption/ParameterKeyProvider.java`, `ParameterKeyProviderFactory.java`, `Keyring.java`, `PropertyParameterKeyProvider.java`, `PropertyParameterKeyProviderFactory.java`
- Create: `…/guacamole-auth-jdbc-base/src/main/resources/META-INF/services/org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProviderFactory`
- Test: `…/encryption/KeyringTest.java`, `…/encryption/PropertyParameterKeyProviderTest.java`

**Interfaces:**
- Produces, on `JDBCEnvironment` (abstract, implemented per database):
  - `String getParameterEncryptionKeys() throws GuacamoleException` (null if unset)
  - `String getParameterEncryptionCurrentKey() throws GuacamoleException` (null if unset)
  - `String getParameterEncryptionProvider() throws GuacamoleException` (default `"property"`)
  - `String getParameterEncryptionMode() throws GuacamoleException` (default `"encrypt"`)
  - `String getParameterEncryptionExtraNames() throws GuacamoleException` (null if unset)
- Produces:
  - `interface ParameterKeyProvider { boolean isEnabled(); String currentKeyId(); byte[] keyFor(String keyId); }`. `keyFor` returns null when the key ID is unknown. `currentKeyId()` is only valid when `isEnabled()`.
  - `interface ParameterKeyProviderFactory { String getName(); ParameterKeyProvider create(JDBCEnvironment environment) throws GuacamoleException; }`
  - `final class Keyring`:
    - `static Map<String, byte[]> parse(String spec, Decoder valueDecoder) throws GuacamoleServerException`;
    - `interface Decoder { byte[] decode(String keyId, String value) throws GuacamoleServerException; }`;
    - `static Map<String, byte[]> parseBase64(String spec)`, a convenience using a base64 decoder plus a 32-byte check.
  - `PropertyParameterKeyProvider(Map<String, byte[]> keys, String currentKeyId)` with a validating constructor; a static `DISABLED` instance.
  - `static ParameterKeyProvider ParameterKeyProviderFactory.loadConfigured(JDBCEnvironment env)`. If the keys are unset it returns `PropertyParameterKeyProvider.DISABLED`; otherwise it finds the factory named by the provider property through `ServiceLoader`. An unknown name gives a `GuacamoleServerException` listing the available names.

- [ ] **Step 1: Write failing tests**

`KeyringTest.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.Map;
import org.apache.guacamole.GuacamoleServerException;
import org.junit.jupiter.api.Test;

public class KeyringTest {

    private static String b64(int len) {
        return Base64.getEncoder().encodeToString(new byte[len]);
    }

    @Test
    public void parsesTwoKeys() throws Exception {
        Map<String, byte[]> keys = Keyring.parseBase64("k1:" + b64(32) + ", k2:" + b64(32));
        assertEquals(2, keys.size());
        assertEquals(32, keys.get("k2").length);
    }

    @Test
    public void rejectsWrongLength() {
        GuacamoleServerException e = assertThrows(GuacamoleServerException.class,
            () -> Keyring.parseBase64("k1:" + b64(16)));
        assertTrue(e.getMessage().contains("k1"));
        assertFalse(e.getMessage().contains(b64(16)), "key material must not appear in errors");
    }

    @Test
    public void rejectsBadBase64() {
        assertThrows(GuacamoleServerException.class, () -> Keyring.parseBase64("k1:!!!"));
    }

    @Test
    public void rejectsMissingSeparatorDuplicateAndBadId() {
        assertThrows(GuacamoleServerException.class, () -> Keyring.parseBase64(b64(32)));
        assertThrows(GuacamoleServerException.class,
            () -> Keyring.parseBase64("k1:" + b64(32) + ",k1:" + b64(32)));
        assertThrows(GuacamoleServerException.class,
            () -> Keyring.parseBase64("bad id:" + b64(32)));
        String longId = new String(new char[65]).replace('\0', 'a');
        assertThrows(GuacamoleServerException.class,
            () -> Keyring.parseBase64(longId + ":" + b64(32)));
    }

}
```

`PropertyParameterKeyProviderTest.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.Map;
import org.apache.guacamole.GuacamoleServerException;
import org.junit.jupiter.api.Test;

public class PropertyParameterKeyProviderTest {

    @Test
    public void disabledWhenNoKeys() {
        assertFalse(PropertyParameterKeyProvider.DISABLED.isEnabled());
        assertNull(PropertyParameterKeyProvider.DISABLED.keyFor("k1"));
    }

    @Test
    public void currentKeyMustExist() {
        Map<String, byte[]> keys = Collections.singletonMap("k1", new byte[32]);
        assertThrows(GuacamoleServerException.class,
            () -> new PropertyParameterKeyProvider(keys, "k2"));
        assertThrows(GuacamoleServerException.class,
            () -> new PropertyParameterKeyProvider(keys, null));
    }

    @Test
    public void servesKeys() throws Exception {
        Map<String, byte[]> keys = Collections.singletonMap("k1", new byte[32]);
        ParameterKeyProvider p = new PropertyParameterKeyProvider(keys, "k1");
        assertTrue(p.isEnabled());
        assertEquals("k1", p.currentKeyId());
        assertNotNull(p.keyFor("k1"));
        assertNull(p.keyFor("other"));
    }

}
```

- [ ] **Step 2: Run, verify failure**

Run: `cd extensions/guacamole-auth-jdbc && ~/.local/bin/mvn -q test -pl modules/guacamole-auth-jdbc-base -am -Dtest='KeyringTest,PropertyParameterKeyProviderTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (missing classes).

- [ ] **Step 3: Properties.** On `JDBCEnvironment`, add five abstract methods with javadoc in the style of `getPasswordHashIterations()`. On each `*GuacamoleProperties`, add `StringGuacamoleProperty` constants named `<DB>_PARAMETER_ENCRYPTION_KEYS`, `…_CURRENT_KEY`, `…_PROVIDER`, `…_MODE` and `…_EXTRA_NAMES`, with names `<db>-parameter-encryption-keys` and so on. For example, PostgreSQL:

```java
    /**
     * The keyring used to encrypt secret connection parameters, as
     * comma-separated "keyId:value" pairs. Unset disables encryption.
     */
    public static final StringGuacamoleProperty POSTGRESQL_PARAMETER_ENCRYPTION_KEYS =
            new StringGuacamoleProperty() {

        @Override
        public String getName() { return "postgresql-parameter-encryption-keys"; }

    };
```

On each `*Environment`:
```java
    @Override
    public String getParameterEncryptionKeys() throws GuacamoleException {
        return getProperty(PostgreSQLGuacamoleProperties.POSTGRESQL_PARAMETER_ENCRYPTION_KEYS);
    }

    @Override
    public String getParameterEncryptionProvider() throws GuacamoleException {
        return getProperty(PostgreSQLGuacamoleProperties.POSTGRESQL_PARAMETER_ENCRYPTION_PROVIDER, "property");
    }

    @Override
    public String getParameterEncryptionMode() throws GuacamoleException {
        return getProperty(PostgreSQLGuacamoleProperties.POSTGRESQL_PARAMETER_ENCRYPTION_MODE, "encrypt");
    }
```
`getParameterEncryptionCurrentKey` and `getParameterEncryptionExtraNames` have no default. Repeat for MySQL and SQL Server with their prefixes.

- [ ] **Step 4: Implement the SPI, keyring and provider**

`ParameterKeyProvider.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

/**
 * Source of the keys used to encrypt secret parameter values.
 */
public interface ParameterKeyProvider {

    /**
     * Returns whether a keyring is configured. When false, no value is
     * encrypted and encrypted values cannot be read.
     */
    boolean isEnabled();

    /**
     * Returns the identifier of the key used for new encryption. Valid only
     * if isEnabled() returns true.
     */
    String currentKeyId();

    /**
     * Returns the 32-byte key having the given identifier, or null if no such
     * key is configured.
     */
    byte[] keyFor(String keyId);

}
```

`ParameterKeyProviderFactory.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;

/**
 * Creates a ParameterKeyProvider. Implementations are discovered with
 * ServiceLoader and selected by the "*-parameter-encryption-provider"
 * property.
 */
public interface ParameterKeyProviderFactory {

    /** Returns the name selecting this factory, such as "property". */
    String getName();

    /** Creates the provider from the given environment. */
    ParameterKeyProvider create(JDBCEnvironment environment)
            throws GuacamoleException;

    /**
     * Returns the configured provider, or PropertyParameterKeyProvider.DISABLED
     * if no keys are configured.
     */
    static ParameterKeyProvider loadConfigured(JDBCEnvironment environment)
            throws GuacamoleException {

        if (environment.getParameterEncryptionKeys() == null)
            return PropertyParameterKeyProvider.DISABLED;

        String name = environment.getParameterEncryptionProvider();
        List<String> available = new ArrayList<>();
        for (ParameterKeyProviderFactory factory : ServiceLoader.load(
                ParameterKeyProviderFactory.class,
                ParameterKeyProviderFactory.class.getClassLoader())) {
            if (factory.getName().equals(name))
                return factory.create(environment);
            available.add(factory.getName());
        }

        throw new GuacamoleServerException("Unknown parameter encryption "
                + "provider \"" + name + "\". Available: " + available);

    }

}
```

`Keyring.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.guacamole.GuacamoleServerException;

/**
 * Parses keyring properties of the form "kid:value[,kid:value...]".
 * Error messages name key identifiers only, never key material.
 */
public final class Keyring {

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Turns one property value into raw key bytes. */
    public interface Decoder {
        byte[] decode(String keyId, String value) throws GuacamoleServerException;
    }

    private Keyring() {}

    public static Map<String, byte[]> parse(String spec, Decoder decoder)
            throws GuacamoleServerException {

        Map<String, byte[]> keys = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {

            String trimmed = entry.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0)
                throw new GuacamoleServerException("Parameter encryption "
                        + "keyring entries must be \"keyId:value\".");

            String keyId = trimmed.substring(0, colon);
            if (!KEY_ID.matcher(keyId).matches())
                throw new GuacamoleServerException("Invalid parameter "
                        + "encryption key identifier \"" + keyId + "\".");
            if (keys.containsKey(keyId))
                throw new GuacamoleServerException("Duplicate parameter "
                        + "encryption key identifier \"" + keyId + "\".");

            byte[] key = decoder.decode(keyId, trimmed.substring(colon + 1).trim());
            if (key.length != ParameterCipher.KEY_BYTES)
                throw new GuacamoleServerException("Parameter encryption key \""
                        + keyId + "\" must be " + ParameterCipher.KEY_BYTES
                        + " bytes.");
            keys.put(keyId, key);

        }
        return Collections.unmodifiableMap(keys);

    }

    public static Map<String, byte[]> parseBase64(String spec)
            throws GuacamoleServerException {
        return parse(spec, (keyId, value) -> {
            try {
                return Base64.getDecoder().decode(value);
            }
            catch (IllegalArgumentException e) {
                throw new GuacamoleServerException("Parameter encryption key \""
                        + keyId + "\" is not valid base64.");
            }
        });
    }

}
```

`PropertyParameterKeyProvider.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.util.Collections;
import java.util.Map;
import org.apache.guacamole.GuacamoleServerException;

/**
 * Key provider whose keys are given directly in a property.
 */
public class PropertyParameterKeyProvider implements ParameterKeyProvider {

    /** Provider used when no keyring is configured. */
    public static final ParameterKeyProvider DISABLED = new ParameterKeyProvider() {
        @Override public boolean isEnabled() { return false; }
        @Override public String currentKeyId() {
            throw new IllegalStateException("Parameter encryption is disabled.");
        }
        @Override public byte[] keyFor(String keyId) { return null; }
    };

    private final Map<String, byte[]> keys;
    private final String currentKeyId;

    public PropertyParameterKeyProvider(Map<String, byte[]> keys,
            String currentKeyId) throws GuacamoleServerException {
        if (currentKeyId == null || !keys.containsKey(currentKeyId))
            throw new GuacamoleServerException("The current parameter "
                    + "encryption key \"" + currentKeyId + "\" is not in the "
                    + "keyring.");
        this.keys = Collections.unmodifiableMap(keys);
        this.currentKeyId = currentKeyId;
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String currentKeyId() { return currentKeyId; }
    @Override public byte[] keyFor(String keyId) { return keys.get(keyId); }

}
```

`PropertyParameterKeyProviderFactory.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;

/**
 * Factory for PropertyParameterKeyProvider, selected by the name "property".
 */
public class PropertyParameterKeyProviderFactory implements ParameterKeyProviderFactory {

    @Override
    public String getName() {
        return "property";
    }

    @Override
    public ParameterKeyProvider create(JDBCEnvironment environment)
            throws GuacamoleException {
        return new PropertyParameterKeyProvider(
                Keyring.parseBase64(environment.getParameterEncryptionKeys()),
                environment.getParameterEncryptionCurrentKey());
    }

}
```

The `META-INF/services/org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProviderFactory` file has the ASF header as `#` comments, then this line:
```
org.apache.guacamole.auth.jdbc.encryption.PropertyParameterKeyProviderFactory
```

- [ ] **Step 5: Run, verify pass.** Run the same command as Step 2. Expected: `Tests run: 7, Failures: 0`. Then build all three database modules (the Task 2 Step 6 command) to confirm every environment implements the new abstract methods.

- [ ] **Step 6: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-558: Add parameter encryption properties and a pluggable key provider." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `SecretParameterPolicy`

**Files:**
- Create: `…/jdbc/encryption/SecretParameterPolicy.java`
- Test: `…/encryption/SecretParameterPolicyTest.java`

**Interfaces:**
- Consumes: `org.apache.guacamole.protocols.ProtocolInfo` (`getConnectionForms()`, `getSharingProfileForms()`), `Form.getFields()`, `Field.getType()`, `Field.Type.PASSWORD`.
- Produces: `SecretParameterPolicy(Collection<ProtocolInfo> protocols, String extraNames)`:
  - `boolean isSecret(String name)`
  - `Set<String> getSecretNames()` (unmodifiable, used by the sweep)
  - `static final List<String> BUILT_IN = Arrays.asList("private-key", "sftp-private-key", "client-key")`

- [ ] **Step 1: Write the failing test.** It loads the protocol descriptors bundled in `guacamole-ext`, which is on the test classpath as a provided dependency.

```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.guacamole.form.Field;
import org.apache.guacamole.form.Form;
import org.apache.guacamole.protocols.ProtocolInfo;
import org.junit.jupiter.api.Test;

public class SecretParameterPolicyTest {

    private static final String[] PROTOCOLS = { "kubernetes", "rdp", "ssh", "telnet", "vnc" };

    static Collection<ProtocolInfo> bundled() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ProtocolInfo> list = new ArrayList<>();
        for (String name : PROTOCOLS) {
            try (InputStream in = ProtocolInfo.class.getResourceAsStream(
                    "/org/apache/guacamole/protocols/" + name + ".json")) {
                assertNotNull(in, name);
                list.add(mapper.readValue(in, ProtocolInfo.class));
            }
        }
        return list;
    }

    @Test
    public void everyPasswordFieldIsSecret() throws Exception {
        Collection<ProtocolInfo> protocols = bundled();
        SecretParameterPolicy policy = new SecretParameterPolicy(protocols, null);
        int checked = 0;
        for (ProtocolInfo p : protocols)
            for (Form f : p.getConnectionForms())
                for (Field field : f.getFields())
                    if (Field.Type.PASSWORD.equals(field.getType())) {
                        assertTrue(policy.isSecret(field.getName()), field.getName());
                        checked++;
                    }
        assertTrue(checked >= 5, "expected the bundled PASSWORD fields");
    }

    @Test
    public void builtInsAndExtras() throws Exception {
        SecretParameterPolicy policy = new SecretParameterPolicy(bundled(), " totp-seed , api-token ");
        for (String n : SecretParameterPolicy.BUILT_IN)
            assertTrue(policy.isSecret(n), n);
        assertTrue(policy.isSecret("totp-seed"));
        assertTrue(policy.isSecret("api-token"));
    }

    @Test
    public void ordinaryFieldsAreNot() throws Exception {
        SecretParameterPolicy policy = new SecretParameterPolicy(bundled(), null);
        for (String n : new String[] { "hostname", "port", "username", "domain", "host-key", "public-key", "" })
            assertFalse(policy.isSecret(n), n);
    }

}
```

- [ ] **Step 2: Run, verify failure.** Run: `… -Dtest=SecretParameterPolicyTest …` (the same form as Task 4). Expected: compilation failure.

  If Jackson cannot map `ProtocolInfo`, check how `LocalEnvironment` reads `protocols/*.json` (`guacamole-ext/src/main/java/org/apache/guacamole/environment/LocalEnvironment.java`, around `readProtocol`) and use the same reader.

- [ ] **Step 3: Implement**

```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.guacamole.form.Field;
import org.apache.guacamole.form.Form;
import org.apache.guacamole.protocols.ProtocolInfo;

/**
 * Decides which parameter names hold secrets. Independent of protocol: a
 * parameter name means the same thing in every protocol.
 */
public class SecretParameterPolicy {

    /** Secret parameters that protocol descriptors do not type as PASSWORD. */
    public static final List<String> BUILT_IN = Collections.unmodifiableList(
            Arrays.asList("private-key", "sftp-private-key", "client-key"));

    private final Set<String> names;

    public SecretParameterPolicy(Collection<ProtocolInfo> protocols,
            String extraNames) {

        Set<String> secret = new HashSet<>(BUILT_IN);
        for (ProtocolInfo protocol : protocols) {
            addPasswordFields(secret, protocol.getConnectionForms());
            addPasswordFields(secret, protocol.getSharingProfileForms());
        }

        if (extraNames != null)
            for (String name : extraNames.split(","))
                if (!name.trim().isEmpty())
                    secret.add(name.trim());

        this.names = Collections.unmodifiableSet(secret);

    }

    private static void addPasswordFields(Set<String> secret, Collection<Form> forms) {
        if (forms == null)
            return;
        for (Form form : forms)
            for (Field field : form.getFields())
                if (Field.Type.PASSWORD.equals(field.getType()))
                    secret.add(field.getName());
    }

    /** Returns whether the parameter with the given name holds a secret. */
    public boolean isSecret(String name) {
        return names.contains(name);
    }

    /** Returns every secret parameter name. */
    public Set<String> getSecretNames() {
        return names;
    }

}
```

- [ ] **Step 4: Run, verify pass.** Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base
git commit -m "GUACAMOLE-558: Derive secret parameter names from protocol descriptors." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `ParameterEncryptionService`

**Files:**
- Create: `…/jdbc/encryption/ParameterEncryptionService.java`
- Test: `…/encryption/ParameterEncryptionServiceTest.java`

**Interfaces:**
- Consumes: `ParameterKeyProvider`, `SecretParameterPolicy`, `ParameterCipher`, `ParameterOwner`, `ConnectionParameterModel`, `SharingProfileParameterModel` (with `get/setEncryption`).
- Produces:
  - Constructor `ParameterEncryptionService(ParameterKeyProvider keys, SecretParameterPolicy policy, boolean decryptMode)`. Guice-constructed via the `@Provides` method in Task 7.
  - `void protectConnectionParameters(Collection<ConnectionParameterModel> models) throws GuacamoleException`
  - `void protectSharingProfileParameters(Collection<SharingProfileParameterModel> models) throws GuacamoleException`
  - `String reveal(ParameterOwner owner, String ownerId, String name, String value, String keyId) throws GuacamoleServerException`
  - `String revealOrOmit(ParameterOwner owner, String ownerId, String name, String value, String keyId)`. Returns null on failure and logs an ERROR.
  - `boolean isEnabled()`, `boolean isDecryptMode()`, `String currentKeyId()`
  - `String encryptValue(ParameterOwner owner, String ownerId, String name, String plaintext) throws GuacamoleClientException`, used by the sweep. Only valid when enabled.
  - `SecretParameterPolicy getPolicy()`, used by the sweep for the candidate names.

**Behaviour:**
- `protect…`:
  - Sets `encryption` to null on every model first.
  - If enabled, not in decrypt mode, the name is secret and the value is non-empty: encrypt with the current key and set `encryption` to the current key ID.
  - If the value is over the limit, throw `GuacamoleClientException("Parameter \"<name>\" is too long to be stored encrypted (limit 6000 bytes).")`.
- `reveal`:
  - `keyId == null` returns `value` unchanged.
  - An unknown key ID, or `GeneralSecurityException`, throws `GuacamoleServerException("Cannot decrypt parameter \"<name>\" of <owner type> <id> (key \"<kid>\").")`.

- [ ] **Step 1: Write failing tests**

```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionParameterModel;
import org.junit.jupiter.api.Test;

public class ParameterEncryptionServiceTest {

    static final byte[] K1 = new byte[32];
    static final byte[] K2 = new byte[32];
    static { K2[0] = 1; }

    static ParameterKeyProvider provider(String current, String... kids) throws Exception {
        Map<String, byte[]> keys = new HashMap<>();
        for (String kid : kids)
            keys.put(kid, kid.equals("k1") ? K1 : K2);
        return new PropertyParameterKeyProvider(keys, current);
    }

    static SecretParameterPolicy policy() {
        return new SecretParameterPolicy(Collections.emptyList(), "password");
    }

    static ConnectionParameterModel param(String id, String name, String value) {
        ConnectionParameterModel m = new ConnectionParameterModel();
        m.setConnectionIdentifier(id);
        m.setName(name);
        m.setValue(value);
        return m;
    }

    @Test
    public void encryptsSecretsOnly() throws Exception {
        ParameterEncryptionService svc = new ParameterEncryptionService(provider("k1", "k1"), policy(), false);
        ConnectionParameterModel host = param("7", "hostname", "10.0.0.1");
        ConnectionParameterModel pw = param("7", "password", "s3cret");
        svc.protectConnectionParameters(Arrays.asList(host, pw));
        assertNull(host.getEncryption());
        assertEquals("10.0.0.1", host.getValue());
        assertEquals("k1", pw.getEncryption());
        assertNotEquals("s3cret", pw.getValue());
        assertEquals("s3cret", svc.reveal(ParameterOwner.CONNECTION, "7", "password", pw.getValue(), "k1"));
    }

    @Test
    public void disabledLeavesPlainText() throws Exception {
        ParameterEncryptionService svc = new ParameterEncryptionService(PropertyParameterKeyProvider.DISABLED, policy(), false);
        ConnectionParameterModel pw = param("7", "password", "s3cret");
        svc.protectConnectionParameters(Collections.singletonList(pw));
        assertNull(pw.getEncryption());
        assertEquals("s3cret", pw.getValue());
    }

    @Test
    public void decryptModeWritesPlainText() throws Exception {
        ParameterEncryptionService svc = new ParameterEncryptionService(provider("k1", "k1"), policy(), true);
        ConnectionParameterModel pw = param("7", "password", "s3cret");
        svc.protectConnectionParameters(Collections.singletonList(pw));
        assertNull(pw.getEncryption());
        assertEquals("s3cret", pw.getValue());
    }

    @Test
    public void nullKeyIdIsPlainTextEvenIfBase64() throws Exception {
        ParameterEncryptionService svc = new ParameterEncryptionService(provider("k1", "k1"), policy(), false);
        assertEquals("QUJDREVGR0g=", svc.reveal(ParameterOwner.CONNECTION, "7", "password", "QUJDREVGR0g=", null));
    }

    @Test
    public void emptyValueIsNotEncrypted() throws Exception {
        ParameterEncryptionService svc = new ParameterEncryptionService(provider("k1", "k1"), policy(), false);
        ConnectionParameterModel pw = param("7", "password", "");
        svc.protectConnectionParameters(Collections.singletonList(pw));
        assertNull(pw.getEncryption());
        assertEquals("", pw.getValue());
    }

    @Test
    public void rejectsOversizedSecret() throws Exception {
        ParameterEncryptionService svc = new ParameterEncryptionService(provider("k1", "k1"), policy(), false);
        char[] big = new char[6001];
        Arrays.fill(big, 'a');
        GuacamoleClientException e = assertThrows(GuacamoleClientException.class,
            () -> svc.protectConnectionParameters(Collections.singletonList(param("7", "password", new String(big)))));
        assertTrue(e.getMessage().contains("password"));
    }

    @Test
    public void unknownKeyFailsWithoutLeaking() throws Exception {
        ParameterEncryptionService old = new ParameterEncryptionService(provider("k2", "k2"), policy(), false);
        ConnectionParameterModel pw = param("7", "password", "s3cret");
        old.protectConnectionParameters(Collections.singletonList(pw));
        ParameterEncryptionService svc = new ParameterEncryptionService(provider("k1", "k1"), policy(), false);
        GuacamoleServerException e = assertThrows(GuacamoleServerException.class,
            () -> svc.reveal(ParameterOwner.CONNECTION, "7", "password", pw.getValue(), "k2"));
        assertTrue(e.getMessage().contains("k2") && e.getMessage().contains("7") && e.getMessage().contains("password"));
        assertFalse(e.getMessage().contains(pw.getValue()));
        assertNull(svc.revealOrOmit(ParameterOwner.CONNECTION, "7", "password", pw.getValue(), "k2"));
    }

    @Test
    public void rotationReadsOldWritesNew() throws Exception {
        ParameterEncryptionService before = new ParameterEncryptionService(provider("k1", "k1"), policy(), false);
        ConnectionParameterModel pw = param("7", "password", "s3cret");
        before.protectConnectionParameters(Collections.singletonList(pw));
        ParameterEncryptionService after = new ParameterEncryptionService(provider("k2", "k1", "k2"), policy(), false);
        assertEquals("s3cret", after.reveal(ParameterOwner.CONNECTION, "7", "password", pw.getValue(), "k1"));
        ConnectionParameterModel again = param("7", "password", "s3cret");
        after.protectConnectionParameters(Collections.singletonList(again));
        assertEquals("k2", again.getEncryption());
    }

}
```

- [ ] **Step 2: Run, verify failure.** `-Dtest=ParameterEncryptionServiceTest`. Expected: compilation failure.

- [ ] **Step 3: Implement**

```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.security.GeneralSecurityException;
import java.util.Collection;
import org.apache.guacamole.GuacamoleClientException;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionParameterModel;
import org.apache.guacamole.auth.jdbc.sharingprofile.SharingProfileParameterModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypts secret parameter values before they are stored and decrypts them
 * after they are read. Never logs or reports values or key material.
 */
public class ParameterEncryptionService {

    private static final Logger logger = LoggerFactory.getLogger(ParameterEncryptionService.class);

    private final ParameterKeyProvider keys;
    private final SecretParameterPolicy policy;
    private final boolean decryptMode;

    public ParameterEncryptionService(ParameterKeyProvider keys,
            SecretParameterPolicy policy, boolean decryptMode) {
        this.keys = keys;
        this.policy = policy;
        this.decryptMode = decryptMode;
    }

    public boolean isEnabled() { return keys.isEnabled(); }
    public boolean isDecryptMode() { return decryptMode; }
    public String currentKeyId() { return keys.currentKeyId(); }
    public SecretParameterPolicy getPolicy() { return policy; }

    private boolean shouldEncrypt(String name, String value) {
        return keys.isEnabled() && !decryptMode && value != null
                && !value.isEmpty() && policy.isSecret(name);
    }

    /** Encrypts with the current key. Valid only when enabled. */
    public String encryptValue(ParameterOwner owner, String ownerId,
            String name, String plaintext) throws GuacamoleClientException {
        try {
            return ParameterCipher.encrypt(keys.keyFor(keys.currentKeyId()),
                    owner, ownerId, name, plaintext);
        }
        catch (IllegalArgumentException e) {
            throw new GuacamoleClientException("Parameter \"" + name + "\" is "
                    + "too long to be stored encrypted (limit "
                    + ParameterCipher.MAX_PLAINTEXT_BYTES + " bytes).");
        }
    }

    public void protectConnectionParameters(
            Collection<ConnectionParameterModel> models) throws GuacamoleException {
        for (ConnectionParameterModel model : models) {
            model.setEncryption(null);
            if (shouldEncrypt(model.getName(), model.getValue())) {
                model.setValue(encryptValue(ParameterOwner.CONNECTION,
                        model.getConnectionIdentifier(), model.getName(), model.getValue()));
                model.setEncryption(keys.currentKeyId());
            }
        }
    }

    public void protectSharingProfileParameters(
            Collection<SharingProfileParameterModel> models) throws GuacamoleException {
        for (SharingProfileParameterModel model : models) {
            model.setEncryption(null);
            if (shouldEncrypt(model.getName(), model.getValue())) {
                model.setValue(encryptValue(ParameterOwner.SHARING_PROFILE,
                        model.getSharingProfileIdentifier(), model.getName(), model.getValue()));
                model.setEncryption(keys.currentKeyId());
            }
        }
    }

    /**
     * Returns the plain value of a stored parameter.
     *
     * @throws GuacamoleServerException
     *     If the value is encrypted and cannot be decrypted.
     */
    public String reveal(ParameterOwner owner, String ownerId, String name,
            String value, String keyId) throws GuacamoleServerException {

        if (keyId == null)
            return value;

        String failure = "Cannot decrypt parameter \"" + name + "\" of "
                + owner.getType() + " " + ownerId + " (key \"" + keyId + "\").";

        byte[] key = keys.keyFor(keyId);
        if (key == null)
            throw new GuacamoleServerException(failure + " The key is not configured.");

        try {
            return ParameterCipher.decrypt(key, owner, ownerId, name, value);
        }
        catch (GeneralSecurityException e) {
            throw new GuacamoleServerException(failure + " The value does not "
                    + "match the key, owner or parameter name.");
        }

    }

    /**
     * Like reveal(), but returns null and logs an ERROR on failure. For read
     * paths that cannot throw.
     */
    public String revealOrOmit(ParameterOwner owner, String ownerId,
            String name, String value, String keyId) {
        try {
            return reveal(owner, ownerId, name, value, keyId);
        }
        catch (GuacamoleServerException e) {
            logger.error("{} The parameter is omitted; saving this {} now "
                    + "would store it without that value.", e.getMessage(), owner.getType());
            return null;
        }
    }

}
```

- [ ] **Step 4: Run, verify pass.** Expected: `Tests run: 8, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base
git commit -m "GUACAMOLE-558: Add service encrypting secret parameters on write and decrypting on read." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Wire into the services, the tunnel and Guice

**Files:**
- Modify: `…/jdbc/JDBCAuthenticationProviderModule.java`
- Modify: `…/jdbc/connection/ConnectionService.java` (create and update inserts around :305-327; `retrieveParameters` around :405)
- Modify: `…/jdbc/sharingprofile/SharingProfileService.java` (around :230-252 and :291)
- Modify: `…/jdbc/tunnel/AbstractGuacamoleTunnelService.java` (`getGuacamoleConfiguration` :335-360)
- Test: `…/encryption/TunnelDecryptFailureTest.java`

**Interfaces:**
- Consumes: `ParameterEncryptionService` (Task 6), `ParameterKeyProviderFactory.loadConfigured` (Task 4), `SecretParameterPolicy` (Task 5), `JDBCEnvironment.getParameterEncryptionMode/ExtraNames`.
- Produces: a Guice singleton `ParameterEncryptionService`, available for `@Inject` in Task 8.

- [ ] **Step 1: Guice provider.** In `JDBCAuthenticationProviderModule`, add a `@Provides @Singleton` method. Use `com.google.inject.Provides` and `com.google.inject.Singleton`, and check which `Singleton` import the module already uses.

```java
    /**
     * Creates the service encrypting secret connection parameters, failing
     * fast on any invalid encryption configuration.
     */
    @Provides
    @Singleton
    ParameterEncryptionService provideParameterEncryptionService(
            JDBCEnvironment environment) throws GuacamoleException {

        String mode = environment.getParameterEncryptionMode();
        if (!"encrypt".equals(mode) && !"decrypt".equals(mode))
            throw new GuacamoleServerException("Parameter encryption mode must "
                    + "be \"encrypt\" or \"decrypt\", not \"" + mode + "\".");

        ParameterKeyProvider keys = ParameterKeyProviderFactory.loadConfigured(environment);
        SecretParameterPolicy policy = new SecretParameterPolicy(
                environment.getProtocols().values(),
                environment.getParameterEncryptionExtraNames());

        return new ParameterEncryptionService(keys, policy, "decrypt".equals(mode));

    }
```

  Guice wraps exceptions thrown from `@Provides` methods. Confirm that a bad keyring stops the extension from loading with the message visible in the log. Task 10 checks this end to end.

- [ ] **Step 2: ConnectionService.** Add `@Inject private ParameterEncryptionService parameterEncryptionService;`.
  - In `createObject` and `updateObject`, call this right after `getParameterModels(...)` and before any `insert`:
    ```java
    parameterEncryptionService.protectConnectionParameters(parameterModels);
    ```
  - In `retrieveParameters`, replace the loop body with:
    ```java
            for (ConnectionParameterModel parameter : parameterMapper.select(identifier)) {
                String value = parameterEncryptionService.revealOrOmit(
                        ParameterOwner.CONNECTION, identifier, parameter.getName(),
                        parameter.getValue(), parameter.getEncryption());
                if (value != null)
                    parameterMap.put(parameter.getName(), value);
            }
    ```

- [ ] **Step 3: SharingProfileService.** Make the same three edits using `protectSharingProfileParameters` and `ParameterOwner.SHARING_PROFILE`.

- [ ] **Step 4: Tunnel.** Inject `ParameterEncryptionService`. Change `getGuacamoleConfiguration` to `throws GuacamoleException`, and the loops to:
```java
        if (sharingProfile != null) {
            Collection<SharingProfileParameterModel> parameters = sharingProfileParameterMapper.select(sharingProfile.getIdentifier());
            for (SharingProfileParameterModel parameter : parameters)
                config.setParameter(parameter.getName(), parameterEncryptionService.reveal(
                        ParameterOwner.SHARING_PROFILE, sharingProfile.getIdentifier(),
                        parameter.getName(), parameter.getValue(), parameter.getEncryption()));
        }
        else {
            Collection<ConnectionParameterModel> parameters = connectionParameterMapper.select(connection.getIdentifier());
            for (ConnectionParameterModel parameter : parameters)
                config.setParameter(parameter.getName(), parameterEncryptionService.reveal(
                        ParameterOwner.CONNECTION, connection.getIdentifier(),
                        parameter.getName(), parameter.getValue(), parameter.getEncryption()));
        }
```
  Both callers (:618, :632) are inside `assignGuacamoleTunnel`. Confirm that it declares `throws GuacamoleException`; if not, propagate the declaration up to the first method that does. Keep the existing `try` around the calls so that a failure still releases the reserved seat. Read the surrounding `catch` before editing.

- [ ] **Step 5: Test the session-path guard.** `getGuacamoleConfiguration` is private and needs the full service graph, so test the guard it delegates to. Assert that `reveal` refuses, rather than returning ciphertext, for each failure kind:

```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.connection.ConnectionParameterModel;
import org.junit.jupiter.api.Test;

public class TunnelDecryptFailureTest {

    @Test
    public void tunnelGuardNeverReturnsCiphertext() throws Exception {
        ParameterEncryptionService writer = new ParameterEncryptionService(
            ParameterEncryptionServiceTest.provider("k1", "k1"), ParameterEncryptionServiceTest.policy(), false);
        ConnectionParameterModel pw = ParameterEncryptionServiceTest.param("7", "password", "s3cret");
        writer.protectConnectionParameters(Collections.singletonList(pw));

        // key removed, feature disabled
        ParameterEncryptionService disabled = new ParameterEncryptionService(
            PropertyParameterKeyProvider.DISABLED, ParameterEncryptionServiceTest.policy(), false);
        assertThrows(GuacamoleServerException.class, () -> disabled.reveal(
            ParameterOwner.CONNECTION, "7", "password", pw.getValue(), pw.getEncryption()));

        // row moved to another connection
        assertThrows(GuacamoleServerException.class, () -> writer.reveal(
            ParameterOwner.CONNECTION, "8", "password", pw.getValue(), pw.getEncryption()));
    }

}
```

- [ ] **Step 6: Run the module's tests.** Run: `cd extensions/guacamole-auth-jdbc && ~/.local/bin/mvn -q verify -pl modules/guacamole-auth-jdbc-postgresql,modules/guacamole-auth-jdbc-mysql,modules/guacamole-auth-jdbc-sqlserver -am`. Expected: BUILD SUCCESS, and all existing tests still pass, including the reset and PBKDF2 suites.

- [ ] **Step 7: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-base
git commit -m "GUACAMOLE-558: Encrypt secret parameters on save and decrypt them on every read path." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Sweep — mapper, service and starter

**Files:**
- Create: `…/jdbc/encryption/ParameterRow.java`, `ParameterEncryptionMapper.java`, `ParameterEncryptionSweep.java`, `SweepStarter.java`
- Create: `…/guacamole-auth-jdbc-{postgresql,mysql,sqlserver}/src/main/resources/org/apache/guacamole/auth/jdbc/encryption/ParameterEncryptionMapper.xml` (×3)
- Modify: `JDBCAuthenticationProviderModule.java`: `addMapperClass(ParameterEncryptionMapper.class);` and `bind(SweepStarter.class).asEagerSingleton();`
- Test: `…/encryption/InMemoryParameterEncryptionMapper.java`, `…/encryption/ParameterEncryptionSweepTest.java`

**Interfaces:**
- Consumes: `ParameterEncryptionService` (`isEnabled`, `isDecryptMode`, `currentKeyId`, `getPolicy`, `reveal`, `encryptValue`).
- Produces:
  - `class ParameterRow`: `String ownerIdentifier, name, value, encryption`, with getters and setters.
  - `interface ParameterEncryptionMapper`:
    - `List<ParameterRow> selectConnectionCandidates(@Param("names") Collection<String> names, @Param("currentKeyId") String currentKeyId, @Param("decrypt") boolean decrypt)`
    - `List<ParameterRow> selectSharingProfileCandidates(…)` with the same parameters
    - `int updateConnectionParameter(@Param("row") ParameterRow row, @Param("newValue") String newValue, @Param("newEncryption") String newEncryption)`
    - `int updateSharingProfileParameter(…)` with the same parameters
  - `class ParameterEncryptionSweep`:
    - `@Inject ParameterEncryptionSweep(ParameterEncryptionService, ParameterEncryptionMapper)`
    - `SweepResult run()`
    - `static final int BATCH_SIZE = 100`
  - `SweepResult` (nested static class) with `int encrypted, rekeyed, decrypted, skipped, failed, remaining`, and `toString()` producing `encrypted N, re-keyed M, decrypted D, skipped K, failed F, remaining plain-text secrets R`.

**Candidate semantics:**
- `decrypt=false`: `parameter_name IN (names) AND (parameter_encryption IS NULL OR parameter_encryption <> currentKeyId)`.
- `decrypt=true`: `parameter_encryption IS NOT NULL`.
- The update always matches on primary key, old value and old encryption (null-safe), so it is a compare-and-set.

- [ ] **Step 1: Write the fake mapper and failing tests**

`InMemoryParameterEncryptionMapper.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Single "connection" table; sharing profiles are always empty. */
class InMemoryParameterEncryptionMapper implements ParameterEncryptionMapper {

    final List<ParameterRow> rows = new ArrayList<>();

    /** Hook run before each update, to simulate a concurrent writer. */
    Runnable beforeUpdate = () -> {};

    static ParameterRow row(String id, String name, String value, String kid) {
        ParameterRow r = new ParameterRow();
        r.setOwnerIdentifier(id); r.setName(name); r.setValue(value); r.setEncryption(kid);
        return r;
    }

    private static ParameterRow copy(ParameterRow r) {
        return row(r.getOwnerIdentifier(), r.getName(), r.getValue(), r.getEncryption());
    }

    @Override
    public synchronized List<ParameterRow> selectConnectionCandidates(Collection<String> names,
            String currentKeyId, boolean decrypt) {
        List<ParameterRow> out = new ArrayList<>();
        for (ParameterRow r : rows) {
            boolean match = decrypt ? r.getEncryption() != null
                    : names.contains(r.getName()) && !Objects.equals(r.getEncryption(), currentKeyId);
            if (match) out.add(copy(r));
        }
        return out;
    }

    @Override
    public List<ParameterRow> selectSharingProfileCandidates(Collection<String> names,
            String currentKeyId, boolean decrypt) {
        return new ArrayList<>();
    }

    @Override
    public int updateConnectionParameter(ParameterRow old, String newValue, String newEncryption) {
        beforeUpdate.run();
        synchronized (this) {
            for (ParameterRow r : rows)
                if (r.getOwnerIdentifier().equals(old.getOwnerIdentifier())
                        && r.getName().equals(old.getName())
                        && r.getValue().equals(old.getValue())
                        && Objects.equals(r.getEncryption(), old.getEncryption())) {
                    r.setValue(newValue); r.setEncryption(newEncryption);
                    return 1;
                }
            return 0;
        }
    }

    @Override
    public int updateSharingProfileParameter(ParameterRow old, String newValue, String newEncryption) {
        return 0;
    }

}
```

`ParameterEncryptionSweepTest.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

public class ParameterEncryptionSweepTest {

    static ParameterEncryptionService svc(String current, boolean decrypt, String... kids) throws Exception {
        return new ParameterEncryptionService(
            ParameterEncryptionServiceTest.provider(current, kids),
            ParameterEncryptionServiceTest.policy(), decrypt);
    }

    @Test
    public void encryptsPlainSecretsOnly() throws Exception {
        InMemoryParameterEncryptionMapper db = new InMemoryParameterEncryptionMapper();
        db.rows.add(InMemoryParameterEncryptionMapper.row("1", "password", "a", null));
        db.rows.add(InMemoryParameterEncryptionMapper.row("1", "hostname", "h", null));
        ParameterEncryptionService s = svc("k1", false, "k1");
        ParameterEncryptionSweep.SweepResult r = new ParameterEncryptionSweep(s, db).run();
        assertEquals(1, r.encrypted);
        assertEquals(0, r.remaining);
        assertEquals("k1", db.rows.get(0).getEncryption());
        assertEquals("a", s.reveal(ParameterOwner.CONNECTION, "1", "password", db.rows.get(0).getValue(), "k1"));
        assertNull(db.rows.get(1).getEncryption());
        assertEquals("h", db.rows.get(1).getValue());
    }

    @Test
    public void rekeysOnRotation() throws Exception {
        InMemoryParameterEncryptionMapper db = new InMemoryParameterEncryptionMapper();
        db.rows.add(InMemoryParameterEncryptionMapper.row("1", "password", "a", null));
        new ParameterEncryptionSweep(svc("k1", false, "k1"), db).run();
        ParameterEncryptionService rotated = svc("k2", false, "k1", "k2");
        ParameterEncryptionSweep.SweepResult r = new ParameterEncryptionSweep(rotated, db).run();
        assertEquals(1, r.rekeyed);
        assertEquals("k2", db.rows.get(0).getEncryption());
        assertEquals("a", rotated.reveal(ParameterOwner.CONNECTION, "1", "password", db.rows.get(0).getValue(), "k2"));
    }

    @Test
    public void decryptModeRestoresPlainText() throws Exception {
        InMemoryParameterEncryptionMapper db = new InMemoryParameterEncryptionMapper();
        db.rows.add(InMemoryParameterEncryptionMapper.row("1", "password", "a", null));
        new ParameterEncryptionSweep(svc("k1", false, "k1"), db).run();
        ParameterEncryptionSweep.SweepResult r = new ParameterEncryptionSweep(svc("k1", true, "k1"), db).run();
        assertEquals(1, r.decrypted);
        assertNull(db.rows.get(0).getEncryption());
        assertEquals("a", db.rows.get(0).getValue());
    }

    @Test
    public void undecryptableRowIsCountedAndSkipped() throws Exception {
        InMemoryParameterEncryptionMapper db = new InMemoryParameterEncryptionMapper();
        db.rows.add(InMemoryParameterEncryptionMapper.row("1", "password", "garbage", "gone"));
        db.rows.add(InMemoryParameterEncryptionMapper.row("2", "password", "b", null));
        ParameterEncryptionSweep.SweepResult r = new ParameterEncryptionSweep(svc("k1", false, "k1"), db).run();
        assertEquals(1, r.failed);
        assertEquals(1, r.encrypted);
        assertEquals("garbage", db.rows.get(0).getValue());
    }

    @Test
    public void concurrentSweepsEncryptEachRowOnce() throws Exception {
        InMemoryParameterEncryptionMapper db = new InMemoryParameterEncryptionMapper();
        for (int i = 0; i < 500; i++)
            db.rows.add(InMemoryParameterEncryptionMapper.row(Integer.toString(i), "password", "v" + i, null));
        ParameterEncryptionService s = svc("k1", false, "k1");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<ParameterEncryptionSweep.SweepResult> a = pool.submit(() -> { start.await(); return new ParameterEncryptionSweep(s, db).run(); });
        Future<ParameterEncryptionSweep.SweepResult> b = pool.submit(() -> { start.await(); return new ParameterEncryptionSweep(s, db).run(); });
        start.countDown();
        int total = a.get().encrypted + b.get().encrypted;
        pool.shutdown();
        assertEquals(500, total, "each row encrypted exactly once across both sweeps");
        for (int i = 0; i < 500; i++)
            assertEquals("v" + i, s.reveal(ParameterOwner.CONNECTION, Integer.toString(i), "password",
                db.rows.get(i).getValue(), "k1"));
    }

    @Test
    public void adminWriteDuringSweepWins() throws Exception {
        InMemoryParameterEncryptionMapper db = new InMemoryParameterEncryptionMapper();
        db.rows.add(InMemoryParameterEncryptionMapper.row("1", "password", "old", null));
        ParameterEncryptionService s = svc("k1", false, "k1");
        String adminValue = s.encryptValue(ParameterOwner.CONNECTION, "1", "password", "new");
        db.beforeUpdate = () -> { db.rows.get(0).setValue(adminValue); db.rows.get(0).setEncryption("k1"); };
        ParameterEncryptionSweep.SweepResult r = new ParameterEncryptionSweep(s, db).run();
        assertEquals(1, r.skipped);
        assertEquals("new", s.reveal(ParameterOwner.CONNECTION, "1", "password", db.rows.get(0).getValue(), "k1"));
    }

}
```

- [ ] **Step 2: Run, verify failure.** `-Dtest=ParameterEncryptionSweepTest`. Expected: compilation failure.

- [ ] **Step 3: Implement**

`ParameterRow.java`: a plain bean with the four `String` fields above, plus javadoc.

`ParameterEncryptionMapper.java`: the four methods listed in Interfaces, with `@Param` names `names`, `currentKeyId`, `decrypt`, `row`, `newValue` and `newEncryption`.

`ParameterEncryptionSweep.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import com.google.inject.Inject;
import java.util.List;
import org.apache.guacamole.GuacamoleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings stored parameters in line with the configured encryption: encrypts
 * plain-text secrets, re-keys rows on non-current keys, or (decrypt mode)
 * restores plain text. Each row is written with a compare-and-set, so
 * concurrent sweeps on several nodes, or an administrator saving mid-sweep,
 * are safe without a database lock.
 */
public class ParameterEncryptionSweep {

    private static final Logger logger = LoggerFactory.getLogger(ParameterEncryptionSweep.class);

    /** Rows handled between progress log lines. */
    public static final int BATCH_SIZE = 100;

    /** Counts produced by one sweep. */
    public static class SweepResult {
        public int encrypted, rekeyed, decrypted, skipped, failed, remaining;
        @Override
        public String toString() {
            return "encrypted " + encrypted + ", re-keyed " + rekeyed
                    + ", decrypted " + decrypted + ", skipped " + skipped
                    + ", failed " + failed + ", remaining plain-text secrets "
                    + remaining;
        }
    }

    private final ParameterEncryptionService service;
    private final ParameterEncryptionMapper mapper;

    @Inject
    public ParameterEncryptionSweep(ParameterEncryptionService service,
            ParameterEncryptionMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public SweepResult run() {
        SweepResult result = new SweepResult();
        if (!service.isEnabled())
            return result;
        String kid = service.currentKeyId();
        boolean decrypt = service.isDecryptMode();
        java.util.Set<String> names = service.getPolicy().getSecretNames();
        sweep(ParameterOwner.CONNECTION,
                mapper.selectConnectionCandidates(names, kid, decrypt), result);
        sweep(ParameterOwner.SHARING_PROFILE,
                mapper.selectSharingProfileCandidates(names, kid, decrypt), result);
        if (!decrypt)
            result.remaining = mapper.selectConnectionCandidates(names, kid, false).size()
                    + mapper.selectSharingProfileCandidates(names, kid, false).size()
                    - result.failed;
        return result;
    }

    private void sweep(ParameterOwner owner, List<ParameterRow> rows, SweepResult result) {
        int handled = 0;
        for (ParameterRow row : rows) {
            try {
                String plain = service.reveal(owner, row.getOwnerIdentifier(),
                        row.getName(), row.getValue(), row.getEncryption());
                String newValue;
                String newKid;
                if (service.isDecryptMode()) {
                    newValue = plain;
                    newKid = null;
                }
                else {
                    newValue = service.encryptValue(owner, row.getOwnerIdentifier(), row.getName(), plain);
                    newKid = service.currentKeyId();
                }
                int updated = owner == ParameterOwner.CONNECTION
                        ? mapper.updateConnectionParameter(row, newValue, newKid)
                        : mapper.updateSharingProfileParameter(row, newValue, newKid);
                if (updated == 0)
                    result.skipped++;
                else if (service.isDecryptMode())
                    result.decrypted++;
                else if (row.getEncryption() == null)
                    result.encrypted++;
                else
                    result.rekeyed++;
            }
            catch (GuacamoleException e) {
                result.failed++;
                logger.warn("Parameter encryption sweep skipped a row: {}", e.getMessage());
            }
            if (++handled % BATCH_SIZE == 0)
                logger.info("Parameter encryption sweep: {} {} rows processed.", handled, owner.getType());
        }
    }

}
```

The `remaining` count re-queries after the sweep. A row that could not be decrypted is still a candidate, so failures are subtracted: `remaining` counts plain-text secrets only.

`SweepStarter.java`:
```java
package org.apache.guacamole.auth.jdbc.encryption;

import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the parameter encryption sweep once, in the background, when the
 * extension loads and encryption is configured. Never delays startup.
 */
public class SweepStarter {

    private static final Logger logger = LoggerFactory.getLogger(SweepStarter.class);

    @Inject
    public SweepStarter(ParameterEncryptionService service,
            ParameterEncryptionSweep sweep) {

        if (!service.isEnabled())
            return;

        Thread thread = new Thread(() -> {
            try {
                logger.info("Parameter encryption sweep finished: {}.", sweep.run());
            }
            catch (RuntimeException e) {
                logger.error("Parameter encryption sweep failed: {}", e.getMessage());
                logger.debug("Parameter encryption sweep failure.", e);
            }
        }, "parameter-encryption-sweep");
        thread.setDaemon(true);
        thread.start();

    }

}
```
The sweep uses the MyBatis mapper outside a request. Check how other background code in this module (e.g. `reset/SelfServicePasswordResetService`'s mailer thread) obtains a mapper session, and follow it. If mappers need a `@Transactional` boundary, annotate `ParameterEncryptionSweep.run()` with `@org.mybatis.guice.transactional.Transactional`, or the module's equivalent.

- [ ] **Step 4: Mapper XML (×3).** PostgreSQL `encryption/ParameterEncryptionMapper.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
    "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<!-- (ASF license header comment — copy from ConnectionParameterMapper.xml) -->
<mapper namespace="org.apache.guacamole.auth.jdbc.encryption.ParameterEncryptionMapper">

    <resultMap id="ConnectionRow" type="org.apache.guacamole.auth.jdbc.encryption.ParameterRow">
        <result column="connection_id"        property="ownerIdentifier" jdbcType="INTEGER"/>
        <result column="parameter_name"       property="name"            jdbcType="VARCHAR"/>
        <result column="parameter_value"      property="value"           jdbcType="VARCHAR"/>
        <result column="parameter_encryption" property="encryption"      jdbcType="VARCHAR"/>
    </resultMap>

    <resultMap id="SharingProfileRow" type="org.apache.guacamole.auth.jdbc.encryption.ParameterRow">
        <result column="sharing_profile_id"   property="ownerIdentifier" jdbcType="INTEGER"/>
        <result column="parameter_name"       property="name"            jdbcType="VARCHAR"/>
        <result column="parameter_value"      property="value"           jdbcType="VARCHAR"/>
        <result column="parameter_encryption" property="encryption"      jdbcType="VARCHAR"/>
    </resultMap>

    <sql id="candidateFilter">
        <choose>
            <when test="decrypt">
                parameter_encryption IS NOT NULL
            </when>
            <otherwise>
                parameter_name IN
                    <foreach collection="names" item="name" open="(" separator="," close=")">
                        #{name,jdbcType=VARCHAR}
                    </foreach>
                AND (parameter_encryption IS NULL
                     OR parameter_encryption &lt;&gt; #{currentKeyId,jdbcType=VARCHAR})
            </otherwise>
        </choose>
    </sql>

    <select id="selectConnectionCandidates" resultMap="ConnectionRow">
        SELECT connection_id, parameter_name, parameter_value, parameter_encryption
        FROM guacamole_connection_parameter
        WHERE <include refid="candidateFilter"/>
        ORDER BY connection_id, parameter_name
    </select>

    <select id="selectSharingProfileCandidates" resultMap="SharingProfileRow">
        SELECT sharing_profile_id, parameter_name, parameter_value, parameter_encryption
        FROM guacamole_sharing_profile_parameter
        WHERE <include refid="candidateFilter"/>
        ORDER BY sharing_profile_id, parameter_name
    </select>

    <update id="updateConnectionParameter">
        UPDATE guacamole_connection_parameter
        SET parameter_value      = #{newValue,jdbcType=VARCHAR},
            parameter_encryption = #{newEncryption,jdbcType=VARCHAR}
        WHERE connection_id   = #{row.ownerIdentifier,jdbcType=INTEGER}::integer
          AND parameter_name  = #{row.name,jdbcType=VARCHAR}
          AND parameter_value = #{row.value,jdbcType=VARCHAR}
          AND parameter_encryption IS NOT DISTINCT FROM #{row.encryption,jdbcType=VARCHAR}
    </update>

    <update id="updateSharingProfileParameter">
        UPDATE guacamole_sharing_profile_parameter
        SET parameter_value      = #{newValue,jdbcType=VARCHAR},
            parameter_encryption = #{newEncryption,jdbcType=VARCHAR}
        WHERE sharing_profile_id = #{row.ownerIdentifier,jdbcType=INTEGER}::integer
          AND parameter_name     = #{row.name,jdbcType=VARCHAR}
          AND parameter_value    = #{row.value,jdbcType=VARCHAR}
          AND parameter_encryption IS NOT DISTINCT FROM #{row.encryption,jdbcType=VARCHAR}
    </update>

</mapper>
```

  MySQL: identical except no `::integer` casts, and the null-safe comparison is `parameter_encryption <=> #{row.encryption,jdbcType=VARCHAR}`.

  SQL Server: identical except no casts, and the null-safe comparison is
  `(parameter_encryption = #{row.encryption,jdbcType=VARCHAR} OR (parameter_encryption IS NULL AND #{row.encryption,jdbcType=VARCHAR} IS NULL))`.

  The `names` collection is never empty, because the built-ins are always present, so `IN ()` cannot occur.

- [ ] **Step 5: Guice.** In `JDBCAuthenticationProviderModule.configure()`, add `addMapperClass(ParameterEncryptionMapper.class);` next to the other mapper registrations, and `bind(SweepStarter.class).asEagerSingleton();`.

- [ ] **Step 6: Run, verify pass.** `-Dtest=ParameterEncryptionSweepTest`. Expected: `Tests run: 6, Failures: 0`. Then run the full module build (the Task 7 Step 6 command). Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc
git commit -m "GUACAMOLE-558: Sweep existing parameters into the configured encryption at startup." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

**This is the last upstreamable commit.** Task 9 is fork-only.

---

### Task 9: KMS key provider module (fork only)

**Files:**
- Create: `extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-kms/pom.xml`, `.ratignore` (empty)
- Create: `…/guacamole-auth-jdbc-kms/src/main/java/org/apache/guacamole/auth/jdbc/kms/KmsParameterKeyProvider.java`, `KmsParameterKeyProviderFactory.java`, `package-info.java`
- Create: `…/guacamole-auth-jdbc-kms/src/main/resources/META-INF/services/org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProviderFactory`
- Modify: `extensions/guacamole-auth-jdbc/pom.xml` (add the module), and the three database modules' `pom.xml` (add the `guacamole-auth-jdbc-kms` dependency so the shaded extension jars carry it)
- Modify: `doc/licenses/*` for the new bundled AWS SDK jars
- Test: `…/guacamole-auth-jdbc-kms/src/test/java/org/apache/guacamole/auth/jdbc/kms/KmsParameterKeyProviderTest.java`

**Interfaces:**
- Consumes: `ParameterKeyProviderFactory`, `ParameterKeyProvider`, `PropertyParameterKeyProvider`, `Keyring.parse(spec, Decoder)`, `JDBCEnvironment`.
- Produces:
  - Factory name `"kms"`.
  - `KmsParameterKeyProvider.create(JDBCEnvironment env, Function<byte[], byte[]> unwrap)`, which is testable with a fake `unwrap`.

- [ ] **Step 1: pom.** Model it on `guacamole-auth-jdbc-base/pom.xml`: same parent and version, packaging `jar`. Dependencies:
  - `guacamole-auth-jdbc-base` (provided);
  - `software.amazon.awssdk:kms`, `software.amazon.awssdk:sts` (needed for web-identity / IRSA credentials) and `software.amazon.awssdk:url-connection-client`, all from the AWS SDK BOM at its current 2.x release;
  - JUnit Jupiter (test).

  Exclude `netty-nio-client` and `apache-client` from `kms` and `sts`, so only the URL-connection HTTP client ships.

- [ ] **Step 2: Failing test**

```java
package org.apache.guacamole.auth.jdbc.kms;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Base64;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.encryption.Keyring;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProvider;
import org.junit.jupiter.api.Test;

public class KmsParameterKeyProviderTest {

    @Test
    public void unwrapsEachKeyOnce() throws Exception {
        int[] calls = { 0 };
        String wrapped = Base64.getEncoder().encodeToString(new byte[] { 9, 9 });
        ParameterKeyProvider p = KmsParameterKeyProvider.fromKeyring(
            Keyring.parse("k1:" + wrapped, KmsParameterKeyProvider.decoder(blob -> {
                calls[0]++;
                assertArrayEquals(new byte[] { 9, 9 }, blob);
                return new byte[32];
            })), "k1");
        assertEquals(1, calls[0]);
        assertEquals("k1", p.currentKeyId());
        assertEquals(32, p.keyFor("k1").length);
    }

    @Test
    public void unwrapFailureNamesKeyOnly() {
        String wrapped = Base64.getEncoder().encodeToString(new byte[] { 1 });
        GuacamoleServerException e = assertThrows(GuacamoleServerException.class,
            () -> Keyring.parse("k1:" + wrapped, KmsParameterKeyProvider.decoder(blob -> {
                throw new RuntimeException("AccessDenied");
            })));
        assertTrue(e.getMessage().contains("k1"));
        assertFalse(e.getMessage().contains(wrapped));
    }

}
```

- [ ] **Step 3: Implement**

```java
package org.apache.guacamole.auth.jdbc.kms;

import java.util.Base64;
import java.util.Map;
import java.util.function.Function;
import org.apache.guacamole.GuacamoleServerException;
import org.apache.guacamole.auth.jdbc.encryption.Keyring;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProvider;
import org.apache.guacamole.auth.jdbc.encryption.PropertyParameterKeyProvider;

/**
 * Key provider whose keyring values are AWS KMS ciphertext blobs. Each is
 * unwrapped once with KMS Decrypt at startup; plain keys stay in memory.
 */
public final class KmsParameterKeyProvider {

    private KmsParameterKeyProvider() {}

    /** Decoder turning a base64 KMS blob into a plain key via unwrap. */
    public static Keyring.Decoder decoder(Function<byte[], byte[]> unwrap) {
        return (keyId, value) -> {
            byte[] blob;
            try {
                blob = Base64.getDecoder().decode(value);
            }
            catch (IllegalArgumentException e) {
                throw new GuacamoleServerException("Parameter encryption key \""
                        + keyId + "\" is not valid base64.");
            }
            try {
                return unwrap.apply(blob);
            }
            catch (RuntimeException e) {
                throw new GuacamoleServerException("AWS KMS could not decrypt "
                        + "parameter encryption key \"" + keyId + "\": "
                        + e.getClass().getSimpleName());
            }
        };
    }

    /** Builds the provider from an already-unwrapped keyring. */
    public static ParameterKeyProvider fromKeyring(Map<String, byte[]> keys,
            String currentKeyId) throws GuacamoleServerException {
        return new PropertyParameterKeyProvider(keys, currentKeyId);
    }

}
```

`KmsParameterKeyProviderFactory.java`:
```java
package org.apache.guacamole.auth.jdbc.kms;

import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;
import org.apache.guacamole.auth.jdbc.encryption.Keyring;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProvider;
import org.apache.guacamole.auth.jdbc.encryption.ParameterKeyProviderFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;

/**
 * Selected by "*-parameter-encryption-provider: kms". Uses the default AWS
 * credential and region chains (including web identity / IRSA).
 */
public class KmsParameterKeyProviderFactory implements ParameterKeyProviderFactory {

    @Override
    public String getName() {
        return "kms";
    }

    @Override
    public ParameterKeyProvider create(JDBCEnvironment environment)
            throws GuacamoleException {
        try (KmsClient kms = KmsClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            return KmsParameterKeyProvider.fromKeyring(
                    Keyring.parse(environment.getParameterEncryptionKeys(),
                            KmsParameterKeyProvider.decoder(blob -> kms.decrypt(
                                    DecryptRequest.builder()
                                            .ciphertextBlob(SdkBytes.fromByteArray(blob))
                                            .build())
                                    .plaintext().asByteArray())),
                    environment.getParameterEncryptionCurrentKey());
        }
    }

}
```

The services file contains:
```
org.apache.guacamole.auth.jdbc.kms.KmsParameterKeyProviderFactory
```

- [ ] **Step 4: Run tests.** Run: `cd extensions/guacamole-auth-jdbc && ~/.local/bin/mvn -q verify -pl modules/guacamole-auth-jdbc-kms -am`. Expected: `Tests run: 2, Failures: 0`.

- [ ] **Step 5: Licences.** The new bundled jars need `doc/licenses/<dep>/` entries. `generate-license-files.sh` silently does nothing on macOS, so regenerate them on Linux, the way the fork did for the dependency bumps:
```bash
docker run --rm -v "$PWD":/src -w /src maven:3-eclipse-temurin-21 \
  mvn -B -q package -DskipTests -DdownloadMissingLicenses -Dexec.skip=true -Dskip.npm -Dskip.installnodenpm \
  -pl extensions/guacamole-auth-jdbc/modules/guacamole-auth-jdbc-postgresql -am
```
Review the new `doc/licenses/*` directories: every one should be Apache-2.0, as the AWS SDK and its transitive dependencies are expected to be. Commit them. Then `docker build --progress=plain .` must succeed **without** `-DdownloadMissingLicenses`.

- [ ] **Step 6: Commit**

```bash
git add -A extensions/guacamole-auth-jdbc doc/licenses
git commit -m "GUACAMOLE-558: Add optional AWS KMS key provider for parameter encryption." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: End-to-end verification on all three databases

No new committed code. The evidence goes into the PR description. Scripts live in the session scratchpad, not the repo.

- [ ] **Step 1: Build the image.** Run: `docker build --progress=plain -t guac-558:test .`. Expected: BUILD SUCCESS, with every module's tests passing in the log, including the new suites.

- [ ] **Step 2: PostgreSQL scenario.** Run postgres:16, `guacamole/guacd:1.6.0`, an SSH target (`linuxserver/openssh-server` with a password user) and `guac-558:test`.

  1. Initialise the database with `origin/main`'s 001+002 schema (pre-558), and create an SSH connection whose password is set, through REST with **no** key configured. Check that `parameter_value` holds the plain password.
  2. Apply the 558 part of `upgrade-pre-1.6.1.sql`. Restart the webapp with `POSTGRESQL_PARAMETER_ENCRYPTION_KEYS=k1:$(openssl rand -base64 32)` and `POSTGRESQL_PARAMETER_ENCRYPTION_CURRENT_KEY=k1`.
  3. The log contains `Parameter encryption sweep finished: encrypted 1, re-keyed 0, decrypted 0, skipped 0, failed 0, remaining plain-text secrets 0.`
  4. `SELECT parameter_name, parameter_encryption, length(parameter_value) FROM guacamole_connection_parameter` shows `password | k1 | <~44>`, and `hostname | NULL | …`. Do not print values.
  5. Open the SSH connection through the HTTP tunnel and see terminal output. guacd logs `SSH connection successful`.
  6. `GET /api/session/data/postgresql/connections/<id>/parameters` as guacadmin returns the plain password.
  7. Rotation: set `KEYS=k1:…,k2:…` and `CURRENT_KEY=k2`, then restart. The sweep reports `re-keyed 1`, the row shows `k2`, and the session still works.
  8. Decrypt mode: set `POSTGRESQL_PARAMETER_ENCRYPTION_MODE=decrypt` and restart. The sweep reports `decrypted 1`, and the row is plain text with NULL encryption. Run the `origin/main` image (no 558 code) against this database: the session works.
  9. Bad configuration: set `CURRENT_KEY=nope` and restart. The extension fails to load, and the log contains `The current parameter encryption key "nope" is not in the keyring.` and no key material.
  10. Missing key: encrypt again with `k1`, then restart with only `k2`. Opening the connection fails with a message naming the connection, `password` and `k1`. The admin editor shows the password field empty, and the log has the ERROR from `revealOrOmit`.
  11. Size limit: saving a connection with an 8 KB `private-key` through REST returns 400 naming `private-key`.

- [ ] **Step 3: MySQL and SQL Server.** Repeat steps 1–6 and 8 of the PostgreSQL scenario, with `mysql:8.0` (`MYSQL_PARAMETER_ENCRYPTION_*`) and `mcr.microsoft.com/mssql/server:2022-latest` (`SQLSERVER_PARAMETER_ENCRYPTION_*`). Expected: the same results.

- [ ] **Step 4: Scale.** In PostgreSQL, insert 600 connections, each with `hostname` and `password`, via SQL with encryption off. Restart with a key and time the sweep from start to the "finished" log line. Expected: `encrypted 600, … remaining plain-text secrets 0`. Record the duration.

- [ ] **Step 5: Clean up** all containers and networks. Record the evidence (commands and output, never values) for the PR.

---

### Task 11: Test with the KMS provider on a non-production cluster

**Requires the user's explicit go-ahead before Step 1.** It creates AWS resources: a KMS key and an IAM policy and role in a non-production AWS account.

- [ ] **Step 1 (after approval):**
  - Create a test KMS key in that non-production account.
  - Create an IRSA role that allows `kms:Decrypt` on that key only, trusted by the service account `guacamole` in a test namespace on a non-production Kubernetes test cluster.
  - Generate a data key with `aws kms generate-data-key --key-id <key> --key-spec AES_256`. Base64 the `CiphertextBlob` into `POSTGRESQL_PARAMETER_ENCRYPTION_KEYS=k1:<blob>`, and throw the plain key away.
  - Use that cluster's context and AWS profile.
  - Prefer the project's Terraform repo over CLI if the user asks for it.
- [ ] **Step 2:** Build an arm64 image from this branch, push `guacamole-cluster:1.6.1-enc-rc1`, and deploy it to the test namespace:
  - `POSTGRESQL_PARAMETER_ENCRYPTION_PROVIDER=kms`;
  - the service account annotated with the role;
  - the upgrade SQL applied first.

  Expected: the sweep reports `remaining plain-text secrets 0`; an SSH session works; the logs show no KMS error; CloudTrail shows one `Decrypt` call per replica start.
- [ ] **Step 3:** Remove `kms:Decrypt` from the role and restart one pod. Expected: the extension fails to load with `AWS KMS could not decrypt parameter encryption key "k1": KmsException (AccessDeniedException)`, and nothing leaks. Restore the permission afterwards.

---

### Task 12: Pull requests

- [ ] **Step 1: Fork PR.**
  - Push `GUACAMOLE-558-parameter-encryption`.
  - Open a PR to `honeybadger-technologies/guacamole-client` `main`, titled `GUACAMOLE-558: Store secret connection parameters encrypted`.
  - Body: the summary, a link to the spec, the configuration table, the upgrade steps, the backup caveat (spec §9), the evidence from Tasks 10–11, and an AI disclosure. End with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
  - Grep the body for internal names before creating it.
- [ ] **Step 2: Upstream branch.**
  - `git worktree add -b GUACAMOLE-558 ../guacamole-client-up558 apache/main`.
  - Cherry-pick the Task 2–8 commits only: not the spec or plan commits, not Task 9.
  - Conflicts are expected in `upgrade-pre-1.6.1.sql`, which does not exist on `apache/main`. Create it with only the 558 section, plus the standard header copied from `upgrade-pre-1.6.0.sql`.
  - `001-create-schema.sql` and the mapper XMLs on `apache/main` lack the 1743/829 changes, so resolve by keeping only the 558 edits.
  - Check the protocol-descriptor field names still match.
  - Run `docker build --progress=plain .`. Expected: success.
  - Then `git diff apache/main | grep -i -w -E "cluster|redis|kms|aws|honeybadger"` must be empty.
- [ ] **Step 3: Upstream PR.**
  - Push `GUACAMOLE-558` to origin.
  - Open a PR to `apache/guacamole-client` `main`, head `honeybadger-technologies:GUACAMOLE-558`, titled `GUACAMOLE-558: Store secret connection parameters encrypted (JDBC).`
  - The body is generic: no KMS, no deployment details. Note that `upgrade-pre-1.6.1.sql` overlaps with #1254 and #1255, and include the AI disclosure.
- [ ] **Step 4:** Comment the PR links on the internal ticket (in Jira, never in the public PRs).
