package com.mycompany.bpide;

import edu.bpgenvm.util.Json;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * V9 #876 (25-nov) — la prueba de los comandos del entorno ({@link EntornoPlaca}) contra el micro simulado, que habla el
 * mismo protocolo que una placa, sin enseñar ninguna ventana:
 * <pre>java -cp BpIde-8.1.jar com.mycompany.bpide.EntornoSmoke [ruta de bpvm-sim.exe]</pre>
 * Lo que se mira: listar, poner y borrar, con lo que había antes; lo que no se deja (las de las particiones, también en
 * mayúsculas, y lo que la placa no guardaría bien) sin tocar la placa; un valor con comillas y acentos, de vuelta igual;
 * que el diálogo abierto vea un cambio que no ha hecho él; y la IA, por su servidor: las tres herramientas, y sin poder
 * pasar lo que es de la ventana. Sale con 0 si todo va bien.
 */
public final class EntornoSmoke {

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

    private static Resultado haz(Comandos cs, String id, Object... kv) throws Exception {
        return cs.ejecuta(id, p(kv)).get(20, TimeUnit.SECONDS);
    }

    /** Las variables de un resultado, «clave=valor» cada una. */
    private static List<String> vars(Resultado r, String dato) {
        List<String> out = new ArrayList<>();
        Object v = r.datos().get(dato);
        if (v instanceof List) {
            for (Object o : (List<?>) v) out.add(((Map<?, ?>) o).get("clave") + "=" + ((Map<?, ?>) o).get("valor"));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        File sim = new File(args.length > 0 ? args[0] : "bpgenvm-c/build/bpvm-sim.exe");
        if (!sim.isFile()) { System.out.println("(skip: no está " + sim + ")"); return; }
        File tmp = Files.createTempDirectory("entorno").toFile();
        Process proc = new ProcessBuilder(sim.getAbsolutePath(), "--port=" + PUERTO_SIM,
                "--flash-file=" + new File(tmp, "sim.flash").getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(new File(tmp, "sim.log")).start();
        BpvmClient cli = new BpvmClient();
        try {
            boolean si = false;
            for (int i = 0; i < 80 && !si; i++) {
                try { cli.connectRemote("127.0.0.1", PUERTO_SIM); si = true; } catch (Exception e) { Thread.sleep(150); }
            }
            if (!si) { System.out.println("FALLO: el micro simulado no acepta conexión"); System.exit(1); }
            prueba(cli);
        } finally {
            cli.close();
            proc.destroy();
        }
        System.out.println(fallos == 0 ? "EntornoSmoke: TODO BIEN" : "EntornoSmoke: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }

    private static void prueba(BpvmClient cli) throws Exception {
        AtomicReference<BpvmClient> conexion = new AtomicReference<>();
        List<String> consola = java.util.Collections.synchronizedList(new ArrayList<>());
        Comandos cs = new Comandos(null);
        EntornoPlaca entorno = new EntornoPlaca(cs, conexion::get, consola::add);

        // 1. sin placa
        Resultado r = haz(cs, "entorno_listar");
        mira(!r.ok && r.mensaje.contains("no hay ninguna placa conectada"), "sin placa: " + r.mensaje);
        conexion.set(cli);

        // 2. con los tamaños de las particiones puestos (lo que hace «Aplicar tamaños»)
        Map<String, Long> partes = new HashMap<>();
        partes.put("fs", 0x100000L);
        partes.put("packs", 0x100000L);
        cli.partApply(partes, 8000);
        r = haz(cs, "entorno_listar");
        mira(r.ok && vars(r, "variables").stream().noneMatch(s -> s.startsWith("part."))
                && vars(r, "de_particiones").size() >= 1, "listar: las de las particiones, aparte: " + r);
        final int habia = vars(r, "variables").size();

        // 3. poner: no estaba; estaba; ya valía eso (y en mayúsculas es la misma)
        r = haz(cs, "entorno_poner", "clave", "log", "valor", "1");
        mira(r.ok && "log".equals(r.datos().get("clave")) && !r.datos().containsKey("antes")
                && vars(r, "variables").contains("log=1") && r.mensaje.startsWith("log=1 (no estaba)"), "poner: " + r);
        mira("1".equals(cli.envGet("log", 8000)), "la placa la tiene");
        r = haz(cs, "entorno_poner", "clave", "log", "valor", "0");
        mira(r.ok && "1".equals(r.datos().get("antes")) && "0".equals(r.datos().get("valor")), "cambiar, con lo de antes: " + r);
        int dichas = consola.size();
        r = haz(cs, "entorno_poner", "clave", "LOG", "valor", "0");
        mira(r.ok && r.mensaje.contains("ya valía 0") && consola.size() == dichas, "ya valía eso (LOG es log): " + r.mensaje);
        r = haz(cs, "entorno_poner", "clave", "  packRam ", "valor", " 512 ");
        mira(r.ok && vars(r, "variables").contains("packRam=512"), "sin los espacios de alrededor: " + r.mensaje);

        // 4. un valor con comillas, barras, acentos y «=»: de vuelta, igual
        String raro = "st7789,seq:C:\\a\\\"b\",ñ=1";
        r = haz(cs, "entorno_poner", "clave", "lcd", "valor", raro);
        mira(r.ok && raro.equals(cli.envGet("lcd", 8000)), "un valor raro, de vuelta igual: " + r.mensaje);

        // 5. lo que no se deja, sin tocar la placa
        List<String> antes = vars(haz(cs, "entorno_listar"), "variables");
        String[][] no = {
            { "part.fs.size", "4096", "particiones" }, { "PART.FS.SIZE", "4096", "particiones" },
            { "", "1", "falta la clave" }, { "a b", "1", "espacios" }, { "a=b", "1", "espacios" },
            { "log", "", "falta el valor" }, { "log", "uno\ndos", "una línea" },
            { repite('k', 64), "1", "demasiado larga" }, { "log", repite('v', 192), "demasiado largo" } };
        for (String[] c : no) {
            r = haz(cs, "entorno_poner", "clave", c[0], "valor", c[1]);
            mira(!r.ok && r.mensaje.contains(c[2]), "no se deja: " + r.mensaje);
        }
        r = haz(cs, "entorno_borrar", "clave", "Part.fs.size");
        mira(!r.ok && r.mensaje.contains("particiones"), "ni borrarla: " + r.mensaje);
        mira(antes.equals(vars(haz(cs, "entorno_listar"), "variables")), "y la placa, como estaba: " + antes);
        r = haz(cs, "entorno_poner", "clave", repite('k', 63), "valor", repite('v', 191));
        mira(r.ok, "lo más largo que cabe, sí: " + (r.ok ? "63 y 191 bytes" : r.mensaje));
        haz(cs, "entorno_borrar", "clave", repite('k', 63));

        // 6. borrar, y borrar lo que no está
        r = haz(cs, "entorno_borrar", "clave", "Log");
        mira(r.ok && "0".equals(r.datos().get("antes")) && !vars(r, "variables").contains("log=0")
                && "log".equals(r.datos().get("clave")), "borrar, con lo que valía: " + r);
        r = haz(cs, "entorno_borrar", "clave", "log");
        mira(!r.ok && r.mensaje.contains("no hay ninguna variable «log»"), "y otra vez, no está: " + r.mensaje);

        // 7. el diálogo, sin enseñarlo: lo que hay al abrirlo, y lo que cambia otro
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("      (sin pantalla: el diálogo no se mira)");
        } else {
            final EnvDialog[] d = new EnvDialog[1];
            final int[] veces = { 0 };
            SwingUtilities.invokeAndWait(() -> d[0] = new EnvDialog(null, cs, entorno, () -> veces[0]++));
            JTable t = dentro(d[0].getContentPane(), JTable.class);
            JCheckBox psram = dentro(d[0].getContentPane(), JCheckBox.class);
            final int f0 = espera(() -> t.getRowCount() > 0 ? t.getRowCount() : -1);
            mira(f0 == vars(haz(cs, "entorno_listar"), "variables").size(), "el diálogo, con lo que hay: " + f0 + " filas");
            haz(cs, "entorno_poner", "clave", "psram", "valor", "1");
            mira(espera(() -> psram.isSelected() ? 1 : -1) == 1 && t.getRowCount() == f0 + 1 && veces[0] == 0,
                    "lo que pone otro se ve en el diálogo abierto (y la casilla)");
            SwingUtilities.invokeAndWait(psram::doClick);   // la casilla, por su comando
            mira(espera(() -> veces[0] == 1 ? 1 : -1) == 1 && "0".equals(cli.envGet("psram", 8000)),
                    "la casilla de la PSRAM, por el comando");
            SwingUtilities.invokeAndWait(() -> d[0].dispose());
            haz(cs, "entorno_poner", "clave", "psram", "valor", "1");
            SwingUtilities.invokeAndWait(() -> { });
            mira(!psram.isSelected(), "cerrado, ya no mira");
            haz(cs, "entorno_borrar", "clave", "psram");
        }

        // 8. la IA: las tres herramientas, y sólo lo documentado
        ServidorIa ia = new ServidorIa(cs, s -> { }, 20000);
        ia.arranca(PUERTO_IA, Files.createTempDirectory("ia"));
        try {
            List<String> nombres = herramientas(PUERTO_IA);
            mira(nombres.contains("entorno_listar") && nombres.contains("entorno_poner")
                    && nombres.contains("entorno_borrar"), "la IA ve las tres: " + nombres);
            Map<?, ?> rr = llama(PUERTO_IA, "entorno_poner", p("clave", "display", "valor", "st7701"));
            mira(Boolean.FALSE.equals(rr.get("isError")) && "st7701".equals(cli.envGet("display", 8000)),
                    "la IA pone una: " + rr.get("content"));
            rr = llama(PUERTO_IA, "entorno_poner", p("clave", "display", "valor", "x", "sobre", "algo"));
            mira(Boolean.TRUE.equals(rr.get("isError")) && "st7701".equals(cli.envGet("display", 8000)),
                    "lo que es de la ventana no lo puede pasar: " + rr.get("content"));
            rr = llama(PUERTO_IA, "entorno_poner", p("clave", "display"));
            mira(Boolean.TRUE.equals(rr.get("isError")), "sin el valor, no: " + rr.get("content"));
            rr = llama(PUERTO_IA, "entorno_poner", p("clave", "part.packs.size", "valor", "1"));
            mira(Boolean.TRUE.equals(rr.get("isError")), "las de las particiones, tampoco: " + rr.get("content"));
            rr = llama(PUERTO_IA, "entorno_borrar", p("clave", "display"));
            mira(Boolean.FALSE.equals(rr.get("isError")), "y la borra: " + rr.get("content"));
        } finally {
            ia.para();
        }
        haz(cs, "entorno_borrar", "clave", "packRam");
        haz(cs, "entorno_borrar", "clave", "lcd");
        r = haz(cs, "entorno_listar");
        mira(vars(r, "variables").size() == habia, "al final, las del principio: " + vars(r, "variables"));
    }

    private static String repite(char c, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(c);
        return s.toString();
    }

    private interface Cuenta { int vale() throws Exception; }

    /** El valor, cuando deje de ser -1 (en el EDT), o -1 a los 5 s. */
    private static int espera(Cuenta c) throws Exception {
        for (int i = 0; i < 100; i++) {
            final int[] v = { -1 };
            final Exception[] mal = { null };
            SwingUtilities.invokeAndWait(() -> { try { v[0] = c.vale(); } catch (Exception e) { mal[0] = e; } });
            if (mal[0] != null) throw mal[0];
            if (v[0] != -1) return v[0];
            Thread.sleep(50);
        }
        return -1;
    }

    private static <T> T dentro(java.awt.Container c, Class<T> que) {
        for (java.awt.Component h : c.getComponents()) {
            if (que.isInstance(h)) return que.cast(h);
            if (h instanceof java.awt.Container) {
                T t = dentro((java.awt.Container) h, que);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Las herramientas que ve la IA en ese servidor. (También las usa PacksSmoke.) */
    static List<String> herramientas(int puerto) throws Exception {
        List<String> nombres = new ArrayList<>();
        Map<String, Object> lista = pide(puerto, "tools/list", new HashMap<>());
        for (Object o : (List<?>) ((Map<?, ?>) lista.get("result")).get("tools")) {
            nombres.add(String.valueOf(((Map<?, ?>) o).get("name")));
        }
        return nombres;
    }

    static Map<?, ?> llama(int puerto, String nombre, Map<String, Object> argumentos) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("name", nombre);
        params.put("arguments", argumentos);
        return (Map<?, ?>) pide(puerto, "tools/call", params).get("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pide(int puerto, String metodo, Map<String, Object> params) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", 1);
        m.put("method", metodo);
        m.put("params", params);
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + puerto + "/mcp").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(Json.write(m).getBytes(StandardCharsets.UTF_8)); }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        }
        return (Map<String, Object>) Json.parse(new String(b.toByteArray(), StandardCharsets.UTF_8));
    }
}
