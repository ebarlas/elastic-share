# DLS Search Trace Execution Timeline

Opaque ID: `dls-search-trace-1776488718255`

**Cluster layout**: 2-node cluster, 3 shards, 0 replicas, 20 documents.
- **node-0** (`test-cluster-0`): coordinator, shard [1] (6 docs)
- **node-1** (`test-cluster-1`): data node, shards [0] (9 docs) and [2] (5 docs)

**DLS role**: `dls_search_trace_role` with query `{"term": {"department": "engineering"}}`.
9 of 20 documents match the DLS filter (3 per shard).

---

## Phase 1: HTTP ingress and user lookup

All on **node-0**, `transport_worker[T#1]`.

### T+0ms — `http-incoming`
Netty4HttpHeaderValidator.validate(). First touch of the HTTP request on the wire.
`method=GET, uri=dls-trace-test/_search?size=10`

### T+4ms — `http-authn`
AuthenticationService.authenticate(HttpPreRequest). Begins authenticating the
`dls_trace_user` credential from the Authorization header.

### T+6ms — `task-manager-register-and-execute`
TaskManager.registerAndExecute(). Registers `action=indices:data/read/get` — an
internal read of the `.security-7` index to fetch the native user record. This is
NOT the search itself.

### T+10ms — `authz-check`
AuthorizationService.authorize(). Authorizes the `.security` get as
`user=_xpack_security` (internal security subsystem user).

### T+13ms — `transport-authn-authz`
ServerTransportFilter.inbound(). Security transport filter for the shard-level
`indices:data/read/get[s]`. This is a local dispatch — the `.security-7` shard
lives on node-0 in this run.

### T+15ms — `authz-check`
Authorizes `indices:data/read/get[s]` for `user=_xpack_security`.

### T+17ms — `thread-context-run` → `system_critical_read[T#1]`
ContextPreservingRunnable.run(). Dispatches the `.security-7` shard read to the
`system_critical_read` thread pool.

### T+19ms — `dls-reader-wrap`
SecurityIndexReaderWrapper.apply(). Wraps the `.security-7` reader.
`hasDls=false, dlsQuery=null` — no DLS on the security index.

### T+24ms — `thread-context-run` → `get[T#1]`
Response handler dispatch for the completed user lookup.

---

## Phase 2: Search action dispatch

Back on **node-0**, `transport_worker[T#1]`.

### T+82ms — `http-request`
AbstractHttpServerTransport.incomingRequest(). Authentication is complete; the
HTTP request enters the action pipeline. The 58ms gap from user-lookup response
is the authentication completion callback chain.

### T+84ms — `task-manager-register-and-execute`
Registers the actual search task: `action=indices:data/read/search`.

### T+85ms — `authz-check` ⭐
AuthorizationService.authorize() for `action=indices:data/read/search,
user=dls_trace_user`.

**Key moment**: The role `dls_search_trace_role` is resolved. Its DLS query
`{"term": {"department": "engineering"}}` is parsed into `DocumentPermissions`,
assembled into an `IndicesAccessControl` map, and stored as the transient thread
context header `_indices_permissions` via `securityContext.putIndicesAccessControl()`.

Because the role has DLS, the pre-authorization optimization
(`PreAuthorizationUtils.maybeSkipChildrenActionAuthorization`) is skipped. This
means every data node must independently rebuild `IndicesAccessControl` with the
DLS query rather than relying on the coordinator's authorization.

### T+86ms — `thread-context-run` → `generic[T#2]`
Async dispatch for DeprecationRoleDescriptorConsumer (role deprecation check).

### T+86ms — `thread-context-run` → `generic[T#4]`
Async dispatch for AbstractThrottledTaskRunner (search execution).

### T+90ms — `search-execute` (on `generic[T#4]`)
TransportSearchAction.doExecute(). Main search entry point.
`indices=[dls-trace-test]`. The coordinator resolves shard routing: shard [1] is
local (node-0), shards [0] and [2] are remote (node-1).

---

## Phase 3: Query phase fan-out

On **node-0**, `generic[T#4]` unless noted.

### T+98ms — `search-send-query`
SearchTransportService.sendExecuteQuery(). Coordinator fans out query to
`shard=[dls-trace-test][1], node=test-cluster-0` (local shard).

### T+99ms — `transport-authn-authz`
ServerTransportFilter.inbound() for local `search[phase/query]` action.

### T+100ms — `authz-check`
Authorizes `indices:data/read/search[phase/query]` for `user=dls_trace_user`.

