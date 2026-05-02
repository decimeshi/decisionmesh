package com.decisionmesh.llm;


/**
 * Marker interface for LLM adapters that support provider-level prompt caching.
 *
 * Supported providers:
 *   ANTHROPIC — cache_control: {type: ephemeral} on system prompt blocks
 *               Beta header: anthropic-beta: prompt-caching-2024-07-31
 *               Cache TTL:   5 minutes (ephemeral)
 *               Savings:     cache reads cost 10% of standard input price (~90% cheaper)
 *
 *   OPENAI    — Not yet supported (no equivalent API as of 2026)
 *   GOOGLE    — Not yet supported
 *
 * Adapters that implement this interface MUST:
 *   1. Add cache_control to the system prompt block in their request body
 *   2. Add the required beta header to the HTTP request
 *   3. Parse cache_creation_input_tokens + cache_read_input_tokens from response usage
 *   4. Return a CacheUsage record inside their ExecutionRecord metadata
 */
public interface CacheableAdapter {

    /**
     * Returns true if caching is enabled for this adapter instance.
     * Controlled by config: llm.cache.enabled (default true for Anthropic).
     */
    boolean isCacheEnabled();

    /**
     * Provider-specific cache key prefix for metrics tagging.
     * e.g. "anthropic", "openai"
     */
    String cacheProvider();
}
