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


-- Resolves a join request only if it is still pending.
--
-- KEYS[1] : the request hash
-- ARGV[1] : the new status
-- ARGV[2] : the share key, or an empty string for none
if redis.call('HGET', KEYS[1], 'status') ~= 'PENDING' then
    return 0
end
redis.call('HSET', KEYS[1], 'status', ARGV[1])
if ARGV[2] ~= '' then
    redis.call('HSET', KEYS[1], 'shareKey', ARGV[2])
end
return 1
