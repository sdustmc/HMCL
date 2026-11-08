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

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.GameInstanceID;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/// The community's announcement feed and server directory.
///
/// @param catalogUrl optional remote JSON feed configured by the local distribution
/// @param announcements announcements in display order
/// @param servers servers in display order; IDs must be unique
@NotNullByDefault
public record ClubCatalog(String catalogUrl, @Unmodifiable List<Announcement> announcements,
                          @Unmodifiable List<Server> servers) {
    /// Normalizes optional fields and rejects ambiguous server IDs before any installation.
    public ClubCatalog(@Nullable String catalogUrl, @Nullable List<Announcement> announcements,
                       @Nullable List<Server> servers) {
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl;
        if (!this.catalogUrl.isBlank()) {
            requireHttpUrl(this.catalogUrl);
        }
        this.announcements = announcements == null ? List.of() : List.copyOf(announcements);
        this.servers = servers == null ? List.of() : List.copyOf(servers);
        HashSet<String> ids = new HashSet<>();
        for (Server server : this.servers) {
            if (!ids.add(server.id())) {
                throw new IllegalArgumentException("Duplicate community server ID: " + server.id());
            }
        }
    }

    /// Loads the configured feed off the JavaFX thread; no network access occurs without a URL.
    public static ClubCatalog load() throws IOException {
        return load(Path.of(System.getProperty("hmcl.club.config",
                Metadata.HMCL_LOCAL_HOME.resolve("club.json").toString())));
    }

    /// Loads a local override or bundled defaults, then resolves its optional remote feed.
    public static ClubCatalog load(Path configuration) throws IOException {
        String json;
        if (Files.exists(configuration)) {
            json = Files.readString(configuration);
        } else {
            try (@Nullable InputStream input = ClubCatalog.class.getResourceAsStream("/assets/club.json")) {
                if (input == null) {
                    throw new IOException("Missing bundled community configuration");
                }
                json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        ClubCatalog local = JsonUtils.fromNonNullJson(json, ClubCatalog.class);
        return local.catalogUrl().isBlank() ? local
                : JsonUtils.fromNonNullJson(HttpRequest.GET(local.catalogUrl()).getString(), ClubCatalog.class);
    }

    /// Rejects non-web addresses, missing hosts, and embedded credentials.
    public static void requireHttpUrl(String value) {
        URI uri = URI.create(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Expected an HTTP(S) URL: " + value);
        }
    }

    /// A plain-text announcement; line breaks are preserved without interpreting remote markup.
    ///
    /// @param title nonempty announcement title
    /// @param content nonempty announcement body
    @NotNullByDefault
    public record Announcement(String title, String content) {
        /// Rejects incomplete announcements from malformed feeds.
        public Announcement {
            if (title == null || title.isBlank() || content == null || content.isBlank()) {
                throw new IllegalArgumentException("Announcement title and content are required");
            }
        }
    }

    /// A stable community server entry pointing to an HMCL server-modpack manifest.
    ///
    /// @param id stable lowercase identifier, used to reserve a community instance name
    /// @param name server display name
    /// @param address Minecraft host and optional port for quick play
    /// @param manifestUrl remote HMCL server-manifest.json URL
    @NotNullByDefault
    public record Server(String id, String name, String address, String manifestUrl) {
        /// Validates feed data before it can name an instance or start a download.
        public Server {
            if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,47}")
                    || name == null || name.isBlank() || address == null || address.isBlank()
                    || manifestUrl == null || manifestUrl.isBlank()) {
                throw new IllegalArgumentException("Invalid community server entry");
            }
            requireHttpUrl(manifestUrl);
        }

        /// Returns a reserved instance ID independent of the server's display name.
        public GameInstanceID instanceId() {
            return new GameInstanceID("sdustmc-" + id);
        }
    }
}
