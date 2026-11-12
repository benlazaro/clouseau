package com.droidenx.clouseau.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JSON toggle shows only on messages that carry real JSON, written as JSON is. The
 * formatter and the highlighter both decide with {@code findJsonStart}.
 */
class JsonDetectionTest {

    @Test
    void jsonAfterATextPrefixIsFound() {
        String msg = "Payment gateway response: {\"status\":\"AUTHORISED\",\"retried\":false,\"ms\":561}";
        assertThat(JsonDetection.findJsonStart(msg)).isEqualTo(msg.indexOf('{'));
    }

    @Test
    void anArrayOfQuotedStringsIsJson() {
        assertThat(JsonDetection.findJsonStart("Roles: [\"USER\",\"MANAGER\"]")).isEqualTo(7);
    }

    @Test
    void bracketedWordsAreNotJson() {
        for (String msg : new String[] {
                "Active profiles: [production, us-east-1]",
                "Order {id=42, total=162.47} created",
                "Retrying [attempt 2/3]",
                "Config: {'single': 'quotes'}",
                "Values: [1, 2,]",
                "Payload: {\"a\":1} and more text",
                "Entering Bootstrap.init(), args=[]",
                "Headers: {}"}) {
            assertThat(JsonDetection.findJsonStart(msg)).as(msg).isEqualTo(-1);
        }
    }
}
