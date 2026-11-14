package com.nexodb.util;

import java.security.SecureRandom;

/** Generates secrets locally; no persistence or implicit clipboard access. */
public final class PasswordGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String[] GROUPS = {"ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#$%+-_=?:"};
    private PasswordGenerator() {}
    public static char[] generate(int length, boolean symbols) {
        if (length < 12 || length > 64) throw new IllegalArgumentException("12–64");
        int groups = symbols ? 4 : 3;
        String alphabet = String.join("", java.util.Arrays.copyOf(GROUPS, groups));
        char[] result = new char[length];
        for (int i = 0; i < groups; i++) result[i] = pick(GROUPS[i]);
        for (int i = groups; i < length; i++) result[i] = pick(alphabet);
        for (int i = result.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1); char c = result[i]; result[i] = result[j]; result[j] = c;
        }
        return result;
    }
    private static char pick(String alphabet) { return alphabet.charAt(RANDOM.nextInt(alphabet.length())); }
}
