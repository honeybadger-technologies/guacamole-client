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

import com.google.inject.Inject;
import java.security.MessageDigest;
import org.apache.guacamole.GuacamoleException;
import org.apache.guacamole.auth.jdbc.JDBCEnvironment;
import org.apache.guacamole.auth.jdbc.user.UserModel;
import org.apache.guacamole.net.auth.PBKDF2PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a PBKDF2-HMAC-SHA256 based implementation of the password
 * encryption functionality, delegating to {@link PBKDF2PasswordHasher}. Legacy
 * salted SHA-256 hashes, which have no stored algorithm, remain verifiable so
 * that they can be upgraded when their users next log in.
 */
public class PBKDF2PasswordEncryptionService implements PasswordEncryptionService {

    /**
     * The algorithm name stored alongside each PBKDF2 password hash.
     */
    public static final String ALGORITHM = PBKDF2PasswordHasher.ALGORITHM;

    /**
     * Logger for this class.
     */
    private static final Logger logger = LoggerFactory.getLogger(PBKDF2PasswordEncryptionService.class);

    /**
     * Fixed salt used for dummy hash computations which exist only to keep
     * the timing of failed logins independent of whether the user exists.
     */
    private static final byte[] DUMMY_SALT = new byte[PBKDF2PasswordHasher.DEFAULT_LENGTH];

    /**
     * Service for verifying legacy salted SHA-256 hashes.
     */
    private final PasswordEncryptionService legacyService = new SHA256PasswordEncryptionService();

    /**
     * The server environment for retrieving configuration.
     */
    @Inject
    private JDBCEnvironment environment;

    /**
     * Returns the number of PBKDF2 iterations to use for new hashes, falling
     * back to {@link PBKDF2PasswordHasher#DEFAULT_ITERATIONS} if the
     * configured value cannot be read.
     *
     * @return
     *     The number of PBKDF2 iterations to use for new hashes.
     */
    public int getIterations() {
        try {
            return environment.getPasswordHashIterations();
        }
        catch (GuacamoleException e) {
            logger.warn("Unable to read the configured password hash "
                    + "iteration count. Using the default of {}: {}",
                    PBKDF2PasswordHasher.DEFAULT_ITERATIONS, e.getMessage());
            logger.debug("Unable to read password hash iteration count.", e);
            return PBKDF2PasswordHasher.DEFAULT_ITERATIONS;
        }
    }

    @Override
    public byte[] createPasswordHash(String password, byte[] salt) {
        return PBKDF2PasswordHasher.hash(password, salt, getIterations());
    }

    /**
     * Hashes the given password with a new random salt, storing the salt,
     * hash, algorithm, and iteration count within the given user model. The
     * password date is not modified.
     *
     * @param user
     *     The user model to update.
     *
     * @param password
     *     The password to hash.
     */
    public void setPassword(UserModel user, String password) {
        int iterations = getIterations();
        byte[] salt = PBKDF2PasswordHasher.generateSalt();
        user.setPasswordSalt(salt);
        user.setPasswordHash(PBKDF2PasswordHasher.hash(password, salt, iterations));
        user.setPasswordHashAlgorithm(ALGORITHM);
        user.setPasswordHashIterations(iterations);
    }

    /**
     * Returns whether the given password matches the given stored hash,
     * using a constant-time comparison. A null password never matches.
     *
     * @param password
     *     The password to verify.
     *
     * @param salt
     *     The stored salt, if any.
     *
     * @param hash
     *     The stored hash.
     *
     * @param algorithm
     *     The stored algorithm, or null for a legacy salted SHA-256 hash.
     *
     * @param iterations
     *     The stored iteration count, or null for a legacy hash.
     *
     * @return
     *     true if the password matches, false otherwise.
     */
    public boolean verifyPassword(String password, byte[] salt, byte[] hash,
            String algorithm, Integer iterations) {

        if (password == null || hash == null)
            return false;

        // Legacy salted SHA-256
        if (algorithm == null)
            return MessageDigest.isEqual(
                    legacyService.createPasswordHash(password, salt), hash);

        if (ALGORITHM.equals(algorithm) && iterations != null && iterations > 0
                && salt != null && salt.length > 0)
            return PBKDF2PasswordHasher.verify(password, salt, iterations, hash);

        logger.warn("Stored password hash uses an unsupported algorithm or "
                + "invalid parameters (\"{}\", {} iterations).", algorithm, iterations);
        return false;

    }

    /**
     * Returns whether the given password matches the password hash stored
     * within the given user model.
     *
     * @param user
     *     The user model containing the stored hash.
     *
     * @param password
     *     The password to verify.
     *
     * @return
     *     true if the password matches, false otherwise.
     */
    public boolean verifyPassword(UserModel user, String password) {
        return verifyPassword(password, user.getPasswordSalt(),
                user.getPasswordHash(), user.getPasswordHashAlgorithm(),
                user.getPasswordHashIterations());
    }

    /**
     * Returns whether the password hash stored within the given user model
     * should be replaced, as it is a legacy hash or uses fewer iterations
     * than currently configured.
     *
     * @param user
     *     The user model containing the stored hash.
     *
     * @return
     *     true if the hash should be replaced, false otherwise.
     */
    public boolean isUpgradeNeeded(UserModel user) {
        Integer iterations = user.getPasswordHashIterations();
        return !ALGORITHM.equals(user.getPasswordHashAlgorithm())
                || iterations == null || iterations < getIterations();
    }

    /**
     * Performs a PBKDF2 computation whose result is discarded, such that a
     * failed login takes roughly as long as verifying a real PBKDF2 hash.
     *
     * @param password
     *     The password provided by the user, if any.
     */
    public void simulateVerification(String password) {
        PBKDF2PasswordHasher.hash(password != null ? password : "",
                DUMMY_SALT, getIterations());
    }

}
