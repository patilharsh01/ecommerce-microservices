package com.harshalpatil.ecommerce.platform.format;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Formats amounts the Indian way: ₹1,23,456.50 (last three digits, then groups of two).
 * {@link java.text.DecimalFormat} only supports a single grouping size, so even the en-IN
 * locale renders ₹123,456.50 — hence this small formatter.
 */
public final class Rupees {

    private Rupees() {
    }

    public static String format(BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        String plain = scaled.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot);
        return (scaled.signum() < 0 ? "-₹" : "₹") + group(whole) + fraction;
    }

    private static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String lastThree = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder grouped = new StringBuilder();
        for (int end = rest.length(); end > 0; end -= 2) {
            int start = Math.max(0, end - 2);
            grouped.insert(0, rest, start, end);
            if (start > 0) {
                grouped.insert(0, ',');
            }
        }
        return grouped.append(',').append(lastThree).toString();
    }
}
