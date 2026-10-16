package dev.marregui.comando.ui.kit.desktop;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserTest {

    @Test
    void webLinksAreHttpAndHttpsInAnyLetterCase() {
        assertThat(Browser.isWebLink("https://example.com")).isTrue();
        assertThat(Browser.isWebLink("HTTP://example.com")).isTrue();
        assertThat(Browser.isWebLink("Https://example.com")).isTrue();
        assertThat(Browser.isWebLink("comando-file:/tmp/x#1")).isFalse();
        assertThat(Browser.isWebLink("file:///etc/passwd")).isFalse();
        assertThat(Browser.isWebLink("httpx://example.com")).isFalse();
    }

    @Test
    void theBrowserIsCalledOnAThreadOfItsOwnNotTheCallers() throws Exception {
        CompletableFuture<String> thread = new CompletableFuture<>();
        Browser.open("https://example.com/a", uri -> thread.complete(Thread.currentThread().getName() + " " + uri));
        assertThat(thread.get(5, TimeUnit.SECONDS)).isEqualTo("open-link " + URI.create("https://example.com/a"));
    }

    @Test
    void aLinkThatIsNotAWebLinkIsNotOpened() throws Exception {
        CompletableFuture<URI> opened = new CompletableFuture<>();
        Browser.open("file:///etc/passwd", opened::complete);
        Browser.open("https://bad host/", opened::complete);
        Browser.open("https://example.com/ok", opened::complete);
        assertThat(opened.get(5, TimeUnit.SECONDS)).isEqualTo(URI.create("https://example.com/ok"));
    }
}