### T+101ms — `shard-query-execute`
SearchService.executeQueryPhase() for local `shardId=[dls-trace-test][1]`.

### T+103ms — `thread-context-run` → `search[T#1]`
Dispatches shard [1]'s reader acquisition to the `search` thread pool.

### T+118ms — `transport-send-request`
OutboundHandler.sendRequest(). Sends the remote query to node-1:
`action=indices:data/read/search[query][n]`. This is a node-level multi-shard
query — the coordinator batches shards [0] and [2] into a single transport
request since they're both on node-1.

---

## Phase 4: DLS wrapping and bitset on local shard

On **node-0**, `search[T#1]`.

### T+122ms — `dls-reader-wrap`
SecurityIndexReaderWrapper.apply() for `shardId=[dls-trace-test][1]`.
`hasDls=true, dlsQuery=(department:engineering)~1`. The role's DLS query is
materialized into a Lucene BooleanQuery via DocumentPermissions.filter() and
passed to DocumentSubsetReader.wrap().

### T+127ms — `dls-bitset-compute`
DocumentSubsetBitsetCache.getBitSet().
`query=ConstantScore((department:engineering)~1), cacheHit=false`
**cardinality=3, maxDoc=6** — 3 of 6 documents in shard [1] match the DLS filter.

---

## Phase 5: Query phase on node-1

### T+120ms — `inbound-handler-handle-request` (node-1 `transport_worker[T#1]`)
InboundHandler.handleRequest(). Receives `indices:data/read/search[query][n]` —
the node-level multi-shard query for shards [0] and [2].

### T+129ms — `transport-authn-authz` (same thread)
ServerTransportFilter.inbound() on node-1. Re-authenticates the request using the
serialized Authentication from the thread context headers.

### T+130ms — `authz-check` (same thread)
Authorizes `search[query][n]` for `user=dls_trace_user` **on node-1**. Because
the coordinator did NOT set ParentActionAuthorization (DLS is configured, so
the pre-authorization optimization is disabled), this goes through full
`buildIndicesAccessControl()`. The DLS query is re-resolved from the role
definition, a fresh `IndicesAccessControl` is built with `DocumentPermissions`
containing the query bytes, and stored in node-1's thread context.

### T+131ms — `thread-context-run` → `generic[T#18]`
Deprecation role check dispatch.

### T+131ms — `thread-context-run` → `generic[T#6]`
Throttled task runner dispatch for search execution.

### T+133ms — `shard-query-execute` (on `generic[T#6]`)
SearchService.executeQueryPhase() for `shardId=[dls-trace-test][0]`.

### T+135ms — `thread-context-run` → `search[T#1]`
Dispatch to search pool for shard [0]'s reader acquisition.

### T+135ms — `shard-query-execute` (on `generic[T#6]`)
SearchService.executeQueryPhase() for `shardId=[dls-trace-test][2]`.

### T+136ms — `thread-context-run` → `search[T#2]`
Dispatch to search pool for shard [2]'s reader acquisition.

### T+139ms — `dls-reader-wrap` (on `search[T#2]`)
Wraps shard [2]'s reader. `hasDls=true, dlsQuery=(department:engineering)~1`.

### T+139ms — `dls-reader-wrap` (on `search[T#1]`)
Wraps shard [0]'s reader. `hasDls=true, dlsQuery=(department:engineering)~1`.

### T+145ms — `dls-bitset-compute` (on `search[T#1]`)
Shard [0]: `cacheHit=false`, **cardinality=3, maxDoc=9**. 3 of 9 docs match.

### T+145ms — `dls-bitset-compute` (on `search[T#2]`)
Shard [2]: `cacheHit=false`, **cardinality=3, maxDoc=5**. 3 of 5 docs match.

---

## Phase 6: Query response and fetch coordination

Back on **node-0**.

### T+150ms — `inbound-handler-exec-response-handler` (`transport_worker[T#2]`)
InboundHandler.executeResponseHandler(). Node-1's query-phase response arrives.
The coordinator now has all 3 shards' query results and can compute the global
top-N for the fetch phase.

### T+157ms — `thread-context-run` → `search[T#2]`
Dispatch to search pool for fetch coordination.

### T+159ms — `transport-authn-authz` (on `search[T#2]`)
Security filter for `internal:data/read/search/fetch/coordination`.

### T+160ms — `authz-check` (on `search[T#2]`)
Authorized as `user=_system`.

### T+160ms — `authz-check` (on `search[T#2]`)
Second `_system` authz for coordination (shard-level dispatch).

