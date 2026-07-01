package com.decisionmesh.contracts.security.guard;

import com.decisionmesh.domain.intent.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.decisionmesh.contracts.security.service.PiiMaskingGuardService;

class PiiMaskingGuardServiceTest {

    PiiMaskingGuardService guard;

    @BeforeEach
    void setUp() {
        guard = new PiiMaskingGuardService();
        guard.mapper = new ObjectMapper();
    }

    @Test
    void detectsAadhaarNumber() {
        var result = guard.scanText("Customer Aadhaar is 1234 5678 9012, please verify.", "test-1");

        assertTrue(result.piiDetected());
        assertTrue(result.hasHighRisk());
        assertEquals(1, result.matches().size());
        assertEquals(com.decisionmesh.contracts.security.service.PiiMaskingGuardService.PiiCategory.AADHAAR, result.matches().get(0).category());
        assertFalse(result.maskedText().contains("1234 5678 9012"));
        assertTrue(result.maskedText().contains("[REDACTED-AADHAAR]"));
    }

    @Test
    void detectsPanNumber() {
        var result = guard.scanText("PAN: ABCPR1234F submitted for KYC.", "test-2");

        assertTrue(result.piiDetected());
        assertTrue(result.hasHighRisk());
        assertEquals(PiiMaskingGuardService.PiiCategory.PAN, result.matches().get(0).category());
        assertFalse(result.maskedText().contains("ABCPR1234F"));
    }

    @Test
    void detectsIndianMobileNumber() {
        var result = guard.scanText(
                "Contact customer at +91 9876543210 for verification.", "test-3");

        assertTrue(result.piiDetected());
        assertFalse(result.hasHighRisk());
        // Both BANK_ACCOUNT and PHONE may match — confirm PHONE is present
        boolean matchedPhone = result.matches().stream()
                .anyMatch(m -> m.category() == PiiMaskingGuardService.PiiCategory.PHONE);
        assertTrue(matchedPhone, "Expected PHONE category in matches");
    }

    @Test
    void doesNotFalsePositiveOnNonMobileNumbers() {
        var result = guard.scanText("Reference number 1234567890 for this ticket.", "test-4");

        boolean matchedPhone = result.matches().stream()
                .anyMatch(m -> m.category() == PiiMaskingGuardService.PiiCategory.PHONE);
        assertFalse(matchedPhone);
    }

    @Test
    void detectsEmailAddress() {
        var result = guard.scanText("Send statement to ramesh.sharma@example.com please.", "test-5");

        assertTrue(result.piiDetected());
        assertEquals(PiiMaskingGuardService.PiiCategory.EMAIL, result.matches().get(0).category());
        assertFalse(result.maskedText().contains("ramesh.sharma@example.com"));
    }



    @Test
    void detectsIfscCode() {
        var result = guard.scanText("Transfer to IFSC HDFC0001234 immediately.", "test-6");

        assertTrue(result.piiDetected());
        assertEquals(PiiMaskingGuardService.PiiCategory.IFSC, result.matches().get(0).category());
    }

    @Test
    void returnsCleanResultWhenNoPiiPresent() {
        var result = guard.scanText("Assess credit risk for this loan application based on income.", "test-7");

        assertTrue(result.isClean());
        assertFalse(result.piiDetected());
        assertFalse(result.hasHighRisk());
        assertNull(result.maskedText());
    }

    @Test
    void handlesNullAndBlankText() {
        assertTrue(guard.scanText(null, "test-8").isClean());
        assertTrue(guard.scanText("", "test-9").isClean());
        assertTrue(guard.scanText("   ", "test-10").isClean());
    }

    @Test
    void detectsMultiplePiiCategoriesInSamePayload() {
        String text = "Applicant Ramesh Sharma, PAN ABCPR1234F, phone +91 9876543210, "
                + "email ramesh@example.com, requesting loan approval.";

        var result = guard.scanText(text, "test-11");

        assertTrue(result.piiDetected());
        assertTrue(result.hasHighRisk());
        assertTrue(result.matches().size() >= 3);

        String masked = result.maskedText();
        assertFalse(masked.contains("ABCPR1234F"));
        assertFalse(masked.contains("9876543210"));
        assertFalse(masked.contains("ramesh@example.com"));
    }

    @Test
    void masksPiiInsideNestedJsonObjective() {
        // Use scanText with raw JSON — avoids constructing IntentObjective
        // and exercises the same tree-walking code path via JSON string input
        String json = """
            {
              "description": "Verify customer KYC",
              "customer": {
                "name": "Ramesh Sharma",
                "pan": "ABCPR1234F",
                "phone": "9876543210"
              }
            }
            """;

        var result = guard.scanText(json, "test-nested-json");

        assertTrue(result.piiDetected());
        assertTrue(result.hasHighRisk());

        String masked = result.maskedText();
        assertFalse(masked.contains("ABCPR1234F"));
        assertFalse(masked.contains("9876543210"));
        assertTrue(masked.contains("Verify customer KYC"));
    }

    @Test
    void returnsCleanResultForNullObjective() {
        Intent intent = Mockito.mock(Intent.class);
        Mockito.when(intent.getId()).thenReturn(UUID.randomUUID());
        Mockito.when(intent.getObjective()).thenReturn(null);

        var result = guard.scanAndMask(intent);

        assertTrue(result.isClean());
        assertFalse(result.piiDetected());
    }

    @Test
    void redactedSampleInMatchNeverContainsFullOriginalValue() {
        var result = guard.scanText("PAN: ABCPR1234F", "test-12");

        String sample = result.matches().get(0).redactedSample();
        assertFalse(sample.equals("ABCPR1234F"));
        assertTrue(sample.contains("***"));
    }
}
