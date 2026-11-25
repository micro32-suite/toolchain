package com.mycompany.bpide;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/**
 * V9 #891 (25-nov) — la prueba de los guiones ({@link Guion}, {@link Guiones}), con los comandos de la placa de verdad
 * ({@link PicoExplorer}, sin enseñarlo) contra el micro simulado:
 * <pre>java -cp BpIde-8.1.jar com.mycompany.bpide.GuionSmoke [ruta de bpvm-sim.exe]</pre>
 * Lo que se mira: la forma de una línea, ida y vuelta; que un guion con errores los diga todos, con su línea, y no haga nada;
 * que se haga en orden y pare en el primero que falla; «rw» (el de una línea y el del guion) y «mostrar»; que lo que no se
 * deshace se pregunte, diciendo qué línea lo pide; pararlo; uno a la vez; la IA, por su servidor y sin salir de su carpeta; y
 * que los de la instalación valgan, y sus packs estén. Sale con 0 si todo va bien.
 */
public final class GuionSmoke {

    private static final int PUERTO_SIM = 5199;
    private static final int PUERTO_IA = 5198;
    private static int fallos = 0;

    private static void mira(boolean ok, String que) {
        System.out.println((ok ? "ok    " : "FALLO ") + que);
        if (!ok) fallos++;
    }

    private static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Comandos cs;
    private static Path tmp;

    private static Resultado haz(String id, Object... kv) throws Exception {
        return cs.ejecuta(id, p(kv)).get(60, TimeUnit.SECONDS);
    }

