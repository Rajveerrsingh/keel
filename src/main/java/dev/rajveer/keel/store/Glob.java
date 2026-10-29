package dev.rajveer.keel.store;

/**
 * Redis KEYS patterns: {@code *} any run, {@code ?} one character,
 * {@code [abc]} and {@code [a-z]} classes, {@code [^a]} to negate, {@code \} to escape.
 */
public final class Glob {
    private Glob() {}

    public static boolean matches(String pattern, String text) {
        return match(pattern, 0, text, 0, 0);
    }

    private static boolean match(String pattern, int pi, String text, int ti, int stars) {
        while (pi < pattern.length()) {
            char c = pattern.charAt(pi);
            if (c == '*') {
                if (stars > 32) {
                    return false;
                }
                while (pi < pattern.length() && pattern.charAt(pi) == '*') {
                    pi++;
                }
                if (pi == pattern.length()) {
                    return true;
                }
                for (int k = ti; k <= text.length(); k++) {
                    if (match(pattern, pi, text, k, stars + 1)) {
                        return true;
                    }
                }
                return false;
            }
            if (ti >= text.length()) {
                return false;
            }
            if (c == '?') {
                pi++;
                ti++;
                continue;
            }
            if (c == '[') {
                int close = closingBracket(pattern, pi);
                if (close < 0 || !inClass(pattern, pi + 1, close, text.charAt(ti))) {
                    return false;
                }
                pi = close + 1;
                ti++;
                continue;
            }
            if (c == '\\' && pi + 1 < pattern.length()) {
                c = pattern.charAt(++pi);
            }
            if (text.charAt(ti) != c) {
                return false;
            }
            pi++;
            ti++;
        }
        return ti == text.length();
    }

    private static int closingBracket(String pattern, int open) {
        for (int i = open + 1; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '\\' && i + 1 < pattern.length()) {
                i++;
                continue;
            }
            if (pattern.charAt(i) == ']') {
                return i;
            }
        }
        return -1;
    }

    /** {@code start} is the first character inside the brackets, {@code end} is the ']'. */
    private static boolean inClass(String pattern, int start, int end, char ch) {
        if (start >= end) {
            return false;
        }
        boolean neg = pattern.charAt(start) == '^';
        if (neg) {
            start++;
        }
        boolean found = false;
        for (int i = start; i < end && !found; i++) {
            char a = pattern.charAt(i);
            if (a == '\\' && i + 1 < end) {
                a = pattern.charAt(++i);
                found = a == ch;
                continue;
            }
            if (i + 2 < end && pattern.charAt(i + 1) == '-') {
                char b = pattern.charAt(i + 2);
                found = ch >= a && ch <= b;
                i += 2;
                continue;
            }
            found = a == ch;
        }
        return neg != found;
    }
}
