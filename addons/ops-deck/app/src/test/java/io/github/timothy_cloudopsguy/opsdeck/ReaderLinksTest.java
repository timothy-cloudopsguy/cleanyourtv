package io.github.timothy_cloudopsguy.opsdeck;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReaderLinksTest {
    @Test
    public void onlyHttpsPagesLoad() {
        assertTrue(ReaderActivity.allowed("https://example.com/story"));
        assertTrue(ReaderActivity.allowed("HTTPS://example.com"));
        for (String link : new String[]{null, "", "https://", "http://example.com", "intent://x#Intent;end",
                "market://details?id=x", "file:///data/x", "javascript:alert(1)", "content://x"}) {
            assertFalse(String.valueOf(link), ReaderActivity.allowed(link));
        }
    }
}
