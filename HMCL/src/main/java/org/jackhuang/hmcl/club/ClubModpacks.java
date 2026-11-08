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

import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.game.DefaultGameInstance;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.ModpackHelper;
import org.jackhuang.hmcl.modpack.Modpack;
import org.jackhuang.hmcl.modpack.ModpackConfiguration;
import org.jackhuang.hmcl.modpack.ModpackUpdateTask;
import org.jackhuang.hmcl.modpack.server.ServerModpackCompletionTask;
import org.jackhuang.hmcl.modpack.server.ServerModpackManifest;
import org.jackhuang.hmcl.modpack.server.ServerModpackRemoteInstallTask;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/// Prepares community instances through HMCL's native downloading and transactional update tasks.
@NotNullByDefault
public final class ClubModpacks {
    /// Prevents instantiation of this task factory.
    private ClubModpacks() {
    }

    /// Fetches the current manifest, installs or updates as necessary, then selects the instance.
    /// Updates use the native backup and rollback behavior; a failed task never starts the game.
    public static Task<@Nullable Void> prepare(HMCLGameRepository repository, ClubCatalog.Server server) {
        return repository.refreshAsync().thenComposeAsync(Schedulers.io(), () -> {
            ServerModpackManifest manifest = HttpRequest.GET(server.manifestUrl()).getJson(ServerModpackManifest.class);
            Modpack modpack = validateManifest(manifest);
            Task<?> operation;
            if (repository.getSnapshot().hasInstance(server.instanceId())) {
                @Nullable ModpackConfiguration<ServerModpackManifest> installed = JsonUtils.fromJsonFile(
                        repository.getInstance(server.instanceId()).getModpackConfigurationFile(),
                        ModpackConfiguration.typeOf(ServerModpackManifest.class));
                if (installed == null) {
                    throw new IOException("无法读取已安装的社团整合包配置");
                }
                operation = needsUpdate(installed, manifest)
                        ? update(repository.getDependency(), repository.getInstance(server.instanceId()), installed)
                        : Task.runAsync(() -> {});
            } else {
                operation = ModpackHelper.getInstallTask(repository, manifest, server.instanceId(), modpack);
            }
            return operation.thenRunAsync(Schedulers.javafx(), () ->
                    repository.setSelectedInstance(repository.getInstance(server.instanceId())));
        });
    }

    /// Rejects incomplete remote publications before reserving or changing a local instance.
    static Modpack validateManifest(ServerModpackManifest manifest) throws IOException {
        if (manifest.getName() == null || manifest.getName().isBlank()
                || manifest.getVersion() == null || manifest.getVersion().isBlank()
                || manifest.getFileApi() == null || manifest.getFileApi().isBlank()) {
            throw new IOException("社团整合包缺少名称、版本号或文件下载地址");
        }
        try {
            ClubCatalog.requireHttpUrl(manifest.getFileApi());
        } catch (IllegalArgumentException e) {
            throw new IOException("社团整合包文件下载地址无效", e);
        }
        return manifest.toModpack();
    }

    /// Updates against the installed file hashes, retaining native preservation of user edits.
    /// The entire completion pipeline, including version recording, is covered by rollback.
    static Task<@Nullable Void> update(DefaultDependencyManager dependency, DefaultGameInstance instance,
                                       ModpackConfiguration<ServerModpackManifest> installed) {
        Task<@Nullable Void> completion = new ServerModpackCompletionTask(dependency, instance, installed)
                .thenRunAsync(() -> {
                    @Nullable ModpackConfiguration<ServerModpackManifest> completed = JsonUtils.fromJsonFile(
                            instance.getModpackConfigurationFile(), ModpackConfiguration.typeOf(ServerModpackManifest.class));
                    if (completed == null) {
                        throw new IOException("更新后的整合包配置为空");
                    }
                    @Nullable String version = completed.getManifest().getVersion();
                    if (version == null || version.isBlank()) {
                        throw new IOException("更新后的整合包未提供版本号");
                    }
                    JsonUtils.writeToJsonFile(instance.getModpackConfigurationFile(),
                            completed.setVersion(version));
                });
        return new ModpackUpdateTask(instance, completion)
                .thenComposeAsync(instance.getRepository().refreshAsync());
    }

    /// Rejects incompatible instances and compares publisher versions without assuming SemVer.
    static boolean needsUpdate(ModpackConfiguration<?> installed, ServerModpackManifest manifest) throws IOException {
        if (!ServerModpackRemoteInstallTask.MODPACK_TYPE.equals(installed.getType())
                || !manifest.getName().equals(installed.getName())) {
            throw new IOException("同名实例不属于此社团整合包，请先备份并重命名该实例");
        }
        return !manifest.getVersion().equals(installed.getVersion());
    }
}
