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

/**
 * La guía para IA ({@code AGENTS.md}, en inglés: Eduardo, 23-nov, «Sólo inglés») y el {@code CLAUDE.md} de una línea que
 * la incluye ({@code @AGENTS.md}), en una carpeta: la de cada proyecto nuevo y la de la IA al encender su servidor.
 * Claude Code lee AGENTS.md sólo si no hay un CLAUDE.md en la carpeta de trabajo ni por encima; con el import la lee
 * siempre (su documentación, «memory»). La plantilla viaja en el jar (el pom la copia de {@code scripts/zip/AGENTS.md},
 * la misma de la raíz del ZIP), con la ruta de la instalación donde dice {@code @INSTALACION@}: desde un proyecto, las
 * carpetas de la instalación no están a mano por una ruta relativa.
 */
final class GuiaIa {

    private GuiaIa() { }

    static final String GUIA = "AGENTS.md";
    static final String CLAUDE = "CLAUDE.md";

    /** Deja en {@code carpeta} lo que falte de los dos; no pisa nada. Devuelve los que ha escrito (vacía si estaban). */
    static List<Path> dejaEn(Path carpeta) throws IOException {
        List<Path> escritos = new ArrayList<>();
        Path guia = carpeta.resolve(GUIA);
        if (!Files.exists(guia)) {
            String texto = plantilla();
            if (texto == null) throw new IOException("el IDE no lleva la guía para IA (AGENTS.md)");
            Files.write(guia, texto.replace("@INSTALACION@", raizDeLaInstalacion()).getBytes(StandardCharsets.UTF_8));
            escritos.add(guia);
        }
        Path claude = carpeta.resolve(CLAUDE);
        if (!Files.exists(claude)) {
            Files.write(claude, ("@" + GUIA + "\n").getBytes(StandardCharsets.UTF_8));
            escritos.add(claude);
        }
        return escritos;
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
