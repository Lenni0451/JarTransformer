package net.lenni0451.jartransformer.utils;

import java.util.regex.Pattern;

public class PatternUtils {

    public static Pattern globToRegex(final String glob) {
        // For a null or empty glob, return a pattern that will never match.
        // The '$a' pattern is a simple way to create a non-matching pattern.
        if (glob == null || glob.trim().isEmpty()) {
            return Pattern.compile("$a");
        }

        final StringBuilder regex = new StringBuilder("^");
        final String trimmedGlob = glob.trim();
        final char[] chars = trimmedGlob.toCharArray();
        int i = 0;

        // Special case: If the glob starts with '**/', it means it can match
        // at the root level or in any subdirectory. We translate this to an
        // optional "any characters followed by a slash".
        if (trimmedGlob.startsWith("**/") || trimmedGlob.startsWith("**\\")) {
            regex.append("(?:.*[\\\\/])?");
            i = 3; // Start processing after the '**/' or '**\'
        }

        // Process the rest of the glob string character by character.
        for (; i < chars.length; i++) {
            char c = chars[i];
            char next = (i + 1 < chars.length) ? chars[i + 1] : '\0';
            switch (c) {
                case '*' -> {
                    // Check for the '**' directory wildcard.
                    if (next == '*') {
                        i++; // Consume the second '*'
                        // In the middle or at the end of a pattern, '**' matches any character,
                        // including slashes. This is a simple and powerful translation.
                        regex.append(".*");
                    } else {
                        // A single '*' matches any sequence of characters except a slash.
                        regex.append("[^\\\\/]*");
                    }
                }

                // '?' matches a single character except a slash.
                case '?' -> regex.append("[^\\\\/]");

                // Normalize slashes to a character class matching either.
                case '/', '\\' -> regex.append("[\\\\/]");

                // Escape regex metacharacters to treat them as literals.
                case '.', '(', ')', '[', ']', '{', '}', '|', '^', '$', '+' -> regex.append('\\').append(c);

                // Append any other character as a literal.
                default -> regex.append(c);
            }
        }

        // Anchor the regex to the end of the string for a full match.
        regex.append('$');
        return Pattern.compile(regex.toString());
    }

}
