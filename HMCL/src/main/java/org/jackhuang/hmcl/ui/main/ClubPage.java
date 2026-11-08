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
package org.jackhuang.hmcl.ui.main;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.club.ClubCatalog;
import org.jackhuang.hmcl.club.ClubModpacks;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.QuickPlayOption;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.account.CreateAccountPane;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// The home dashboard showing community login, announcements, and managed server modpacks.
@NotNullByDefault
public final class ClubPage extends ScrollPane {
    /// Announcement cards from the most recently loaded feed.
    private final VBox announcements = new VBox(10);
    /// Server cards from the most recently loaded feed.
    private final VBox servers = new VBox(10);
    /// Feed refresh state, including recoverable network failures.
    private final Label status = text("正在加载社团内容…");
    /// Prevents concurrent feed requests from publishing out of order.
    private final JFXButton refresh = new JFXButton("刷新");

    /// Creates the dashboard and starts a background feed request.
    public ClubPage() {
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        Label title = text("圣悦灵社");
        title.setStyle("-fx-font-size: 25px; -fx-font-weight: bold;");
        Label subtitle = text("与社团伙伴一起，开启方块世界");
        VBox heading = new VBox(5, title, subtitle);
        HBox.setHgrow(heading, Priority.ALWAYS);

        JFXButton login = new JFXButton("皮肤站登录");
        login.setOnAction(event -> Controllers.dialog(new CreateAccountPane()));
        refresh.setOnAction(event -> reload());
        HBox toolbar = new HBox(10, heading, login, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14, toolbar, status, text("社团公告"), announcements, text("社团服务器"), servers);
        content.setPadding(new Insets(12));
        setContent(content);
        reload();
    }

    /// Replaces displayed cards only after a complete, valid feed has arrived.
    private void reload() {
        refresh.setDisable(true);
        status.setText("正在加载社团内容…");
        Task.supplyAsync(Schedulers.io(), ClubCatalog::load)
                .whenComplete(Schedulers.javafx(), catalog -> {
                    refresh.setDisable(false);
                    status.setText("进入服务器前会自动检查并更新整合包");
                    announcements.getChildren().clear();
                    for (ClubCatalog.Announcement announcement : catalog.announcements()) {
                        announcements.getChildren().add(card(text(announcement.title()), text(announcement.content())));
                    }
                    if (catalog.announcements().isEmpty()) {
                        announcements.getChildren().add(text("暂无公告"));
                    }
                    servers.getChildren().clear();
                    for (ClubCatalog.Server server : catalog.servers()) {
                        servers.getChildren().add(serverCard(server));
                    }
                    if (catalog.servers().isEmpty()) {
                        servers.getChildren().add(text("社团服务器整合包尚未发布"));
                    }
                }, error -> {
                    refresh.setDisable(false);
                    status.setText("社团内容加载失败，请检查网络后重试");
                    LOG.warning("Failed to load community catalog", error);
                }).start();
    }

    /// Builds actions that capture the selected repository at click time.
    private VBox serverCard(ClubCatalog.Server server) {
        JFXButton install = new JFXButton("安装 / 更新整合包");
        JFXButton launch = new JFXButton("进入服务器");
        HBox actions = new HBox(10, install, launch);
        install.setOnAction(event -> prepare(server, actions, false));
        launch.setOnAction(event -> prepare(server, actions, true));
        return card(text(server.name()), text(server.address()), actions);
    }

    /// Runs installation or update before optional quick play, and restores controls on every result.
    private void prepare(ClubCatalog.Server server, HBox actions, boolean launch) {
        HMCLGameRepository repository = GameDirectoryManager.getSelectedRepository();
        actions.setDisable(true);
        Controllers.taskDialog(ClubModpacks.prepare(repository, server)
                .whenComplete(Schedulers.javafx(), (@Nullable Void ignored) -> {
                    actions.setDisable(false);
                    if (launch) {
                        Instances.launch(repository.getInstance(server.instanceId()), helper ->
                                helper.setQuickPlayOption(new QuickPlayOption.MultiPlayer(server.address())));
                    } else {
                        Controllers.dialog("整合包已是社团发布的最新版本。", "准备完成", MessageDialogPane.MessageType.SUCCESS);
                    }
                }, error -> {
                    actions.setDisable(false);
                    if (!(error instanceof CancellationException)) {
                        LOG.warning("Failed to prepare community modpack " + server.id(), error);
                        Controllers.dialog("整合包准备失败，请检查网络和社团发布的配置。\n" + error.getLocalizedMessage(),
                                "安装 / 更新失败", MessageDialogPane.MessageType.ERROR);
                    }
                }), server.name() + " · 安装 / 更新整合包", TaskCancellationAction.NORMAL);
    }

    /// Creates a wrapped label so long remote text stays within the dashboard.
    private static Label text(String value) {
        Label label = new Label(value);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /// Creates a card using the existing theme's card style.
    private static VBox card(javafx.scene.Node... children) {
        VBox box = new VBox(9, children);
        box.setPadding(new Insets(15));
        box.getStyleClass().add("card");
        return box;
    }
}