### T+162ms — `transport-send-request` (on `search[T#2]`)
Sends fetch request to node-1: `action=indices:data/read/search[phase/fetch/id]`.

### T+173ms — `transport-authn-authz` (on `search[T#2]`)
Fetch coordination for the next shard group.

### T+173ms — `authz-check` × 2 (on `search[T#2]`)
`_system` authorization for fetch coordination.

### T+175ms — `transport-authn-authz` (on `search[T#2]`)
Security filter for `indices:data/read/search[phase/fetch/id]` (local fetch on
shard [1]).

### T+176ms — `authz-check` (on `search[T#2]`)
Authorized for `user=dls_trace_user`.

### T+178ms — `thread-context-run` → `search[T#3]`
Dispatch for local shard [1] fetch.

### T+178ms — `transport-authn-authz` + `authz-check` × 3 (on `search[T#2]`)
More fetch coordination + authorization for the remaining shard group.

### T+178ms — `dls-reader-wrap` (on `search[T#3]`)
Fetch phase re-acquires shard [1]'s reader with DLS wrapping.

### T+179ms — `transport-send-request` (on `search[T#2]`)
Sends second fetch request to node-1.

---

## Phase 7: Fetch phase on node-1

### T+173ms — `inbound-handler-handle-request` (node-1 `transport_worker[T#2]`)
Receives first `search[phase/fetch/id]` request (for shard [0]).

### T+176ms — `transport-authn-authz` (same thread)
Security filter on node-1.

### T+176ms — `authz-check` (same thread)
Authorizes fetch for `user=dls_trace_user`.

### T+178ms — `thread-context-run` → `search[T#3]`
Dispatch to search pool.

### T+179ms — `dls-reader-wrap` (on `search[T#3]`)
Re-wraps shard [0]'s reader for fetch. `hasDls=true`.

### T+180ms — `inbound-handler-handle-request` (`transport_worker[T#2]`)
Receives second fetch request (for shard [2]).

### T+181ms — `transport-authn-authz` + `authz-check` (same thread)
Authorize fetch for shard [2], `user=dls_trace_user`.

### T+182ms — `thread-context-run` → `search[T#4]`
Dispatch to search pool.

### T+182ms — `dls-reader-wrap` (on `search[T#4]`)
Re-wraps shard [2]'s reader for fetch. `hasDls=true`.

---

## Phase 8: Fetch responses arrive

### T+192ms — `inbound-handler-exec-response-handler` (node-0 `transport_worker[T#1]`)
Fetch response from node-1 for one shard arrives.

### T+192ms — `inbound-handler-exec-response-handler` (node-0 `transport_worker[T#2]`)
Fetch response for the other shard arrives simultaneously.
Search is now complete. Total wall time: **192ms**.

---

## Document distribution

| Shard | Node | maxDoc | DLS match (engineering) |
|-------|------|--------|------------------------|
| [0]   | test-cluster-1 | 9 | 3 |
| [1]   | test-cluster-0 | 6 | 3 |
| [2]   | test-cluster-1 | 5 | 3 |
| **Total** | | **20** | **9** |

## Instrumentation sites reference

| Site | Class | Purpose |
|------|-------|---------|
| `http-incoming` | Netty4HttpHeaderValidator | First HTTP touch on the wire |
| `http-request` | AbstractHttpServerTransport | HTTP dispatch into action pipeline |
| `http-authn` | AuthenticationService | HTTP-level authentication |
| `authz-check` | AuthorizationService | Authorization decision (coord + data node) |
| `task-manager-register-and-execute` | TaskManager | Task registration boundary |
| `transport-send-request` | OutboundHandler | Outbound transport request over the wire |
| `inbound-handler-handle-request` | InboundHandler | Inbound transport request arrival |
| `inbound-handler-exec-response-handler` | InboundHandler | Inbound transport response arrival |
| `transport-authn-authz` | ServerTransportFilter | Security filter for transport requests |
| `thread-context-run` | ThreadContext | Thread pool dispatch (context boundary) |
| `search-execute` | TransportSearchAction | Main search action entry point |
| `search-send-query` | SearchTransportService | Per-shard query fan-out |
| `shard-query-execute` | SearchService | Shard-level query phase entry |
| `dls-reader-wrap` | SecurityIndexReaderWrapper | DLS DocumentSubsetReader wrapping |
| `dls-bitset-compute` | DocumentSubsetBitsetCache | Lucene BitSet computation from DLS query |