    /** Un guion con ese texto, en un fichero temporal. */
    private static String guion(String nombre, String... lineas) throws Exception {
        Path f = tmp.resolve(nombre + ".guion");
        Files.write(f, String.join("\n", lineas).getBytes(StandardCharsets.UTF_8));
        return f.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pasos(Resultado r) {
        Object v = r.datos().get("pasos");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        File sim = new File(args.length > 0 ? args[0] : "bpgenvm-c/build/bpvm-sim.exe");
        tmp = Files.createTempDirectory("guiones");
        laForma();
        if (!sim.isFile()) { System.out.println("(skip: no está " + sim + ")"); return; }
        Process proc = new ProcessBuilder(sim.getAbsolutePath(), "--port=" + PUERTO_SIM, "--no-screen",
                "--flash-file=" + tmp.resolve("sim.flash"), "--fs=" + tmp.resolve("sim.fs"))
                .redirectErrorStream(true).redirectOutput(tmp.resolve("sim.log").toFile()).start();
        try {
            prueba();
        } finally {
            proc.destroy();
        }
        System.out.println(fallos == 0 ? "GuionSmoke: TODO BIEN" : "GuionSmoke: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }

    /* ===== la forma de una línea ===== */

    private static void forma(String linea, String espera) {
        String sale;
        try {
            Guion.Linea l = Guion.parte(linea);
            sale = l.comando + " " + l.parametros;
        } catch (IllegalArgumentException e) {
            sale = "no: " + e.getMessage();
        }
        mira(sale.equals(espera), "«" + linea + "» → " + sale);
    }

    private static void laForma() {
        forma("compilar", "compilar {}");
        forma("  entorno_poner   clave=log\tvalor=1 ", "entorno_poner {clave=log, valor=1}");
        forma("entorno_poner clave=lcd valor=st7789,spi:2,a=b rw", "entorno_poner {clave=lcd, valor=st7789,spi:2,a=b, rw=true}");
        forma("abrir fichero=\"C:\\dos palabras\\x.bp\"", "abrir {fichero=C:\\dos palabras\\x.bp}");
        forma("x v=\"dice \"\"hola\"\"\" w=\"\"", "x {v=dice \"hola\", w=}");
        forma("placa_subir ficheros=a.txt ficheros=b.txt ficheros=c.txt", "placa_subir {ficheros=[a.txt, b.txt, c.txt]}");
        forma("x v=\"sin cerrar", "no: faltan las comillas de cierre");
        forma("x =1", "no: a «=1» le falta el nombre del parámetro");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", "dos palabras");
        m.put("b", "di \"hola\"");
        m.put("c", true);
        m.put("d", java.util.Arrays.asList("x", "y z"));
        m.put("e", "");
        String t = Guion.escribe("orden", m);
        mira(t.equals("orden a=\"dos palabras\" b=\"di \"\"hola\"\"\" c=true d=x d=\"y z\" e=\"\""), "escribe: " + t);
        mira(Guion.parte(t).parametros.toString().equals("{a=dos palabras, b=di \"hola\", c=true, d=[x, y z], e=}"),
                "y de vuelta: " + Guion.parte(t).parametros);
    }

    /* ===== con los comandos de la placa, contra el simulado ===== */

    private static void prueba() throws Exception {
        cs = new Comandos(null);
        List<String> preguntas = new ArrayList<>();
        final boolean[] contesta = { true };
        cs.setPreguntador(t -> { preguntas.add(t); return contesta[0]; });   // sin diálogos en la pantalla
        final PicoExplorer[] ex = new PicoExplorer[1];
        SwingUtilities.invokeAndWait(() -> ex[0] = new PicoExplorer(cs));
        List<String> consola = java.util.Collections.synchronizedList(new ArrayList<>());
        new Guiones(cs, consola::add, null);
        cs.registra("dialogo", "Uno de la ventana", q -> CompletableFuture.completedFuture(Resultado.bien("no"))).soloVentana();
        final AtomicReference<CompletableFuture<Resultado>> pendiente = new AtomicReference<>();
        cs.registra("lento", "Uno que tarda", q -> { pendiente.set(new CompletableFuture<>()); return pendiente.get(); });

        // 1. con errores: todos, con su línea, y no se hace nada
        Resultado r = haz("guion", "fichero", guion("malo", "// uno malo", "entorno_poner clave=a valor=1", "no_hay x=1",
                "entorno_poner clave=b", "", "entorno_poner clave=c valr=1", "dialogo", "guion nombre=x",
                "pack_borrar offset=mucho", "placa_reiniciar reconectar=puede", "pack_grabar pack", "x=\"", "packs_listar"));
        String e = r.mensaje;
        mira(!r.ok && e.contains("línea 3: no hay ningún comando «no_hay»") && e.contains("línea 4: entorno_poner necesita «valor»")
                && e.contains("línea 6: entorno_poner no tiene el parámetro [valr]") && e.contains("línea 7: «dialogo» abre un")
                && e.contains("línea 8: un guion no llama a otro") && e.contains("línea 9: pack_borrar: «offset» es un número")
                && e.contains("línea 10: placa_reiniciar: «reconectar» es true o false")
                && e.contains("línea 11: a «pack» le falta el valor") && e.contains("línea 12: faltan las comillas"),
                "con errores, los dice todos: " + e.replace("\n", " | "));
        mira(pasos(r).isEmpty() && consola.size() == 1, "y no hace nada");
        mira(!haz("guion", "fichero", guion("vacio", "// nada")).ok, "uno sin comandos no vale");
        mira(!haz("guion").ok && !haz("guion", "nombre", "a", "fichero", "b").ok, "ni sin decir cuál, ni diciendo los dos");
        r = haz("guion", "nombre", "no_esta");
        mira(!r.ok && r.mensaje.contains("no hay ningún guion «no_esta»"), "uno que no hay: " + r.mensaje);
        r = haz("guion", "fichero", tmp.resolve("no.guion").toString());
        mira(!r.ok && r.mensaje.contains("no existe el fichero"), "un fichero que no existe: " + r.mensaje);

        // 2. sin placa, para en la primera
        r = haz("guion", "fichero", guion("una", "entorno_listar", "packs_listar"));
        mira(!r.ok && r.mensaje.contains("línea 1 (entorno_listar): no hay ninguna placa") && pasos(r).size() == 1,
                "sin placa: " + r.mensaje.replace("\n", " | "));

        // 3. conectado: en orden, con sus comentarios, su BOM y sus CRLF
        Resultado con = null;
        for (int i = 0; i < 40 && (con == null || !con.ok); i++) {
            con = haz("placa_conectar", "tipo", "vm", "donde", "127.0.0.1:" + PUERTO_SIM, "conectar", true);
            if (!con.ok) Thread.sleep(200);
        }
        mira(con.ok, "conectado al simulado: " + con.mensaje);
        BpvmClient cli = ex[0].debugClient();
        cli.partApply(new HashMap<String, Long>() {{ put("fs", 0x100000L); put("packs", 0x100000L); }}, 8000);
        Path f = tmp.resolve("bom.guion");
        Files.write(f, ("\uFEFF// con BOM y CRLF\r\n\r\nplaca_comprobar micro=host\r\n   // otro comentario\r\n"
                + "entorno_poner clave=log valor=1\r\nentorno_poner clave=lcd valor=\"dos palabras\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        consola.clear();
        r = haz("guion", "fichero", f.toString());
        mira(r.ok && pasos(r).size() == 3 && "1".equals(cli.envGet("log", 8000))
                && "dos palabras".equals(cli.envGet("lcd", 8000)), "en orden: " + r);
        mira(consola.get(0).equals("[guion] bom — con BOM y CRLF (3 pasos)")
                && consola.contains("[guion] 2/3 entorno_poner clave=log valor=1")
                && consola.get(consola.size() - 1).startsWith("[guion] el guion bom, hecho: 3 pasos"),
                "la consola: " + consola);

        // 4. otra placa: no se toca
        r = haz("guion", "fichero", guion("otra", "placa_comprobar micro=esp32c5", "entorno_poner rw clave=log valor=9"));
        mira(!r.ok && r.mensaje.contains("la placa conectada es un host, no un esp32c5") && "1".equals(cli.envGet("log", 8000)),
                "otra placa: " + r.mensaje.replace("\n", " | "));

        // 5. una variable con otro valor: pregunta (con la línea); con un no, para; con rw (línea o guion), no pregunta
        String cambia = guion("cambia", "// cambia", "entorno_poner clave=log valor=2", "entorno_poner clave=nueva valor=1");
        contesta[0] = false;
        r = haz("guion", "fichero", cambia);
        mira(!r.ok && preguntas.size() == 1 && preguntas.get(0).startsWith("El guion cambia (línea 2) pide CAMBIAR")
                && preguntas.get(0).contains("log: de «1» a «2»") && "1".equals(cli.envGet("log", 8000))
                && Integer.valueOf(2).equals(r.datos().get("linea")), "pregunta, y con un no, para: " + preguntas);
        r = haz("guion", "fichero", cambia, "rw", true);
        mira(r.ok && preguntas.size() == 1 && "2".equals(cli.envGet("log", 8000)) && "1".equals(cli.envGet("nueva", 8000)),
                "con rw, no pregunta: " + r.mensaje);
        r = haz("guion", "fichero", guion("linea_rw", "entorno_poner rw clave=log valor=3", "entorno_borrar clave=no_esta",
                "entorno_borrar clave=nueva"));
        mira(r.ok && preguntas.size() == 1 && "3".equals(cli.envGet("log", 8000)) && pasos(r).size() == 3,
                "el rw de una línea, y borrar la que no está: " + r.mensaje);

        // 6. mostrar: se enseña, y con un no, no se hace
        r = haz("guion", "fichero", cambia, "mostrar", true);
        mira(!r.ok && preguntas.size() == 2 && preguntas.get(1).startsWith("EJECUTAR el guion cambia (cambia):")
                && preguntas.get(1).contains("\n   entorno_poner clave=log valor=2") && "3".equals(cli.envGet("log", 8000)),
                "mostrar, con un no: " + r.mensaje + " | " + preguntas.get(1).replace("\n", " | "));
        contesta[0] = true;
        r = haz("guion", "fichero", cambia, "mostrar", true, "rw", true);
        mira(r.ok && preguntas.size() == 3 && "2".equals(cli.envGet("log", 8000)), "y con un sí: " + r.mensaje);

        // 7. los packs: formatear pregunta, diga lo que diga el guion
        String packs = guion("packs", "packs_formatear", "pack_grabar pack=Stdlib", "pack_grabar pack=DriverSpi");
        contesta[0] = false;
        r = haz("guion", "fichero", packs, "rw", true);
        mira(!r.ok && preguntas.get(3).startsWith("El guion packs (línea 1) pide FORMATEAR") && pasos(r).size() == 1,
                "formatear pregunta siempre: " + r.mensaje.replace("\n", " | "));
        contesta[0] = true;
        r = haz("guion", "fichero", packs);
        Resultado l = haz("packs_listar");
        mira(r.ok && pasos(r).size() == 3 && l.mensaje.startsWith("2 packs (2 activos)"), "y con un sí: " + l.mensaje);

        // 8. uno a la vez, y pararlo
        CompletableFuture<Resultado> uno = cs.ejecuta("guion", p("fichero", guion("largo", "lento", "packs_listar")));
        for (int i = 0; i < 100 && pendiente.get() == null; i++) Thread.sleep(20);
        r = haz("guion", "fichero", cambia);
        mira(!r.ok && r.mensaje.contains("ya hay un guion en marcha (largo)"), "uno a la vez: " + r.mensaje);
        r = haz("guion_parar");
        pendiente.get().complete(Resultado.bien("ya"));
        Resultado u = uno.get(20, TimeUnit.SECONDS);
        mira(r.ok && !u.ok && u.mensaje.contains("parado: hechos 1 de 2") && pasos(u).size() == 1, "pararlo: " + u.mensaje);
        mira(!haz("guion_parar").ok, "y ya no hay ninguno");

        // 9. la IA: por su servidor; las rutas, dentro de su carpeta también dentro de un guion
        Path suya = Files.createTempDirectory("ia");
        ServidorIa ia = new ServidorIa(cs, consola::add, 20000);
        ia.arranca(PUERTO_IA, suya);
        try {
            List<String> hay = EntornoSmoke.herramientas(PUERTO_IA);
            mira(hay.contains("guion") && hay.contains("guiones_listar") && hay.contains("guion_parar")
                    && hay.contains("placa_comprobar"), "la IA los ve");
            Files.write(suya.resolve("bien.guion"), "entorno_poner rw clave=log valor=5".getBytes(StandardCharsets.UTF_8));
            Files.write(suya.resolve("fuera.guion"), "packs_listar\npack_grabar fichero=../x.pack"
                    .getBytes(StandardCharsets.UTF_8));
            consola.clear();
            Map<?, ?> rr = EntornoSmoke.llama(PUERTO_IA, "guion", p("fichero", "bien.guion"));
            mira(Boolean.FALSE.equals(rr.get("isError")) && "5".equals(cli.envGet("log", 8000)), "ejecuta uno: "
                    + rr.get("content"));
            mira(consola.get(0).startsWith("[ia] guion fichero="), "y la consola lo dice con la forma de un guion: "
                    + consola.get(0).trim());
            rr = EntornoSmoke.llama(PUERTO_IA, "guion", p("fichero", "fuera.guion"));
            mira(Boolean.TRUE.equals(rr.get("isError")) && String.valueOf(rr.get("content")).contains("fuera de la carpeta"),
                    "lo que lleva, también dentro de su carpeta: " + rr.get("content"));
            rr = EntornoSmoke.llama(PUERTO_IA, "guion", p("fichero", tmp.resolve("bom.guion").toString()));
            mira(Boolean.TRUE.equals(rr.get("isError")), "y el guion: " + rr.get("content"));
            rr = EntornoSmoke.llama(PUERTO_IA, "guion", p("fichero", "bien.guion", "quien", "x"));
            mira(Boolean.TRUE.equals(rr.get("isError")), "no puede decir quién es: " + rr.get("content"));
            rr = EntornoSmoke.llama(PUERTO_IA, "entorno_poner", p("clave", "log", "valor", "6"));
            mira(preguntas.get(preguntas.size() - 1).startsWith("La IA pide CAMBIAR"), "y la pregunta dice que lo pide ella");
            rr = EntornoSmoke.llama(PUERTO_IA, "guiones_listar", p());
            mira(Boolean.FALSE.equals(rr.get("isError")), "los de la instalación: " + rr.get("content"));
        } finally {
            ia.para();
        }

        // 10. los de la instalación: valen, y sus packs están
        Path dir = IdePrefs.guionesDir();
        mira(dir != null, "hay carpeta de guiones: " + dir);
        if (dir != null) {
            File[] gs = dir.toFile().listFiles((d, n) -> n.endsWith(".guion"));
            for (File g : gs) {
                try {
                    Guion x = Guion.lee(g.toPath(), cs, null);
                    String mal = x.descripcion.isEmpty() ? "sin descripción" : "";
                    if (!x.pasos.get(0).comando.id.equals("placa_comprobar")) mal += " no empieza por placa_comprobar";
                    for (Guion.Paso paso : x.pasos) {
                        Object pk = paso.parametros.get("pack");
                        if (pk != null && PacksPlaca.deLaInstalacion(pk.toString()) == null) mal += " falta el pack " + pk;
                    }
                    mira(mal.isEmpty(), x.nombre + " — " + x.descripcion + " (" + x.pasos.size() + " pasos)" + mal);
                } catch (Guion.Malo m) {
                    mira(false, g.getName() + ": " + m.getMessage());
                }
            }
        }
        haz("placa_conectar", "conectar", false);
    }
}
