package com.decisionmesh.contracts.security;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public class KeyGenerator {

    private static final String KEY_PREFIX_LIVE = "sk_live_";
    private static final String KEY_PREFIX_TEST = "sk_test_";
    private static final int KEY_LENGTH_BYTES = 32; // same as your service

    static void main(String[] args) throws Exception {

        boolean isTest = false;
        String tenantId = "bootstrap-tenant";

        byte[] randomBytes = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(randomBytes);

        String randomPart =
                Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        String prefix = isTest ? KEY_PREFIX_TEST : KEY_PREFIX_LIVE;
        String plaintextKey = prefix + randomPart;

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(plaintextKey.getBytes(StandardCharsets.UTF_8));
        String keyHash = Base64.getEncoder().encodeToString(hashBytes);

        System.out.println("========== API KEY GENERATED ==========");
        System.out.println("PLAINTEXT KEY  : " + plaintextKey);
        System.out.println("KEY HASH       : " + keyHash);
        System.out.println("KEY PREFIX     : " + plaintextKey.substring(0, 20));
        System.out.println("KEY ID (UUID)  : " + UUID.randomUUID());
        System.out.println("TENANT ID      : " + tenantId);
        System.out.println("=======================================");
    }
}
