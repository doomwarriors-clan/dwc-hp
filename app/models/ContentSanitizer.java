package models;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

public final class ContentSanitizer {
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "hr", "b", "strong", "i", "em", "u", "ul", "ol", "li", "a")
            .allowUrlProtocols("http", "https")
            .allowAttributes("href", "title").onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();

    private ContentSanitizer() { }

    public static String sanitizeHtml(String html) {
        return html == null ? "" : POLICY.sanitize(html);
    }
}
