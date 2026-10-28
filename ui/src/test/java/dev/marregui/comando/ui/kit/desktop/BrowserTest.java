package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [Browser] opens web links, and only web links, in the system browser off the caller's thread.
/// - A web link is `http` or `https` in any letter case; other schemes are not.
/// - The browser is called on a thread of its own.
/// - A link that is not a web link, or not a valid URI, is not opened.
class BrowserTest {

    /// [Browser#isWebLink] accepts `http` and `https` in any case and refuses `comando-file`, `file`, a scheme that
    /// only starts with `http`, and no link at all (null), as the other link helpers do.
    @Test
    void webLinksAreHttpAndHttpsInAnyLetterCase() {
        assertThat(Browser.isWebLink("https://example.com")).isTrue();
        assertThat(Browser.isWebLink("HTTP://example.com")).isTrue();
        assertThat(Browser.isWebLink("Https://example.com")).isTrue();
        assertThat(Browser.isWebLink("comando-file:/tmp/x#1")).isFalse();
        assertThat(Browser.isWebLink("file:///etc/passwd")).isFalse();
        assertThat(Browser.isWebLink("httpx://example.com")).isFalse();
        assertThat(Browser.isWebLink(null)).isFalse();
    }

    /// [Browser#open] hands the parsed URI to the opener on a thread named `open-link`.
    @Test
    void theBrowserIsCalledOnAThreadOfItsOwnNotTheCallers() throws Exception {
        CompletableFuture<String> thread = new CompletableFuture<>();
        Browser.open("https://example.com/a", uri -> thread.complete(Thread.currentThread().getName() + " " + uri));
        assertThat(thread.get(5, TimeUnit.SECONDS)).isEqualTo("open-link " + URI.create("https://example.com/a"));
    }

    /// A `file` URI and a URI with a space in its host never reach the opener; the valid web link after them does.
    @Test
    void aLinkThatIsNotAWebLinkIsNotOpened() throws Exception {
        CompletableFuture<URI> opened = new CompletableFuture<>();
        Browser.open("file:///etc/passwd", opened::complete);
        Browser.open("https://bad host/", opened::complete);
        Browser.open("https://example.com/ok", opened::complete);
        assertThat(opened.get(5, TimeUnit.SECONDS)).isEqualTo(URI.create("https://example.com/ok"));
    }
}
