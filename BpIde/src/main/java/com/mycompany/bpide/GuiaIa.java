// ============================================================
// GuiaIa.java — V9 H1.3 (#854): la guía para IA, en la carpeta en que trabaja
// ============================================================
package com.mycompany.bpide;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * La guía para IA ({@code AGENTS.md}, en inglés: Eduardo, 23-nov, «Sólo inglés») y el {@code CLAUDE.md} de una línea que
 * la incluye ({@code @AGENTS.md}), en una carpeta: la de cada proyecto nuevo y la de la IA al encender su servidor.
 * Claude Code lee AGENTS.md sólo si no hay un CLAUDE.md en la carpeta de trabajo ni por encima; con el import la lee
 * siempre (su documentación, «memory»). La plantilla viaja en el jar (el pom la copia de {@code scripts/zip/AGENTS.md},
 * la misma de la raíz del ZIP), con la ruta de la instalación donde dice {@code @INSTALACION@}: desde un proyecto, las
 * carpetas de la instalación no están a mano por una ruta relativa.
 *
 * <p>V9 #893 — lleva al final una marca con la huella de su texto. La de un IDE anterior (o de otra carpeta de la
 * instalación) que nadie ha cambiado se pone al día; una cambiada, o una sin la marca, no se toca, y se dice.</p>
 */
final class GuiaIa {

    private GuiaIa() { }

    static final String GUIA = "AGENTS.md";
    static final String CLAUDE = "CLAUDE.md";

    private static final String MARCA = "<!-- Left by the BasicPlus IDE, which updates it unless somebody changes it: ";
    private static final String FIN_MARCA = " -->";

    /** Deja en {@code carpeta} lo que falte de los dos, y pone al día la guía que dejó otro IDE si nadie la ha cambiado; no
     *  pisa nada más. Devuelve los que ha escrito (vacía si estaban); {@code dice}, lo que hay que saber de la que estaba. */
    static List<Path> dejaEn(Path carpeta, Consumer<String> dice) throws IOException {
        List<Path> escritos = new ArrayList<>();
        Path guia = carpeta.resolve(GUIA);
        String texto = plantilla();
        if (texto == null) throw new IOException("el IDE no lleva la guía para IA (AGENTS.md)");
        texto = conMarca(texto.replace("@INSTALACION@", raizDeLaInstalacion()));
        if (!Files.exists(guia)) {
            Files.write(guia, texto.getBytes(StandardCharsets.UTF_8));
            escritos.add(guia);
        } else {
            String hay = new String(Files.readAllBytes(guia), StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!hay.equals(texto)) {
                if (comoLaDejo(hay)) {
                    Files.write(guia, texto.getBytes(StandardCharsets.UTF_8));
                    escritos.add(guia);
                    dice.accept("la guía para IA de esa carpeta era la de otro IDE: puesta al día");
                } else {
                    dice.accept("la guía para IA de esa carpeta (" + guia + ") no es la de este IDE, y está cambiada: no se"
                            + " toca. Para tener la de ahora, bórrala");
                }
            }
        }
        Path claude = carpeta.resolve(CLAUDE);
        if (!Files.exists(claude)) {
            Files.write(claude, ("@" + GUIA + "\n").getBytes(StandardCharsets.UTF_8));
            escritos.add(claude);
        }
        return escritos;
    }

    /** El texto, con su marca detrás. */
    static String conMarca(String texto) {
        String t = texto.endsWith("\n") ? texto : texto + "\n";
        return t + "\n" + MARCA + huella(t) + FIN_MARCA + "\n";
    }

    /** ¿Está como la dejó un IDE? Su marca casa con lo de arriba. */
    static boolean comoLaDejo(String hay) {
        int i = hay.lastIndexOf("\n" + MARCA);
        if (i < 0) return false;
        int f = hay.indexOf(FIN_MARCA, i);
        return f > 0 && hay.substring(f + FIN_MARCA.length()).trim().isEmpty()
                && hay.substring(i + 1 + MARCA.length(), f).equals(huella(hay.substring(0, i)));
    }

    private static String huella(String t) {
        CRC32 c = new CRC32();
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        c.update(b, 0, b.length);
        return String.format("%08x", c.getValue());
    }

    /** La plantilla, del jar; null si no está. */
    static String plantilla() throws IOException {
        try (InputStream in = GuiaIa.class.getResourceAsStream("/" + GUIA)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /** La raíz de la instalación, con / y acabada en /: la carpeta de su BpVM.cfg (el ZIP, o la raíz del repositorio),
     *  donde están docs/, bpstdlib/, samples/ y bp; sin él, la del jar. */
    static String raizDeLaInstalacion() {
        Path raiz = null;
        edu.bpgenvm.config.VmConfig cfg = IdePrefs.cfgDeLaInstalacion();
        if (cfg != null && cfg.sourcePath != null) raiz = java.nio.file.Paths.get(cfg.sourcePath).getParent();
        if (raiz == null) raiz = IdePrefs.installDir();
        if (raiz == null) return "";
        String r = raiz.toAbsolutePath().normalize().toString().replace('\\', '/');
        return r.endsWith("/") ? r : r + "/";
    }
}
