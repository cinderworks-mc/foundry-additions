package dev.hartforge.foundryadditions.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

public final class ExtrasScreen extends Screen {

    private static final int ACTION_W = 56;
    private static final int PAGE_W = 62;

    private final Screen parent;
    private final ExtrasSession session;
    private final Path modsDir = FMLPaths.MODSDIR.get();
    private final Map<ExtrasChecker.Row, RowEntry> entries = new HashMap<>();

    private RowList list;
    private EditBox search;
    private Button allBtn;
    private Button fromDownloadsBtn;
    private Button closeBtn;
    private String query = "";
    private boolean closed;

    public ExtrasScreen(Screen parent, ExtrasSession session) {
        super(Component.literal("extra mods"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = ExtrasLayout.buttonsTop(this.height);
        ExtrasLayout.Area a = ExtrasLayout.list(this.height);

        search = new EditBox(this.font, cx - 100, 31, 200, 16, search, Component.literal("search"));
        search.setHint(Component.literal("search by name").withColor(0x808080));
        search.setResponder(s -> {
            query = s.trim().toLowerCase(Locale.ROOT);
            list.refill();
        });
        addRenderableWidget(search);

        list = new RowList(this.minecraft, this.width, a.bottom() - a.top(), a.top());
        list.refill();
        addRenderableWidget(list);

        allBtn = Button.builder(Component.empty(), b -> session.download(session.wantedForAll()))
                .bounds(cx - 150, top, 148, 20).build();
        addRenderableWidget(allBtn);
        fromDownloadsBtn = Button.builder(Component.literal("add from downloads folder"),
                        b -> session.addFromDownloads(Instant.now()))
                .bounds(cx + 2, top, 148, 20).build();
        addRenderableWidget(fromDownloadsBtn);
        addRenderableWidget(Button.builder(Component.literal("open mods folder"),
                        b -> Util.getPlatform().openFile(modsDir.toFile()))
                .bounds(cx - 150, top + 24, 97, 20).build());
        closeBtn = Button.builder(Component.literal("not now"), b -> onClose())
                .bounds(cx - 48, top + 24, 97, 20).build();
        addRenderableWidget(closeBtn);
        addRenderableWidget(Button.builder(Component.literal("quit game"), b -> this.minecraft.stop())
                .bounds(cx + 53, top + 24, 97, 20).build());
        refreshButtons();
    }

    @Override
    public void tick() {
        session.tick(System.nanoTime());
        refreshButtons();
    }

    private void refreshButtons() {
        List<ExtrasChecker.Row> all = session.wantedForAll();
        allBtn.setMessage(Component.literal(ExtrasText.downloadAll(all.size(),
                all.stream().map(session::size).toList())));
        allBtn.active = !all.isEmpty() && !session.scanning();
        fromDownloadsBtn.active = !session.active();
        closeBtn.setMessage(Component.literal(session.active() ? "stop and close"
                : session.anyDone() ? "close" : "not now"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, ExtrasText.title(session.needingAction(), session.anyDone()), cx, 8, 0xFFFFFF);
        ExtrasText.Line sub = ExtrasText.subtitle(session.active(), session.anyDone(), session.notice());
        g.drawCenteredString(this.font, sub.text(), cx, 20, color(sub.tone()));
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void onClose() {
        closed = true;
        session.stopAll();
        this.minecraft.setScreen(parent);
    }

    private static int color(ExtrasText.Tone tone) {
        return switch (tone) {
            case GOOD -> 0x55FF55;
            case WARN -> 0xFFCC55;
            case BAD -> 0xFF7070;
            case BUSY -> 0x80D8FF;
            case PLAIN -> 0xAAAAAA;
        };
    }

    private boolean matches(ExtrasChecker.Row r) {
        return query.isEmpty() || r.extra().name().toLowerCase(Locale.ROOT).contains(query)
                || r.extra().modId().toLowerCase(Locale.ROOT).contains(query);
    }

    private final class RowList extends ContainerObjectSelectionList<RowEntry> {

        RowList(Minecraft mc, int width, int height, int y) {
            super(mc, width, height, y, ExtrasLayout.ROW_H);
        }

        void refill() {
            List<RowEntry> shown = session.rows().stream()
                    .filter(ExtrasScreen.this::matches)
                    .map(r -> entries.computeIfAbsent(r, RowEntry::new))
                    .toList();
            replaceEntries(shown);
            setScrollAmount(0);
        }

        @Override
        public int getRowWidth() {
            return ExtrasLayout.rowWidth(ExtrasScreen.this.width);
        }

        @Override
        protected void renderDecorations(GuiGraphics g, int mouseX, int mouseY) {
            if (children().isEmpty()) {
                g.drawCenteredString(ExtrasScreen.this.font, "nothing matches \"" + query + "\"",
                        getX() + width / 2, getY() + 12, 0xAAAAAA);
            }
        }
    }

    private final class RowEntry extends ContainerObjectSelectionList.Entry<RowEntry> {

        private final ExtrasChecker.Row row;
        private final Button action;
        private final Button page;
        private String tip = "";

        RowEntry(ExtrasChecker.Row row) {
            this.row = row;
            this.action = Button.builder(Component.empty(), b -> press()).size(ACTION_W, 20).build();
            String url = row.extra().page().isEmpty() ? row.extra().download() : row.extra().page();
            this.page = Button.builder(Component.literal(ExtrasText.pageLabel(url)),
                    b -> Util.getPlatform().openUri(url)).size(PAGE_W, 20)
                    .tooltip(Tooltip.create(Component.literal("open this mod's page in your browser")))
                    .build();
            this.page.visible = !url.isEmpty();
        }

        private void press() {
            switch (session.job(row).phase()) {
                case WAITING, DOWNLOADING -> session.stop(row);
                default -> session.download(session.wantedFor(row));
            }
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovering, float partialTick) {
            Font font = ExtrasScreen.this.font;
            ExtrasSession.Job job = session.job(row);
            ExtrasSession.Phase phase = job.phase();
            List<ExtrasChecker.Row> wants = session.wantedFor(row);
            String label = ExtrasText.action(row, phase, !wants.isEmpty());

            int right = left + width;
            int buttonY = top + (height - 20) / 2;
            action.visible = !label.isEmpty();
            action.setMessage(Component.literal(label));
            action.setPosition(right - ACTION_W, buttonY);
            int pageX = right - ACTION_W - (action.visible ? 4 : 0) - (page.visible ? PAGE_W : 0);
            page.setPosition(pageX, buttonY);
            if (action.visible) action.render(g, mouseX, mouseY, partialTick);
            if (page.visible) page.render(g, mouseX, mouseY, partialTick);

            OptionalLong size = wants.stream().map(session::size).reduce(OptionalLong.of(0),
                    (x, y) -> x.isPresent() && y.isPresent() ? OptionalLong.of(x.getAsLong() + y.getAsLong())
                            : OptionalLong.empty());
            String newTip = ExtrasText.actionTip(label, wants.stream().map(w -> w.extra().name()).toList(), size);
            if (!newTip.equals(tip)) {
                tip = newTip;
                action.setTooltip(tip.isEmpty() ? null : Tooltip.create(Component.literal(tip)));
            }

            int textRight = pageX - 6;
            if ((phase == ExtrasSession.Phase.IDLE || phase == ExtrasSession.Phase.FAILED) && !wants.isEmpty()) {
                if (size.isPresent()) {
                    String s = ExtrasText.size(size.getAsLong());
                    textRight -= font.width(s);
                    g.drawString(font, s, textRight, top + (height - 8) / 2, 0xAAAAAA);
                    textRight -= 6;
                }
            }
            int room = textRight - left;
            g.drawString(font, font.plainSubstrByWidth(row.extra().name(), room), left, top + 1, 0xFFFFFF);

            List<String> missing = session.missingRequires(row).stream().map(session::nameOf).toList();
            ExtrasText.Line line = ExtrasText.state(row, phase, job.done(), job.total(), job.message(), missing);
            int x = left;
            int y = top + 12;
            if (phase == ExtrasSession.Phase.DOWNLOADING && job.total() > 0) {
                int barW = Math.min(60, room / 3);
                int fill = (int) (barW * Math.min(1.0, job.done() / (double) job.total()));
                g.fill(x, y + 1, x + barW, y + 7, 0xFF303030);
                g.fill(x, y + 1, x + fill, y + 7, 0xFF55AAFF);
                x += barW + 5;
            }
            String text = line.text();
            String shown = font.plainSubstrByWidth(text, textRight - x);
            if (!shown.equals(text)) shown = font.plainSubstrByWidth(text, textRight - x - font.width("...")) + "...";
            g.drawString(font, shown, x, y, color(line.tone()));
            // long messages get cut on small windows, hovering the line shows all of it
            if (!shown.equals(text) && mouseX >= x && mouseX < textRight && mouseY >= y - 1 && mouseY < y + 10) {
                ExtrasScreen.this.setTooltipForNextRenderPass(Component.literal(text));
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(page, action);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(page, action);
        }
    }
}
