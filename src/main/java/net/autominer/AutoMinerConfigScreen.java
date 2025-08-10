package net.autominer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class AutoMinerConfigScreen extends Screen {
    private final Screen parent;
    private final AutoMinerConfig config;
    private int tempMaxSearchNodes;

    public AutoMinerConfigScreen(Screen parent, AutoMinerConfig config) {
        super(Text.literal("AutoMiner Einstellungen"));
        this.parent = parent;
        this.config = config;
        // Lade den aktuellen Wert aus der Konfiguration, wenn der Bildschirm geöffnet wird
        this.tempMaxSearchNodes = config.getPathfindingLimit();
    }

    @Override
    protected void init() {
        // Slider für MAX_SEARCH_NODES
        this.addDrawableChild(new SliderWidget(
            this.width / 2 - 100, 60, 200, 20,
            Text.literal("Pfadfinder-Limit: " + this.tempMaxSearchNodes),
            // Normalisiere den Wert auf einen Bereich von 0.0 bis 1.0 für den Slider
            // Der Bereich ist von 10.000 bis 500.000
            (this.tempMaxSearchNodes - 10000) / 490000.0
        ) {
            @Override
            protected void updateMessage() {
                // Konvertiere den Slider-Wert zurück in den tatsächlichen Wert
                tempMaxSearchNodes = 10000 + (int)(this.value * 490000.0);
                // Runde auf den nächsten Tausender für eine bessere Bedienbarkeit
                tempMaxSearchNodes = (tempMaxSearchNodes / 1000) * 1000;
                this.setMessage(Text.literal("Pfadfinder-Limit: " + tempMaxSearchNodes));
            }

            @Override
            protected void applyValue() {
                // Der Wert wird beim Speichern angewendet, nicht bei jeder Bewegung
            }
        });

        // "Speichern & Schließen"-Button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Speichern & Schließen"), (button) -> {
            this.config.setPathfindingLimit(this.tempMaxSearchNodes);
            this.config.save();
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 - 102, this.height - 28, 100, 20).build());

        // "Abbrechen"-Button
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Abbrechen"), (button) -> {
            this.client.setScreen(this.parent);
        }).dimensions(this.width / 2 + 2, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // KORREKTUR: Rufe zuerst super.render() auf. Es zeichnet den Hintergrund und alle Widgets.
        super.render(context, mouseX, mouseY, delta);
        
        // Zeichne danach den Titel und die Warnungen, damit sie über allem liegen.
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
        
        // Warnhinweise
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§eWARNUNG: Höhere Werte können die Leistung beeinträchtigen"), this.width / 2, 100, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§eoder das Spiel zum Absturz bringen. Benutzung auf eigene Gefahr!"), this.width / 2, 112, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§7(Standard: 80000, Neustart nicht erforderlich)"), this.width / 2, 130, 0xFFFFFF);
    }

    @Override
    public void close() {
        this.client.setScreen(this.parent);
    }
}