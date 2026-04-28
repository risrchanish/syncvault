package com.syncvault.aisdk.token;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        this.encoding = Encodings.newDefaultEncodingRegistry()
                .getEncoding(EncodingType.CL100K_BASE);
    }

    public int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    public boolean isWithinLimit(String text, int maxTokens) {
        return countTokens(text) <= maxTokens;
    }

    /**
     * Truncates {@code text} so that its token count is at most {@code maxTokens},
     * appending {@code "... [truncated]"} when truncation is applied.
     */
    public String truncateToLimit(String text, int maxTokens) {
        if (text == null || text.isBlank()) return text != null ? text : "";
        if (countTokens(text) <= maxTokens) return text;

        String suffix      = "... [truncated]";
        int    suffixTokens = countTokens(suffix);
        int    target       = maxTokens - suffixTokens;

        if (target <= 0) return suffix;

        // Binary search over character indices to find the longest prefix that fits
        int lo = 0, hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (countTokens(text.substring(0, mid)) <= target) lo = mid;
            else hi = mid - 1;
        }
        return text.substring(0, lo) + suffix;
    }

    /**
     * Truncates {@code text} to {@code percent} × {@code maxTokens} tokens,
     * e.g. {@code truncateToPercent(text, 0.80, 1000)} targets 800 tokens.
     */
    public String truncateToPercent(String text, double percent, int maxTokens) {
        return truncateToLimit(text, (int)(maxTokens * percent));
    }
}
