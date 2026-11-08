/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.club;

import com.sun.net.httpserver.HttpServer;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies local publishing, remote feed loading, and validation of download targets.
@NotNullByDefault
public final class ClubCatalogTest {
    /// Loads packaged defaults without requiring a live community publishing service.
    @Test
    public void bundledDefaultsWorkOffline(@TempDir Path temporary) throws IOException {
        ClubCatalog catalog = ClubCatalog.load(temporary.resolve("absent.json"));
        assertTrue(catalog.servers().isEmpty());
        assertTrue(catalog.announcements().isEmpty());
    }

    /// Resolves a locally configured HTTP feed and preserves Chinese text and line breaks.
    @Test
    public void loadsRemoteAnnouncements(@TempDir Path temporary) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = """
                {"announcements":[{"title":"社团公告","content":"第一行\\n第二行"}],"servers":[]}
                """.getBytes(StandardCharsets.UTF_8);
        server.createContext("/catalog.json", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            Path config = temporary.resolve("club.json");
            Files.writeString(config, "{\"catalogUrl\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/catalog.json\"}");
            ClubCatalog catalog = ClubCatalog.load(config);
            assertEquals("社团公告", catalog.announcements().get(0).title());
            assertEquals("第一行\n第二行", catalog.announcements().get(0).content());
            assertThrows(UnsupportedOperationException.class, () -> catalog.announcements().clear());
        } finally {
            server.stop(0);
        }
    }

    /// Rejects paths and duplicate identifiers that could target an unintended installed instance.
    @Test
    public void rejectsAmbiguousOrUnsafeServerEntries() {
        assertThrows(IllegalArgumentException.class, () ->
                new ClubCatalog.Server("../other", "常驻服", "example.org", "https://example.org/server-manifest.json"));
        assertThrows(IllegalArgumentException.class, () ->
                new ClubCatalog.Server("main", "常驻服", "example.org", "file:///C:/private.json"));
        ClubCatalog.Server server = new ClubCatalog.Server("main", "常驻服", "example.org", "https://example.org/server-manifest.json");
        assertThrows(IllegalArgumentException.class, () -> new ClubCatalog("", List.of(), List.of(server, server)));
    }

    /// Rejects incomplete remote entries instead of partially rendering a broken feed.
    @Test
    public void rejectsMalformedFeed() {
        assertThrows(RuntimeException.class, () -> JsonUtils.fromNonNullJson(
                "{\"servers\":[{\"id\":\"main\"}]}", ClubCatalog.class));
        assertThrows(RuntimeException.class, () -> JsonUtils.fromNonNullJson(
                "{\"announcements\":[{\"title\":\"社团公告\"}]}", ClubCatalog.class));
    }
}
