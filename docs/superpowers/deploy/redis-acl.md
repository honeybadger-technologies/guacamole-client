# Redis ACL for Guacamole

The Guacamole user is scoped to the `guac:` prefix, for keys **and** channels.
The channel pattern is not optional: cross-replica kill, share-key revocation
and cluster-wide logout are all pub/sub, and a key pattern alone silently
disables them while everything else keeps working.

Put this in the `users.acl` key of the `guacamole-redis` Secret, with the
password substituted:

```
user default off

user guacamole on >CHANGE_ME_TO_THE_SAME_PASSWORD ~guac:* &guac:* \
    -@all \
    +@connection +@keyspace +@string +@hash +@sortedset +@scripting +@pubsub \
    -flushall -flushdb -keys -scan -randomkey \
    -script|flush -script|kill
```

`user default off` is the load-bearing line. Leaving the default user enabled
leaves an unauthenticated, unscoped account on the port, and the ACL above then
protects nothing.

## Why those command classes

Derived from the code rather than guessed. Re-derive after any change to the
cluster store:

```bash
grep -rhoE "commands\(\)\.[a-zA-Z]+" guacamole-ext/src/main/java/org/apache/guacamole/cluster
grep -rhoE "redis\.call\('[A-Z]+'" guacamole-ext/src/main/resources/org/apache/guacamole/cluster/redis/*.lua
```

Today that is `del exists expire get hgetall hset publish ttl zadd zcount
zrangebyscore zrem evalsha scriptLoad subscribe`, plus `EXPIRE INCR TIME ZADD
ZCARD ZREMRANGEBYSCORE ZSCORE` inside the Lua scripts.

- `@connection` covers the handshake. Lettuce sends `HELLO` and sets a client
  name; denying those breaks the connection rather than one command.
- `@scripting` covers `EVALSHA` and `SCRIPT LOAD`. Script keys are checked
  against the key pattern, and every key the scripts touch is under `guac:`.
- `FLUSHALL` appears in the code, in `flushForTesting()`, used only by the test
  suite. It must never be granted in a deployment.
- `KEYS`, `SCAN` and `RANDOMKEY` are denied because nothing needs them, and
  `KEYS guac:token:*` is the one command that turns this keyspace into a list
  of who is currently logged in.

**Verify the ACL against a running Redis before trusting it.** An over-tight
ACL fails in a way that looks like an application bug: connections succeed and
one feature stops working. The cheapest check is to exercise a login, a
connection, a share and a logout with the ACL in place, then confirm the log
carries no `NOPERM`.

## Preflight checks for a shared Redis

If Guacamole is pointed at an existing Redis rather than a dedicated one, these
are the things that decide whether it is safe:

```bash
redis-cli CONFIG GET maxmemory-policy   # must be noeviction
redis-cli INFO server | grep redis_version   # must be >= 6 for ACLs
redis-cli ACL WHOAMI                    # must not be "default"
```

An LRU policy is disqualifying rather than a matter of tuning. Eviction of a
`guac:idx:*` or `guac:seat:*` member corrupts concurrency limits and guacd
routing silently, and eviction of `guac:token:*` logs users out at random.

Note also that `FLUSHALL` crosses logical databases, so putting Guacamole on
`/3` of a shared instance does not protect it from a cache flush.
