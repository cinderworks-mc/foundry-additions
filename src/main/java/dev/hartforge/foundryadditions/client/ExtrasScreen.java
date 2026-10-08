package dev.hartforge.foundryadditions.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class ExtrasScreen extends Screen {

    private static final int BAR_W = 4;

    private final Screen parent;
    private final List<ExtrasChecker.Problem> problems;
    private final Path modsDir = FMLPaths.MODSDIR.get();
    private final Path downloadsDir = DownloadsScanner.defaultDownloadsDir();

    private final Map<String, String> status = new HashMap<>();
    private final Set<String> moved = new HashSet<>();
    private Button installBtn;
    private boolean busy;
    private boolean closed;
    private boolean barDrag;
    private int offset;

    public ExtrasScreen(Screen parent, List<ExtrasChecker.Problem> problems) {
        super(Component.literal("the foundry needs more mods"));
        this.parent = parent;
        this.problems = problems;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = ExtrasLayout.buttonsTop(this.height);
        int opens = (int) problems.stream().filter(p -> !urlFor(p).isEmpty()).count();
        Button dl = Button.builder(Component.literal("download all (" + opens + ")"), b -> downloadAll())
                .bounds(cx - 150, top, 148, 20).build();
        dl.active = opens > 0;
        addRenderableWidget(dl);
        installBtn = Button.builder(Component.literal("install all"), b -> installAll())
                .bounds(cx + 2, top, 148, 20).build();
        installBtn.active = !busy;
        addRenderableWidget(installBtn);
        addRenderableWidget(Button.builder(Component.literal("open mods folder"), b -> openModsFolder())
                .bounds(cx - 150, top + 24, 97, 20).build());
        addRenderableWidget(Button.builder(Component.literal("not now"), b -> onClose())
                .bounds(cx - 48, top + 24, 97, 20).build());
        addRenderableWidget(Button.builder(Component.literal("quit game"), b -> this.minecraft.stop())
                .bounds(cx + 53, top + 24, 97, 20).build());
        offset = ExtrasLayout.clamp(offset, problems.size(), ExtrasLayout.list(this.height).rows());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int n = problems.size();
        boolean allIn = moved.size() == n && status.values().stream().noneMatch(v -> v.contains("by hand"));
        g.drawCenteredString(this.font,
                Component.literal("the foundry needs " + n + " more mod" + (n == 1 ? "" : "s") + " to join"),
                cx, 8, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.literal(allIn ? "all in. restart the game now"
                        : "mods load at launch, so restart the game after adding them"),
                cx, 20, allIn ? 0x55FF55 : 0xAAAAAA);

        ExtrasLayout.Area a = ExtrasLayout.list(this.height);
        boolean bar = n > a.rows();
        int left = 10;
        int right = this.width - 10 - (bar ? BAR_W + 3 : 0);
        for (int i = 0; i < a.rows() && offset + i < n; i++) {
            ExtrasChecker.Problem p = problems.get(offset + i);
            String st = statusOf(p);
            int y = a.top() + i * ExtrasLayout.ROW_H + 2;
            String shownStatus = this.font.plainSubstrByWidth(st, (right - left) * 3 / 5);
            int sw = this.font.width(shownStatus);
            String name = this.font.plainSubstrByWidth(p.extra().name(), right - left - sw - 8);
            int color = moved.contains(p.extra().modId()) ? 0x55FF55 : 0xFFFFFF;
            g.drawString(this.font, name, left, y, color);
            g.drawString(this.font, shownStatus, right - sw, y, 0xAAAAAA);
        }
        if (bar) {
            int len = a.bottom() - a.top();
            int thumb = Math.max(8, len * a.rows() / n);
            int thumbY = a.top() + (len - thumb) * offset / (n - a.rows());
            int x = this.width - 10 - BAR_W;
            g.fill(x, a.top(), x + BAR_W, a.bottom(), 0x55000000);
            g.fill(x, thumbY, x + BAR_W, thumbY + thumb, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scrollTo(offset - (int) Math.signum(sy));
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (onBar(mx, my)) {
            barDrag = true;
            barTo(my);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        barDrag = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (barDrag) {
            barTo(my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        int rows = ExtrasLayout.list(this.height).rows();
        switch (key) {
            case GLFW.GLFW_KEY_UP -> scrollTo(offset - 1);
            case GLFW.GLFW_KEY_DOWN -> scrollTo(offset + 1);
            case GLFW.GLFW_KEY_PAGE_UP -> scrollTo(offset - rows);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scrollTo(offset + rows);
            default -> {
                return super.keyPressed(key, scan, mods);
            }
        }
        return true;
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void onClose() {
        closed = true;
        this.minecraft.setScreen(parent);
    }

    private boolean onBar(double mx, double my) {
        ExtrasLayout.Area a = ExtrasLayout.list(this.height);
        int x = this.width - 10 - BAR_W;
        return problems.size() > a.rows() && mx >= x - 2 && mx <= x + BAR_W + 2 && my >= a.top() && my <= a.bottom();
    }

    private void barTo(double my) {
        offset = ExtrasLayout.offsetForClick(my, ExtrasLayout.list(this.height), problems.size());
    }

    private void scrollTo(int to) {
        offset = ExtrasLayout.clamp(to, problems.size(), ExtrasLayout.list(this.height).rows());
    }

    private String statusOf(ExtrasChecker.Problem p) {
        String s = status.get(p.extra().modId());
        if (s != null) return s;
        return p.status() == ExtrasChecker.Status.MISSING ? "missing" : "need " + p.need();
    }

    private static String urlFor(ExtrasChecker.Problem p) {
        return p.extra().download().isEmpty() ? p.extra().page() : p.extra().download();
    }

    private void openModsFolder() {
        Util.getPlatform().openFile(modsDir.toFile());
    }

    private void downloadAll() {
        List<String> urls = problems.stream().map(ExtrasScreen::urlFor).filter(u -> !u.isEmpty()).toList();
        Thread t = new Thread(() -> {
            for (String u : urls) {
                Util.getPlatform().openUri(u);
                try {
                    // browsers drop tabs opened back to back
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "foundry-extras-open");
        t.setDaemon(true);
        t.start();
    }

    private void installAll() {
        busy = true;
        installBtn.active = false;
        Minecraft mc = this.minecraft;
        List<DownloadsScanner.Want> wants = problems.stream()
                .filter(p -> !moved.contains(p.extra().modId()))
                .map(p -> new DownloadsScanner.Want(p.extra().modId(), p.extra().jarHint(),
                        p.extra().minBuild(), p.oldFile()))
                .toList();
        CompletableFuture.supplyAsync(() -> DownloadsScanner.installAll(downloadsDir, modsDir, wants, Instant.now()))
                .thenAccept(outs -> mc.execute(() -> {
                    for (DownloadsScanner.Outcome o : outs) {
                        apply(o, wants.stream().filter(w -> w.modId().equals(o.modId())).findFirst().get());
                    }
                    busy = false;
                    if (installBtn != null) installBtn.active = true;
                }));
    }

    private void apply(DownloadsScanner.Outcome o, DownloadsScanner.Want w) {
        if (o.jar() == null) {
            status.put(o.modId(), "not found yet");
            return;
        }
        switch (o.move()) {
            case MOVED, COPIED_SOURCE_KEPT -> {
                moved.add(o.modId());
                if (o.retired() == null) {
                    status.put(o.modId(), "moved");
                } else if (o.retired() == DownloadsScanner.RetireResult.RETIRED) {
                    status.put(o.modId(), "moved, old one set aside");
                } else {
                    status.put(o.modId(), "moved, remove " + w.oldFile() + " by hand");
                }
            }
            case ALREADY_EXISTS -> status.put(o.modId(), "already in mods, left alone");
            default -> status.put(o.modId(), "couldn't move " + o.jar().getFileName());
        }
    }
}
