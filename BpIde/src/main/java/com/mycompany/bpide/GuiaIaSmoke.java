package com.mycompany.bpide;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * V9 #893 (25-nov) — la guía para IA que deja el IDE en una carpeta ({@link GuiaIa}): la escribe si falta, la deja si es la
 * suya, pone al día la de otro IDE que nadie ha cambiado, y no toca una cambiada ni una sin su marca.
 * <pre>java -cp BpIde-8.1.jar com.mycompany.bpide.GuiaIaSmoke</pre>
 * Sale con 0 si todo va bien.
 */
public final class GuiaIaSmoke {

    private static int fallos = 0;

    private static void mira(boolean ok, String que) {
        System.out.println((ok ? "ok    " : "FALLO ") + que);
        if (!ok) fallos++;
    }

    private static String lee(Path f) throws Exception { return new String(Files.readAllBytes(f), StandardCharsets.UTF_8); }

    private static void pon(Path f, String t) throws Exception { Files.write(f, t.getBytes(StandardCharsets.UTF_8)); }

    public static void main(String[] args) throws Exception {
        Path d = Files.createTempDirectory("guia");
        Path g = d.resolve(GuiaIa.GUIA);
        List<String> dicho = new ArrayList<>();

        List<Path> e = GuiaIa.dejaEn(d, dicho::add);
        final String suya = lee(g);
        mira(e.size() == 2 && dicho.isEmpty() && lee(d.resolve(GuiaIa.CLAUDE)).equals("@AGENTS.md\n")
                && GuiaIa.comoLaDejo(suya) && !suya.contains("@INSTALACION@"), "si falta, la escribe (y el CLAUDE.md): " + e.size());
        e = GuiaIa.dejaEn(d, dicho::add);
        mira(e.isEmpty() && dicho.isEmpty() && lee(g).equals(suya), "la suya, ni tocarla");

        pon(g, GuiaIa.conMarca("# BasicPlus, la guía de otro IDE\n\nCon lo que decía entonces.\n"));
        e = GuiaIa.dejaEn(d, dicho::add);
        mira(e.size() == 1 && lee(g).equals(suya) && dicho.size() == 1 && dicho.get(0).contains("puesta al día"),
                "la de otro IDE, sin cambiar, al día: " + dicho);

        dicho.clear();
        pon(g, GuiaIa.conMarca("# la de otro IDE\n").replace("\n", "\r\n"));
        e = GuiaIa.dejaEn(d, dicho::add);
        mira(e.size() == 1 && lee(g).equals(suya), "también con CRLF (git)");

        String cambiada = GuiaIa.conMarca("# la de otro IDE\n").replace("# la", "# mis notas, y la");
        String[][] otras = { { "cambiada", cambiada }, { "con algo detrás", GuiaIa.conMarca("# otra\n") + "y lo mío\n" },
            { "sin marca", "# mi propia guía\n" } };
        for (String[] x : otras) {
            dicho.clear();
            pon(g, x[1]);
            e = GuiaIa.dejaEn(d, dicho::add);
            mira(e.isEmpty() && lee(g).equals(x[1]) && dicho.size() == 1 && dicho.get(0).contains("no se toca"),
                    x[0] + ", no se toca, y se dice: " + dicho);
        }
        System.out.println(fallos == 0 ? "GuiaIaSmoke: TODO BIEN" : "GuiaIaSmoke: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }
}
