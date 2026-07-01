package com.decisionmesh.contracts.security.service;

import com.decisionmesh.domain.intent.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans intent payloads for PII (Personally Identifiable Information) before
 * execution, and produces a masked copy of the payload safe to send to an
 * external LLM provider.
 *
 * Module:  decisionmesh-security
 * Package: com.decisionmesh.contracts.security.guard
 *
 * Sibling to PromptInjectionGuardService — same lifecycle point (PLANNING
 * phase, before any adapter receives the request), same Intent contract
 * (only intent.getObjective() and intent.getId() are read).
 *
 * Behaviour:
 *   - Detects Aadhaar, PAN, phone numbers, email addresses, bank account
 *     numbers, IFSC codes, and credit/debit card numbers via regex.
 *   - Returns a MaskingResult containing the original matches found AND a
 *     masked copy of the scanned text/JSON with each match redacted.
 *   - Does NOT decide DENY vs ALLOW — that decision belongs to the policy
 *     layer that calls this guard (mirrors PromptInjectionGuardService).
 *     Callers typically: always use maskedText() for the outbound LLM call,
 *     and additionally escalate to human review when result.hasHighRisk()
 *     is true (e.g. Aadhaar/PAN/card number detected).
 *
 * Regex-only by design for v1 — no external NER/ML dependency, deterministic,
 * auditable, and fast enough to run inline in the planning phase.
 */
@ApplicationScoped
public class PiiMaskingGuardService {

    private static final Logger LOG = Logger.getLogger(PiiMaskingGuardService.class);

    @Inject
    public ObjectMapper mapper;

    // ── PII patterns ────────────────────────────────────────────────────────
    // Ordered so higher-specificity patterns are checked before looser ones
    // where overlap is possible (e.g. PAN before generic alphanumeric ID).

    private static final List<PiiPattern> PATTERNS = List.of(

            // Aadhaar — 12 digits in groups of 4 (most specific numeric pattern first)
            new PiiPattern(
                    Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"),
                    PiiCategory.AADHAAR, "HIGH", "[REDACTED-AADHAAR]"
            ),

            // PAN — 5 letters, 4 digits, 1 letter
            new PiiPattern(
                    Pattern.compile("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b"),
                    PiiCategory.PAN, "HIGH", "[REDACTED-PAN]"
            ),

            // Credit / debit card numbers — 13-19 digits
            new PiiPattern(
                    Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"),
                    PiiCategory.CARD_NUMBER, "HIGH", "[REDACTED-CARD]"
            ),

            // IFSC code — must come before BANK_ACCOUNT (alphanumeric, not caught by digit pattern)
            new PiiPattern(
                    Pattern.compile("\\b[A-Z]{4}0[A-Z0-9]{6}\\b"),
                    PiiCategory.IFSC, "MEDIUM", "[REDACTED-IFSC]"
            ),

            // Indian mobile numbers — MUST come before BANK_ACCOUNT
            // 10-digit mobiles starting 6-9 would otherwise be caught by the
            // broader \d{9,18} bank account pattern first
            new PiiPattern(
                    Pattern.compile("\\b(?:\\+?91[\\s-]?)?[6-9]\\d{9}\\b"),
                    PiiCategory.PHONE, "MEDIUM", "[REDACTED-PHONE]"
            ),

            // Bank account numbers — broad 9-18 digit pattern, runs AFTER
            // more specific numeric patterns (Aadhaar=12, Phone=10, Card=13-19)
            new PiiPattern(
                    Pattern.compile("\\b\\d{9,18}\\b"),
                    PiiCategory.BANK_ACCOUNT, "MEDIUM", "[REDACTED-ACCOUNT]"
            ),

            // Email address
            new PiiPattern(
                    Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
                    PiiCategory.EMAIL, "LOW", "[REDACTED-EMAIL]"
            )
    );

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Scan an intent's objective payload for PII and return a masked copy
     * ready to be substituted into the plan step before adapter execution.
     * Only reads intent.getObjective() and intent.getId() — both confirmed
     * to exist on the Intent contract.
     */
    public MaskingResult scanAndMask(Intent intent) {
        if (intent.getObjective() == null) {
            return MaskingResult.clean();
        }

        try {
            JsonNode node = mapper.valueToTree(intent.getObjective());
            List<Match> matches = new ArrayList<>();
            JsonNode maskedNode = maskNode(node, intent.getId().toString(), matches);

            if (matches.isEmpty()) {
                return MaskingResult.clean();
            }

            return new MaskingResult(matches, maskedNode, true);

        } catch (Exception e) {
            LOG.warnf("[PiiGuard] Failed to tree-walk objective for intent %s — falling back to text scan: %s",
                    intent.getId(), e.getMessage());
            try {
                return scanText(
                        mapper.writeValueAsString(intent.getObjective()),
                        intent.getId().toString()
                );
            } catch (Exception ex) {
                LOG.warnf("[PiiGuard] Fallback text scan also failed for intent %s — returning clean",
                        intent.getId());
                return MaskingResult.clean();
            }
        }
    }

