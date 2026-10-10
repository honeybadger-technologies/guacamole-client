/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.guacamole.auth.jdbc.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;
import org.apache.guacamole.auth.jdbc.user.PasswordRecordMapper;
import org.apache.guacamole.auth.jdbc.user.PasswordRecordModel;
import org.apache.guacamole.auth.jdbc.user.UserModel;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test which verifies PBKDF2PasswordEncryptionService, including its handling
 * of legacy salted SHA-256 hashes and the password history check of
 * PasswordPolicyService across both algorithms.
 */
public class PBKDF2PasswordEncryptionServiceTest {

    /**
     * JDBCEnvironment returning a configurable iteration count, a password
     * policy remembering five previous passwords, and defaults otherwise.
     */
    private static class TestEnvironment extends JDBCEnvironment {

        /**
         * The iteration count to return from getPasswordHashIterations().
         */
        private int iterations = 1000;

        @Override public int getPasswordHashIterations() { return iterations; }
        @Override public boolean isUserRequired() { return false; }
        @Override public int getAbsoluteMaxConnections() { return 0; }
        @Override public int getBatchSize() { return 100; }
        @Override public int getDefaultMaxConnections() { return 0; }
        @Override public int getDefaultMaxGroupConnections() { return 0; }
        @Override public int getDefaultMaxConnectionsPerUser() { return 0; }
        @Override public int getDefaultMaxGroupConnectionsPerUser() { return 0; }
        @Override public boolean isRecursiveQuerySupported(SqlSession session) { return false; }
        @Override public boolean autoCreateAbsentAccounts() { return false; }
        @Override public String getUsername() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean trackExternalConnectionHistory() { return false; }
        @Override public boolean enforceAccessWindowsForActiveSessions() { return false; }

        @Override
        public PasswordPolicy getPasswordPolicy() {
            return (PasswordPolicy) Proxy.newProxyInstance(
                    PasswordPolicy.class.getClassLoader(),
                    new Class<?>[] { PasswordPolicy.class },
                    (proxy, method, args) -> method.getName().equals("getHistorySize")
                            ? 5 : method.getReturnType() == int.class ? 0 : false);
        }

    }

    /**
     * The test environment shared with the services under test.
     */
    private TestEnvironment environment;

    /**
     * The password history returned by the stub PasswordRecordMapper.
     */
    private List<PasswordRecordModel> history;

    /**
     * Injector providing the services under test.
     */
    private Injector injector;

    /**
     * The service under test.
     */
    private PBKDF2PasswordEncryptionService service;

    @BeforeEach
    public void setUp() {

        environment = new TestEnvironment();
        history = new ArrayList<>();

        PasswordRecordMapper mapper = (PasswordRecordMapper) Proxy.newProxyInstance(
                PasswordRecordMapper.class.getClassLoader(),
                new Class<?>[] { PasswordRecordMapper.class },
                (proxy, method, args) -> history);

        injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(JDBCEnvironment.class).toInstance(environment);
                bind(PasswordRecordMapper.class).toInstance(mapper);
            }
        });

        service = injector.getInstance(PBKDF2PasswordEncryptionService.class);

    }

    /**
     * Returns a user model holding a legacy salted SHA-256 hash of the given
     * password, as stored by previous versions.
     *
     * @param password
     *     The password to hash.
     *
     * @return
     *     A user model holding a legacy hash of the given password.
     */
    private static UserModel legacyUser(String password) {
        UserModel user = new UserModel();
        byte[] salt = "legacy-salt".getBytes(StandardCharsets.UTF_8);
        user.setPasswordSalt(salt);
        user.setPasswordHash(new SHA256PasswordEncryptionService().createPasswordHash(password, salt));
        return user;
    }

    /**
     * Verifies that PBKDF2 hashing is deterministic for the same salt and
     * iteration count, and differs for different salts or iteration counts.
     */
    @Test
    public void testDeterministic() {

        byte[] salt = "salt-one".getBytes(StandardCharsets.UTF_8);
        byte[] hash = service.createPasswordHash("password", salt);

        assertEquals(32, hash.length);
        assertArrayEquals(hash, service.createPasswordHash("password", salt));
        assertFalse(Arrays.equals(hash, service.createPasswordHash("password",
                "salt-two".getBytes(StandardCharsets.UTF_8))));

        environment.iterations = 1001;
        assertFalse(Arrays.equals(hash, service.createPasswordHash("password", salt)));

    }

    /**
     * Verifies that legacy salted and unsalted SHA-256 hashes still verify.
     */
    @Test
    public void testLegacyVerification() {

        UserModel user = legacyUser("password");
        assertTrue(service.verifyPassword(user, "password"));
        assertFalse(service.verifyPassword(user, "Password"));
        assertFalse(service.verifyPassword(user, null));

        // Unsalted legacy hashes predate salting entirely
        user.setPasswordSalt(null);
        user.setPasswordHash(new SHA256PasswordEncryptionService().createPasswordHash("password", null));
        assertTrue(service.verifyPassword(user, "password"));

    }

    /**
     * Verifies that a legacy hash which verifies is upgraded in place to
     * PBKDF2, which then verifies the same password.
     */
    @Test
    public void testUpgradeOnLogin() {

        UserModel user = legacyUser("password");
        byte[] legacyHash = user.getPasswordHash();
        assertNull(user.getPasswordHashAlgorithm());
        assertTrue(service.isUpgradeNeeded(user));

        // The same steps UserService takes upon successful login
        assertTrue(service.verifyPassword(user, "password"));
        service.setPassword(user, "password");

        assertEquals(PBKDF2PasswordEncryptionService.ALGORITHM, user.getPasswordHashAlgorithm());
        assertEquals(Integer.valueOf(1000), user.getPasswordHashIterations());
        assertFalse(Arrays.equals(legacyHash, user.getPasswordHash()));
        assertTrue(service.verifyPassword(user, "password"));
        assertFalse(service.verifyPassword(user, "wrong"));
        assertFalse(service.isUpgradeNeeded(user));

        // Raising the configured cost marks the hash for upgrade again
        environment.iterations = 2000;
        assertTrue(service.isUpgradeNeeded(user));
        assertTrue(service.verifyPassword(user, "password"));

    }

    /**
     * Verifies that hashes of an unknown algorithm never verify.
     */
    @Test
    public void testUnknownAlgorithm() {
        UserModel user = new UserModel();
        service.setPassword(user, "password");
        user.setPasswordHashAlgorithm("PBKDF2WithHmacSHA1");
        assertFalse(service.verifyPassword(user, "password"));
    }

    /**
     * Verifies that PasswordPolicyService rejects reuse of a password found
     * in history rows of either algorithm, and accepts unused passwords.
     */
    @Test
    public void testHistoryAcrossAlgorithms() throws Exception {

        UserModel legacy = legacyUser("legacy-password");
        UserModel modern = new UserModel();
        service.setPassword(modern, "modern-password");
        history.add(new PasswordRecordModel(legacy));
        history.add(new PasswordRecordModel(modern));

        PasswordPolicyService policyService = injector.getInstance(PasswordPolicyService.class);
        assertThrows(PasswordReusedException.class,
                () -> policyService.verifyPassword("user", "legacy-password"));
        assertThrows(PasswordReusedException.class,
                () -> policyService.verifyPassword("user", "modern-password"));
        policyService.verifyPassword("user", "new-password");

    }

}
