package models;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContentSanitizerTest {
    @Test
    public void keepsSupportedFormattingAndSafeLinks() {
        String result = ContentSanitizer.sanitizeHtml("Hello<br><b>world</b> <a href=\"https://example.com\">link</a>");

        assertTrue(result.contains("<br"));
        assertTrue(result.contains("<b>world</b>"));
        assertTrue(result.contains("href=\"https://example.com\""));
    }

    @Test
    public void keepsLists() {
        String result = ContentSanitizer.sanitizeHtml("<ul><li>a<ul><li>b</li></ul></li></ul><ol><li>c</li></ol>");

        assertTrue(result.contains("<ul><li>a<ul><li>b</li></ul></li></ul>"));
        assertTrue(result.contains("<ol><li>c</li></ol>"));
    }

    @Test
    public void keepsHorizontalRules() {
        String result = ContentSanitizer.sanitizeHtml("above<hr>below");

        assertTrue(result.contains("<hr"));
        assertTrue(result.contains("below"));
    }

    @Test
    public void removesScriptsHandlersAndUnsafeUrls() {
        String result = ContentSanitizer.sanitizeHtml(
                "<script>alert(1)</script><b onclick=\"alert(1)\">text</b><a href=\"javascript:alert(1)\">bad</a>");

        assertFalse(result.contains("<script"));
        assertFalse(result.contains("onclick"));
        assertFalse(result.contains("javascript:"));
        assertTrue(result.contains("<b>text</b>"));
    }
}
