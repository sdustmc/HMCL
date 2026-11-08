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
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.DownloadProvider;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.modpack.ModpackConfiguration;
import org.jackhuang.hmcl.modpack.server.ServerModpackManifest;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.util.CacheRepository;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises real file updates and rollback against a loopback modpack publishing server.
@NotNullByDefault
public final class ClubModpacksTest {
    /// Downloads changed files, removes retired files, preserves user edits, and rolls back failure.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void updatesFilesOrRollsBack(boolean failDownload, @TempDir Path temporary) throws IOException {
        CacheRepository originalCache = CacheRepository.getInstance();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            TestRepository repository = new TestRepository(temporary.resolve("game"));
            GameInstanceID id = new GameInstanceID("sdustmc-test");
            try (var draft = repository.openDraft()) {
                draft.put(new GameInstanceManifest(id).withMainClass("example.Main"));
                draft.commit();
            }
            DefaultGameInstance instance = repository.getInstance(id);
            Path root = instance.getInstanceRoot();
            Path changed = root.resolve("managed.txt");
            Path removed = root.resolve("retired.txt");
            Path personal = root.resolve("personal.txt");
            Path save = root.resolve("saves/world/data.txt");
            Files.createDirectories(save.getParent());
            Files.writeString(changed, "old");
            Files.writeString(removed, "retired");
            Files.writeString(personal, "my edits");
            Files.writeString(save, "my world");
            @Unmodifiable List<ModpackConfiguration.FileInformation> oldFiles = List.of(
                    info("managed.txt", "old"), info("retired.txt", "retired"), info("personal.txt", "original"));
            ServerModpackManifest previous = manifest(base, "1", oldFiles);
            ModpackConfiguration<ServerModpackManifest> installed = new ModpackConfiguration<>(previous, "Server", "社团测试包", "1", oldFiles);
            JsonUtils.writeToJsonFile(instance.getModpackConfigurationFile(), installed);
            ServerModpackManifest current = manifest(base, "2", List.of(info("managed.txt", "new"), info("personal.txt", "publisher edit")));
            byte[] json = JsonUtils.GSON.toJson(current).getBytes(StandardCharsets.UTF_8);
            server.createContext("/server-manifest.json", exchange -> {
                exchange.sendResponseHeaders(200, json.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(json);
                }
            });
            server.createContext("/overrides/managed.txt", exchange -> {
                byte[] bytes = "new".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(failDownload ? 404 : 200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            DefaultCacheRepository cache = new DefaultCacheRepository(temporary.resolve("cache"));
            CacheRepository.setInstance(cache);
            DefaultDependencyManager dependency = new DefaultDependencyManager(repository, new TestDownloadProvider(cache), cache);
            TaskExecutor executor = ClubModpacks.update(dependency, instance, installed).executor();
            boolean success = executor.test();
            if (!success && !failDownload) {
                throw new AssertionError("The update unexpectedly failed", executor.getException());
            }
            assertEquals(!failDownload, success);
            assertEquals(failDownload ? "old" : "new", Files.readString(changed));
            assertEquals(failDownload, Files.exists(removed));
            assertEquals("my edits", Files.readString(personal));
            assertEquals("my world", Files.readString(save));
            ModpackConfiguration<?> recorded = JsonUtils.fromNonNullJson(
                    Files.readString(instance.getModpackConfigurationFile()), ModpackConfiguration.class);
            assertEquals(failDownload ? "1" : "2", recorded.getVersion());
            assertTrue(Files.isDirectory(repository.getLayout().getBaseDirectory().resolve("backup")));
        } finally {
            CacheRepository.setInstance(originalCache);
            server.stop(0);
        }
    }

    /// Refuses to turn a foreign instance into a community pack merely because its ID collides.
    @Test
    public void rejectsForeignInstance() {
        ServerModpackManifest manifest = manifest("https://example.org", "2", List.of());
        ModpackConfiguration<ServerModpackManifest> foreign = new ModpackConfiguration<>(manifest, "Modrinth", "other", "1", List.of());
        assertThrows(IOException.class, () -> ClubModpacks.needsUpdate(foreign, manifest));
    }

    /// Does not accept a local-only export as a remotely installable publication.
    @Test
    public void rejectsMissingDownloadSource() {
        assertThrows(IOException.class, () -> ClubModpacks.validateManifest(manifest("", "2", List.of())));
        assertThrows(IOException.class, () -> ClubModpacks.validateManifest(manifest("file:///C:/private", "2", List.of())));
    }

    /// Builds a file entry with the SHA-1 expected by HMCL's download validation.
    private static ModpackConfiguration.FileInformation info(String path, String content) {
        return new ModpackConfiguration.FileInformation(path, DigestUtils.digestToString("SHA-1", content.getBytes(StandardCharsets.UTF_8)));
    }

    /// Creates a file-only server pack so tests never download Minecraft or loader binaries.
    private static ServerModpackManifest manifest(String base, String version, List<ModpackConfiguration.FileInformation> files) {
        return new ServerModpackManifest("社团测试包", "圣悦灵社", version, "", base, files, List.of());
    }

    /// Keeps file-download caching isolated from the user's Minecraft directory.
    @NotNullByDefault
    private static final class TestDownloadProvider extends DownloadProvider {
        /// The cache created for this test's temporary directory.
        private final DefaultCacheRepository cache;

        /// Creates a provider using the supplied cache.
        private TestDownloadProvider(DefaultCacheRepository cache) {
            this.cache = cache;
        }

        /// Returns the isolated cache for downloads and integrity checks.
        @Override
        public DefaultCacheRepository getCacheRepository() {
            return cache;
        }
    }

    /// Supplies isolated repository paths for the real completion and rollback tasks.
    @NotNullByDefault
    private static final class TestRepository extends DefaultGameRepository {
        /// Creates a repository rooted in the test directory.
        private TestRepository(Path path) {
            super(path);
        }

        /// Creates the standard on-disk layout.
        @Override
        protected DefaultGameRepositoryLayout createLayout(Path path) {
            return new DefaultGameRepositoryLayout(path);
        }

        /// Creates instances bound to the published snapshot.
        @Override
        protected DefaultGameInstance createInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                                                     GameInstanceManifest manifest, @Nullable Path file) {
            return new TestInstance(snapshot, id, manifest, file);
        }
    }

    /// Minimal instance implementation for publishing and refreshing test snapshots.
    @NotNullByDefault
    private static final class TestInstance extends DefaultGameInstance {
        /// Creates an instance using a manifest file or the standard repository path.
        private TestInstance(DefaultGameRepositorySnapshot snapshot, GameInstanceID id,
                             GameInstanceManifest manifest, @Nullable Path file) {
            super(snapshot, id, manifest, file);
        }

        /// Rebinds an unchanged manifest after a repository refresh.
        @Override
        protected DefaultGameInstance withNewSnapshot(DefaultGameRepositorySnapshot snapshot) {
            return new TestInstance(snapshot, getId(), getManifest(), getManifestFile());
        }

        /// Rebinds a replacement manifest after draft publication.
        @Override
        protected DefaultGameInstance withManifest(DefaultGameRepositorySnapshot snapshot, GameInstanceManifest manifest) {
            return new TestInstance(snapshot, getId(), manifest, getManifestFile());
        }
    }
}
