--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

--
-- Add the algorithm and iteration count of each password hash, allowing
-- adaptive PBKDF2 hashes. NULL denotes a legacy salted SHA-256 hash.
--

ALTER TABLE [guacamole_user] ADD
    [password_hash_algorithm]  [nvarchar](32),
    [password_hash_iterations] [int];
GO

ALTER TABLE [guacamole_user_password_history] ADD
    [password_hash_algorithm]  [nvarchar](32),
    [password_hash_iterations] [int];
GO
