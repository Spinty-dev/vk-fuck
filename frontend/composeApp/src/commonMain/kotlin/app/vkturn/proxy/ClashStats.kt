package app.vkturn.proxy

/** Total traffic counters exposed by sing-box's `/connections` endpoint. */
data class ClashStats(val downloadTotal: Long, val uploadTotal: Long)

/**
 * Polls the `clash_api` controller for current traffic totals. Returns
 * null when the daemon is not yet reachable.
 *
 * Expected endpoint: `GET http://127.0.0.1:<port>/connections`.
 * Response shape: `{ downloadTotal: N, uploadTotal: N, connections: [...] }`.
 */
expect fun pollClashStats(port: Int): ClashStats?
