package dev.hartforge.foundryadditions.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ExtrasScreen extends Screen {

    private static final int ROW_H = 46;
    private static final int TOP = 56;

    private final Screen parent;
    private final List<ExtrasChecker.Problem> problems;
    private final Path modsDir = FMLPaths.MODSDIR.get();
    private final Path downloadsDir = DownloadsScanner.defaultDownloadsDir();

    private final Map<String, Path> found = new HashMap<>();
    private final Map<String, String> notes = new HashMap<>();
    private boolean closed;

    public ExtrasScreen(Screen parent, List<ExtrasChecker.Problem> problems) {
        super(Component.literal("the foundry needs more mods"));
        this.parent = parent;
        this.problems = problems;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = TOP;
        for (ExtrasChecker.Problem p : problems) {
            ExtrasManifest.Extra e = p.extra();
            // TODO: long mod names clip on the main button
            boolean direct = !e.download().isEmpty();
            String ver = e.fileVersion().isEmpty() ? "" : " " + e.fileVersion();
            Component mainLabel = direct
                    ? Component.literal("download " + e.name() + ver)
                    : Component.literal("open download page");
            String mainUrl = direct ? e.download() : e.page();
            int mainW = direct && !e.page().isEmpty() ? 130 : 160;
            int x = cx - 150;
            Button main = Button.builder(mainLabel, b -> openPage(mainUrl))
                    .bounds(x, y + 11, mainW, 20).build();
            main.active = !mainUrl.isEmpty();
            addRenderableWidget(main);
            x += mainW + 4;
            if (direct && !e.page().isEmpty()) {
                addRenderableWidget(Button.builder(Component.literal("file page"), b -> openPage(e.page()))
                        .bounds(x, y + 11, 55, 20).build());
                x += 55 + 4;
            }
            int restW = cx + 150 - x;

            Path hit = found.get(e.modId());
            if (hit == null) {
                addRenderableWidget(Button.builder(Component.literal("i downloaded it"), b -> scan(e))
                        .bounds(x, y + 11, restW, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("move it into mods"), b -> move(e, hit, p.oldFile()))
                        .bounds(x, y + 11, restW, 20).build());
            }
            y += ROW_H;
        }
        int by = Math.min(this.height - 52, y + 24);
        addRenderableWidget(Button.builder(Component.literal("open mods folder"), b -> openModsFolder())
                .bounds(cx - 115, by, 230, 20).build());
        addRenderableWidget(Button.builder(Component.literal("not now"), b -> onClose())
                .bounds(cx - 115, by + 24, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("quit game"), b -> this.minecraft.stop())
                .bounds(cx + 5, by + 24, 110, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int n = problems.size();
        g.drawCenteredString(this.font,
                Component.literal("the foundry needs " + n + " more mod" + (n == 1 ? "" : "s") + " to join"),
                cx, 16, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.literal("mods load at launch, so restart the game after adding them"),
                cx, 30, 0xAAAAAA);
        int y = TOP;
        for (ExtrasChecker.Problem p : problems) {
            ExtrasManifest.Extra e = p.extra();
            String label = p.status() == ExtrasChecker.Status.MISSING
                    ? e.name() + " - missing"
                    : e.name() + " - have " + p.haveVersion() + ", need " + p.need() + "+";
            g.drawCenteredString(this.font, Component.literal(label), cx, y, 0xFFFFFF);
            String note = notes.get(e.modId());
            if (note == null && found.containsKey(e.modId())) {
                note = "found " + found.get(e.modId()).getFileName();
            }
            if (note != null) {
                g.drawCenteredString(this.font, Component.literal(note), cx, y + 34, 0xAAAAAA);
            }
            y += ROW_H;
        }
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void onClose() {
        closed = true;
        this.minecraft.setScreen(parent);
    }

    private static void openPage(String url) {
        if (!url.isEmpty()) Util.getPlatform().openUri(url);
    }

    private void openModsFolder() {
        Util.getPlatform().openFile(modsDir.toFile());
    }

    private void scan(ExtrasManifest.Extra e) {
        notes.put(e.modId(), "looking in your downloads folder...");
        Minecraft mc = this.minecraft;
        CompletableFuture.supplyAsync(() -> DownloadsScanner.find(downloadsDir, e.jarHint(), e.minBuild(), Instant.now()))
                .thenAccept(hit -> mc.execute(() -> {
                    if (hit.isPresent()) {
                        found.put(e.modId(), hit.get());
                        notes.remove(e.modId());
                    } else {
                        notes.put(e.modId(), "nothing recent in downloads, grab it from the page first");
                    }
                    if (mc.screen == this) this.rebuildWidgets();
                }));
    }

    private record Done(Path jar, DownloadsScanner.MoveResult res, DownloadsScanner.RetireResult retired) {}

    private void move(ExtrasManifest.Extra e, Path cached, String oldFile) {
        Minecraft mc = this.minecraft;
        // rescan, a newer jar may have landed since the first scan
        CompletableFuture.supplyAsync(() -> {
            Path jar = DownloadsScanner.find(downloadsDir, e.jarHint(), e.minBuild(), Instant.now()).orElse(cached);
            var res = DownloadsScanner.moveIntoMods(downloadsDir, jar, modsDir);
            boolean landed = res == DownloadsScanner.MoveResult.MOVED
                    || res == DownloadsScanner.MoveResult.COPIED_SOURCE_KEPT;
            // two jars of one mod kills the launch, so the old one steps aside
            var retired = landed && !oldFile.isEmpty() && !oldFile.equals(jar.getFileName().toString())
                    ? DownloadsScanner.retireOld(modsDir, oldFile) : null;
            return new Done(jar, res, retired);
        }).thenAccept(r -> mc.execute(() -> {
            var res = r.res();
            found.put(e.modId(), r.jar());
            switch (res) {
                case MOVED, COPIED_SOURCE_KEPT -> {
                    String base = res == DownloadsScanner.MoveResult.MOVED
                            ? "moved into mods" : "copied into mods, couldn't delete the download";
                    if (r.retired() == null) {
                        notes.put(e.modId(), base + ", restart the game");
                    } else if (r.retired() == DownloadsScanner.RetireResult.RETIRED) {
                        notes.put(e.modId(), base + ", old " + oldFile + " set aside as " + oldFile
                                + ".old, restart the game");
                    } else {
                        notes.put(e.modId(), "moved the new one, but remove " + oldFile
                                + " from the mods folder or the game will not start");
                    }
                    found.remove(e.modId());
                }
                case ALREADY_EXISTS -> notes.put(e.modId(),
                        "a file with that name is already in mods, left alone");
                default -> notes.put(e.modId(), "couldn't move it, use open mods folder");
            }
            if (mc.screen == this) this.rebuildWidgets();
        }));
    }
}
