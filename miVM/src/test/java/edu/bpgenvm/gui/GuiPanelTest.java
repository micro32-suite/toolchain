package edu.bpgenvm.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V9 #909 (25-nov; Eduardo: «b») — un panel de la VM Java deja por dentro lo que el de LVGL: su borde de 2 px y su margen, que
 * depende del ancho de la pantalla. Los números son los de los píxeles de la VM-C y de la P4 (a 480 de ancho, 2 + 16), y aquí
 * se miran también en los píxeles, los de la captura ({@code Gui.shot}), pintada fuera de pantalla.
 */
class GuiPanelTest {

    private static final int VERDE = 0x00A000, ROJO = 0xD00000, AZUL = 0x0000D0;

    private boolean antes;
    private GuiBackend g;

    @BeforeEach
    void fuera() {
        antes = GuiBackend.fueraDePantalla;
        GuiBackend.fueraDePantalla = true;
        g = new GuiBackend();
    }

    @AfterEach
    void comoEstaba() { GuiBackend.fueraDePantalla = antes; }

    /** La captura, y sus píxeles en RGB565 (la cabecera y las franjas, docs/SHOT_FORMAT.md). */
    private static final class Captura {
        final int w, h;
        final byte[] b;

        Captura(GuiBackend g, Path dir) throws Exception {
            Path f = dir.resolve("x.shot");
            assertTrue(g.shot(f.toString()) > 0, "la captura");
            b = Files.readAllBytes(f);
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            w = bb.getShort(8) & 0xFFFF;
            h = bb.getShort(10) & 0xFFFF;
        }

        int px(int x, int y) {
            int i = 16 + (y / 24) * (16 + w * 24 * 2) + 16 + ((y % 24) * w + x) * 2;
            return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
        }

        boolean es(int x, int y, int rgb) {
            int r = (rgb >> 16) & 0xFF, v = (rgb >> 8) & 0xFF, a = rgb & 0xFF;
            return px(x, y) == ((r >> 3) << 11 | (v >> 2) << 5 | (a >> 3));
        }
    }

    private int panel(int padre, int x, int y, int w, int h, int rgb) {
        int p = g.createObj(padre);
        g.setX(p, x);
        g.setY(p, y);
        g.setWidth(p, w);
        g.setHeight(p, h);
        g.setBgColor(p, rgb);
        return p;
    }

    @Test
    @DisplayName("un hijo en (0,0) sale a 18 px de la esquina (2 de borde y 16 de margen), y el borde se pinta encima")
    void enLaEsquina(@TempDir Path dir) throws Exception {
        int scr = g.screenActive();
        int p = panel(scr, 20, 20, 200, 100, VERDE);
        panel(p, 0, 0, 200, 100, ROJO);
        Captura c = new Captura(g, dir);
        assertTrue(c.es(22, 60, VERDE) && c.es(37, 60, VERDE), "el panel, hasta donde empieza el hijo");
        assertTrue(c.es(38, 80, 0xE0E0E0) && c.es(39, 80, 0xE0E0E0), "el borde del hijo");
        assertTrue(c.es(40, 80, ROJO), "y el hijo");
        assertTrue(c.es(20, 60, 0xE0E0E0) && c.es(21, 60, 0xE0E0E0), "el borde del panel");
        assertTrue(c.es(218, 80, 0xE0E0E0) && c.es(219, 80, 0xE0E0E0), "y a la derecha, encima del hijo, que no cabe");
        assertFalse(c.es(220, 80, ROJO), "y el hijo no se sale del panel");
    }

    @Test
    @DisplayName("un hijo de lo que cabe, 164 x 64, queda entero, con el mismo margen por los cuatro lados")
    void loQueCabe(@TempDir Path dir) throws Exception {
        int p = panel(g.screenActive(), 20, 20, 200, 100, VERDE);
        panel(p, 0, 0, 164, 64, ROJO);
        Captura c = new Captura(g, dir);
        assertTrue(c.es(40, 60, ROJO) && c.es(199, 60, ROJO) && c.es(100, 40, ROJO) && c.es(100, 99, ROJO), "el hijo");
        assertTrue(c.es(202, 60, VERDE) && c.es(217, 60, VERDE) && c.es(100, 102, VERDE) && c.es(100, 117, VERDE), "el margen");
    }

    @Test
    @DisplayName("alineado: desde lo de dentro; y su x, y, contadas desde ahí, como en LVGL")
    void alineado(@TempDir Path dir) throws Exception {
        int p = panel(g.screenActive(), 20, 20, 200, 100, VERDE);
        int h = g.createObj(p);
        g.setWidth(h, 40);
        g.setHeight(h, 20);
        g.setBgColor(h, AZUL);
        g.align(h, GuiBackend.BOTTOM_RIGHT, 0, 0);
        assertEquals(124, g.getX(h));
        assertEquals(44, g.getY(h));
        Captura c = new Captura(g, dir);
        assertTrue(c.es(164, 90, AZUL) && c.es(199, 90, AZUL), "de 162 a 201, sin su borde");
        assertTrue(c.es(161, 90, VERDE) && c.es(202, 90, VERDE), "y a 18 px de la esquina del panel");
        g.align(h, GuiBackend.CENTER, 0, 0);
        assertEquals(62, g.getX(h));
        assertEquals(22, g.getY(h));
    }

    @Test
    @DisplayName("la ventana de un formulario: la raíz, centrada y del tamaño de la pantalla, la cubre entera (#905)")
    void laVentana() {
        int scr = g.screenActive();
        int w = panel(scr, 0, 0, 480, 320, VERDE);
        int raiz = g.createObj(w);
        g.setWidth(raiz, 480);
        g.setHeight(raiz, 320);
        g.align(raiz, GuiBackend.CENTER, 0, 0);
        assertEquals(-18, g.getX(raiz));
        assertEquals(-18, g.getY(raiz));
    }

    @Test
    @DisplayName("el margen es el del ancho de fábrica: girar la pantalla no lo cambia")
    void girada(@TempDir Path dir) throws Exception {
        g.setRotation(90);
        int p = panel(g.screenActive(), 20, 20, 200, 100, VERDE);
        panel(p, 0, 0, 60, 30, ROJO);
        Captura c = new Captura(g, dir);
        assertEquals(320, c.w);
        assertTrue(c.es(40, 50, ROJO) && c.es(37, 50, VERDE), "a 18 px, como sin girar");
    }

    @Test
    @DisplayName("la página de una pestaña: el margen, sin borde")
    void laPagina(@TempDir Path dir) throws Exception {
        int scr = g.screenActive();
        int t = g.createTabview(scr);
        g.setX(t, 20);
        g.setY(t, 130);
        g.setWidth(t, 200);
        g.setHeight(t, 100);
        int pag = g.tabviewAddTab(t, "uno");
        g.setBgColor(pag, VERDE);
        int q = panel(pag, 0, 0, 60, 20, AZUL);
        Captura c = new Captura(g, dir);
        int x0 = -1, y0 = -1;                       // la esquina de la página: su primer píxel verde
        for (int y = 130; y < 230 && y0 < 0; y++)
            for (int x = 20; x < 220; x++)
                if (c.es(x, y, VERDE)) { x0 = x; y0 = y; break; }
        assertTrue(y0 > 0, "la página");
        assertTrue(c.es(x0 + 18, y0 + 26, AZUL) && !c.es(x0 + 15, y0 + 26, AZUL), "el hijo, a 16 px (y 2 de su borde)");
        assertEquals(0, g.getX(q));
    }
}