    /**
     * Scan and mask a raw text string. Used as a fallback when the objective
     * cannot be parsed as structured JSON, and available directly for any
     * caller that has a plain string (e.g. prompt templates, free-text fields).
     */
    public MaskingResult scanText(String text, String contextId) {
        if (text == null || text.isBlank()) {
            return MaskingResult.clean();
        }

        List<Match> matches = new ArrayList<>();
        String masked = maskString(text, contextId, matches);

        if (matches.isEmpty()) {
            return MaskingResult.clean();
        }

        return new MaskingResult(matches, TextNode.valueOf(masked), true);
    }

    // ── Tree-walking masking (preserves JSON structure) ─────────────────────

    private JsonNode maskNode(JsonNode node, String contextId, List<Match> matches) {
        if (node.isObject()) {
            ObjectNode out = mapper.createObjectNode();
            node.fields().forEachRemaining(entry ->
                    out.set(entry.getKey(), maskNode(entry.getValue(), contextId, matches)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            node.forEach(child -> out.add(maskNode(child, contextId, matches)));
            return out;
        }
        if (node.isTextual()) {
            String masked = maskString(node.asText(), contextId, matches);
            return TextNode.valueOf(masked);
        }
        // Numbers, booleans, null — passed through unchanged.
        // Note: numeric Aadhaar/account/card fields stored as JSON numbers
        // (not strings) will not be caught by regex scanning of text nodes.
        // If your intent payloads ever serialise these as numeric fields,
        // add explicit field-name-based masking (see isSensitiveFieldName below).
        return node;
    }

    // ── String-level masking ─────────────────────────────────────────────────

    private String maskString(String text, String contextId, List<Match> matches) {
        String result = text;

        for (PiiPattern p : PATTERNS) {
            Matcher m = p.regex().matcher(result);
            StringBuilder sb = new StringBuilder();
            int last = 0;
            boolean foundInThisPass = false;

            while (m.find()) {
                foundInThisPass = true;
                String original = m.group();

                matches.add(new Match(p.category(), p.severity(), redactForLog(original)));

                LOG.infof("[PiiGuard] %s detected in intent %s — redacted (severity=%s)",
                        p.category(), contextId, p.severity());

                sb.append(result, last, m.start());
                sb.append(p.replacement());
                last = m.end();
            }
            sb.append(result.substring(last));

            if (foundInThisPass) {
                result = sb.toString();
            }
        }

        return result;
    }

    /** Never log the actual PII value — log a shape-preserving redaction only. */
    private String redactForLog(String original) {
        if (original.length() <= 4) return "***";
        return original.substring(0, 2) + "***" + original.substring(original.length() - 2);
    }

    // ── Optional: field-name-based masking for numeric PII fields ───────────
    // Call this explicitly if your Objective schema stores Aadhaar/account
    // numbers as JSON numbers rather than strings, where regex text scanning
    // cannot reach them.

    private static final List<String> SENSITIVE_NUMERIC_FIELD_NAMES = List.of(
            "aadhaar", "aadhaarnumber", "pannumber", "accountnumber",
            "cardnumber", "mobilenumber", "phonenumber"
    );

    public boolean isSensitiveFieldName(String fieldName) {
        String lower = fieldName.toLowerCase();
        return SENSITIVE_NUMERIC_FIELD_NAMES.stream().anyMatch(lower::contains);
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public enum PiiCategory {
        AADHAAR, PAN, CARD_NUMBER, BANK_ACCOUNT, IFSC, PHONE, EMAIL
    }

    public record MaskingResult(
            List<Match> matches,
            JsonNode maskedPayload,
            boolean piiDetected
    ) {
        public boolean isClean() { return matches.isEmpty(); }

        /** True if any HIGH severity category (Aadhaar, PAN, card number) was found. */
        public boolean hasHighRisk() {
            return matches.stream().anyMatch(mt -> "HIGH".equals(mt.severity()));
        }

        /** Convenience accessor — masked payload as a JSON string, ready for the LLM call. */
        public String maskedText() {
            return maskedPayload == null ? null : maskedPayload.toString();
        }

        public static MaskingResult clean() {
            return new MaskingResult(List.of(), null, false);
        }
    }

    public record Match(
            PiiCategory category,
            String severity,
            String redactedSample
    ) {}

    private record PiiPattern(
            Pattern regex,
            PiiCategory category,
            String severity,
            String replacement
    ) {}
}
