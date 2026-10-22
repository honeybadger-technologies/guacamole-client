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


-- Stores a join request and indexes it under each targeted session.
--
-- KEYS[1]    : the request hash
-- KEYS[2..n] : one index set per targeted primary session
-- ARGV[1]    : TTL, in seconds
-- ARGV[2]    : the request ID
-- ARGV[3..]  : field, value pairs
--
-- One script, so that no key is ever left without its expiry.
for i = 3, #ARGV, 2 do
    redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
end
redis.call('EXPIRE', KEYS[1], ARGV[1])
for i = 2, #KEYS do
    redis.call('SADD', KEYS[i], ARGV[2])
    redis.call('EXPIRE', KEYS[i], ARGV[1])
end
return 1
