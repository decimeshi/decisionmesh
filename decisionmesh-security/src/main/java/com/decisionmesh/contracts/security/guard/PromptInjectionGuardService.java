package com.decisionmesh.contracts.security.guard;

import com.decisionmesh.domain.intent.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans intent payloads for prompt injection patterns before execution.
 *
 * Module:  decisionmesh-security
 * Package: com.decisionmesh.contracts.security.guard
 *
 * Called in PLANNING phase — before any adapter receives the request.
 * Uses only Intent methods confirmed to exist:
 *   intent.getId()         — UUID
 *   intent.getObjective()  — Object (serialised to JSON for scanning)
 */
@ApplicationScoped
public class PromptInjectionGuardService {

    private static final Logger LOG = Logger.getLogger(PromptInjectionGuardService.class);

    @Inject
    ObjectMapper mapper;

    // ── Injection patterns ordered by severity ────────────────────────────────

    private static final List<InjectionPattern> PATTERNS = List.of(

            // Direct instruction override
            new InjectionPattern(
                    Pattern.compile("ignore (all )?(previous|prior|above|earlier) (instructions?|prompts?|context|rules?|constraints?)", Pattern.CASE_INSENSITIVE),
                    0.95, "CRITICAL", "Direct instruction override"
            ),
            new InjectionPattern(
                    Pattern.compile("disregard (all )?(previous|prior|above|your) (instructions?|prompts?|rules?|guidelines?)", Pattern.CASE_INSENSITIVE),
                    0.95, "CRITICAL", "Direct instruction disregard"
            ),
            new InjectionPattern(
                    Pattern.compile("you (are|will be|must be|should be) now (a |an )?(different|new|another|uncensored|unrestricted)", Pattern.CASE_INSENSITIVE),
                    0.90, "CRITICAL", "Identity override"
            ),

            // Jailbreak
            new InjectionPattern(
                    Pattern.compile("\\bDAN\\b|do anything now|jailbreak|unrestricted mode|developer mode|god mode", Pattern.CASE_INSENSITIVE),
                    0.90, "HIGH", "Jailbreak attempt"
            ),
            new InjectionPattern(
                    Pattern.compile("pretend (you are|you're|to be) (not |no longer )?(bound by|restricted|limited|constrained)", Pattern.CASE_INSENSITIVE),
                    0.85, "HIGH", "Constraint bypass"
            ),
            new InjectionPattern(
                    Pattern.compile("act as if (you have no|without) (restrictions?|limits?|guidelines?|safety|filters?)", Pattern.CASE_INSENSITIVE),
                    0.85, "HIGH", "Safety bypass"
            ),

            // System prompt extraction
            new InjectionPattern(
                    Pattern.compile("(reveal|show|print|output|repeat|tell me|what (is|are|was)) (your )?(system prompt|initial prompt|original instructions?|base instructions?)", Pattern.CASE_INSENSITIVE),
                    0.80, "HIGH", "System prompt extraction"
            ),
            new InjectionPattern(
                    Pattern.compile("what (were|are) (you|your) (told|instructed|programmed|trained) to", Pattern.CASE_INSENSITIVE),
                    0.70, "MEDIUM", "Instruction probing"
            ),

            // Persona hijacking
            new InjectionPattern(
                    Pattern.compile("from now on (you are|act as|behave as|respond as)", Pattern.CASE_INSENSITIVE),
                    0.75, "HIGH", "Persona hijacking"
            ),
            new InjectionPattern(
                    Pattern.compile("your (new |true |real )?(role|purpose|goal|objective|mission|name) is", Pattern.CASE_INSENSITIVE),
                    0.70, "MEDIUM", "Role redefinition"
            ),

            // Token delimiter injection
            new InjectionPattern(
                    Pattern.compile("(<\\|im_start\\|>|<\\|im_end\\|>|\\[INST\\]|\\[/INST\\]|<<SYS>>|<</SYS>>)", Pattern.CASE_INSENSITIVE),
                    0.90, "HIGH", "Token delimiter injection"
            ),
            new InjectionPattern(
                    Pattern.compile("###\\s*(instruction|system|human|assistant|user)\\s*:?\\s*\\n", Pattern.CASE_INSENSITIVE),
                    0.85, "HIGH", "Markdown role injection"
            ),

            // Task-masked injection
            new InjectionPattern(
                    Pattern.compile("(summarize|explain|continue|complete)[:\\s].{0,100}(ignore|forget|disregard) (the|your|all) (above|previous|prior|original)", Pattern.CASE_INSENSITIVE),
                    0.70, "MEDIUM", "Task-masked injection"
            ),

            // Policy circumvention
            new InjectionPattern(
                    Pattern.compile("(bypass|override|circumvent|evade) (the |your )?(safety|content|filter|restriction|policy|rule|guideline)", Pattern.CASE_INSENSITIVE),
                    0.60, "LOW", "Policy circumvention"
            )
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Scan an intent's objective payload for injection patterns.
     * Only reads intent.getObjective() and intent.getId() — both confirmed to exist.
     */
    public ScanResult scan(Intent intent) {
        String text = extractText(intent);
        return scanText(text, intent.getId().toString());
    }

    public ScanResult scanText(String text, String contextId) {
        if (text == null || text.isBlank()) {
            return ScanResult.clean();
        }

        List<Match> matches = new ArrayList<>();
        double maxRisk = 0.0;

        for (InjectionPattern ip : PATTERNS) {
            Matcher m = ip.regex().matcher(text);
            if (m.find()) {
                String matched = m.group();
                matches.add(new Match(ip.severity(), ip.description(), matched, ip.riskScore()));
                if (ip.riskScore() > maxRisk) {
                    maxRisk = ip.riskScore();
                }
                LOG.warnf("[InjectionGuard] Pattern matched in intent %s — %s (risk=%.2f): '%s'",
                        contextId,
                        ip.description(),
                        ip.riskScore(),
                        matched.length() > 80 ? matched.substring(0, 80) + "…" : matched);
            }
        }

        if (matches.isEmpty()) {
            return ScanResult.clean();
        }

        return new ScanResult(maxRisk, matches, classify(maxRisk));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts all scannable text from the intent.
     * Uses only intent.getObjective() — the only confirmed text-bearing field.
     * Serialises it to JSON so nested string values are also scanned.
     */
    private String extractText(Intent intent) {
        if (intent.getObjective() == null) {
            return "";
        }
        try {
            // Serialise objective to JSON — catches strings nested inside maps/lists
            return mapper.writeValueAsString(intent.getObjective());
        } catch (Exception e) {
            // Fall back to toString() if serialisation fails
            LOG.debugf("Objective serialisation failed for intent %s — using toString()", intent.getId());
            return intent.getObjective().toString();
        }
    }

    private static String classify(double risk) {
        if (risk >= 0.90) return "CRITICAL";
        if (risk >= 0.75) return "HIGH";
        if (risk >= 0.55) return "MEDIUM";
        if (risk >= 0.30) return "LOW";
        return "INFO";
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record ScanResult(
            double injectionRisk,
            List<Match> matches,
            String severity
    ) {
        public boolean isClean()      { return matches.isEmpty(); }
        public boolean isCritical()   { return injectionRisk >= 0.90; }
        public boolean isHighRisk()   { return injectionRisk >= 0.75; }

        public static ScanResult clean() {
            return new ScanResult(0.0, List.of(), "NONE");
        }
    }

    public record Match(
            String severity,
            String description,
            String matchedText,
            double riskScore
    ) {}

    private record InjectionPattern(
            Pattern regex,
            double  riskScore,
            String  severity,
            String  description
    ) {}
}
