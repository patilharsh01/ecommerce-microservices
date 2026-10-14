package com.harshalpatil.ecommerce.platform.format;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RupeesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0          | ₹0.00",
            "7.5        | ₹7.50",
            "999        | ₹999.00",
            "1000       | ₹1,000.00",
            "99999.99   | ₹99,999.99",
            "123456.5   | ₹1,23,456.50",
            "10000000   | ₹1,00,00,000.00",
            "-2500      | -₹2,500.00",
            "1.005      | ₹1.01"
    })
    void usesIndianDigitGrouping(String amount, String expected) {
        assertThat(Rupees.format(new BigDecimal(amount))).isEqualTo(expected);
    }
}
