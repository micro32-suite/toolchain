package com.mycompany.bpide;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * V9 #875 (25-nov) — la prueba de los comandos de los packs ({@link PacksPlaca}) contra el micro simulado, sin enseñar
 * ninguna ventana:
 * <pre>java -cp BpIde-8.1.jar com.mycompany.bpide.PacksSmoke [ruta de bpvm-sim.exe]</pre>
 * Lo que se mira: listar, grabar (por su nombre y un fichero), los ficheros de uno, dos con el mismo nombre, retirar y
 * formatear; lo que no se deja; que formatear, pedido por su nombre (la IA), se lo pregunte antes a la persona —con la placa
 * y lo que se pierde— y con un no no lo haga; el panel abierto, que ve lo que hace otro; y la IA por su servidor. Lo de la
 * placa que se reinicia (motores cargados) es de una placa. Sale con 0 si todo va bien.
 */
public final class PacksSmoke {

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
        return cs.ejecuta(id, p(kv)).get(60, TimeUnit.SECONDS);
    }

    /** Los packs activos de un resultado, por su nombre. */
    private static List<String> activos(Resultado r) {
        List<String> out = new ArrayList<>();
        Object v = r.datos().get("packs");
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (Boolean.TRUE.equals(m.get("activo"))) out.add(String.valueOf(m.get("nombre")));
            }
        }
        return out;
    }

    private static long libre(Resultado r) {
        return ((Number) ((Map<?, ?>) r.datos().get("zona")).get("libre")).longValue();
    }

    public static void main(String[] args) throws Exception {
        File sim = new File(args.length > 0 ? args[0] : "bpgenvm-c/build/bpvm-sim.exe");
        if (!sim.isFile()) { System.out.println("(skip: no está " + sim + ")"); return; }
        File tmp = Files.createTempDirectory("packs").toFile();
        Process proc = new ProcessBuilder(sim.getAbsolutePath(), "--port=" + PUERTO_SIM,
                "--flash-file=" + new File(tmp, "sim.flash").getAbsolutePath(),
                "--packs-file=" + new File(tmp, "sim.packs").getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(new File(tmp, "sim.log")).start();
        BpvmClient cli = new BpvmClient();
        try {
            boolean si = false;
            for (int i = 0; i < 80 && !si; i++) {
                try { cli.connectRemote("127.0.0.1", PUERTO_SIM); si = true; } catch (Exception e) { Thread.sleep(150); }
            }
            if (!si) { System.out.println("FALLO: el micro simulado no acepta conexión"); System.exit(1); }
            prueba(cli, tmp);
        } finally {
            cli.close();
            proc.destroy();
        }
        System.out.println(fallos == 0 ? "PacksSmoke: TODO BIEN" : "PacksSmoke: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }

    private static void prueba(BpvmClient cli, File tmp) throws Exception {
        AtomicReference<BpvmClient> conexion = new AtomicReference<>();
        List<String> consola = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> preguntas = java.util.Collections.synchronizedList(new ArrayList<>());
        final boolean[] contesta = { false };
        Comandos cs = new Comandos(null);
        cs.setPreguntador(t -> { preguntas.add(t); return contesta[0]; });   // sin diálogos en la pantalla
        PacksPlaca packs = new PacksPlaca(cs, conexion::get, () -> Despliegue.Perfil.NINGUNO,
                () -> "placa de prueba en el simulado",
                (alVolver, alFallar) -> alFallar.accept("no hay explorador en la prueba"), consola::add);
        List<String> avances = java.util.Collections.synchronizedList(new ArrayList<>());
        packs.mira(new PacksPlaca.Mirador() {
            @Override public void zona(BpvmClient.PackTable t) { avances.add("zona " + t.packs.size()); }
            @Override public void avance(String que) { avances.add(que); }
        });

        // 0. el motor que le toca a cada placa, por lo que publica (V9 #892): la P4 y la C5 son las dos «riscv»
        String[][] placas = { { "40", "ARMV8M_MAIN_SOFTFP", "ARMV8" }, { "243", "RV32IMAFC_ILP32F", "RISCV" },
            { "243", "RV32IMC_ILP32", "RV32IMC" }, { "94", "", "nada" }, { "0", "", "nada" }, { "243", "OTRA", "nada" },
            { "40", "", "ARMV8" } };
        for (String[] x : placas) {
            basicplus.frontend.NpackReloc.Destino d = PacksPlaca.destino(
                    new Despliegue.Perfil(Integer.parseInt(x[0]), 0, x[1], 9));
            mira((d == null ? "nada" : d.sufijo).equals(x[2]), "arch " + x[0] + ", receta «" + x[1] + "»: " + x[2]);
        }
        for (String[] x : new String[][] { { "Gui", "ARMV8,RISCV,RV32IMC" }, { "GuiMini", "RV32IMC" },
            { "DriverSpi", "RV32IMC" }, { "SQLite", "ARMV8,RISCV" } }) {
            byte[] img = Files.readAllBytes(PacksPlaca.deLaInstalacion(x[0]).toPath());
            StringBuilder con = new StringBuilder();
            for (String[] pl : new String[][] { placas[0], placas[1], placas[2] }) {
                basicplus.frontend.PackBurn.Preparado prep = basicplus.frontend.PackBurn.podar(img,
                        PacksPlaca.destino(new Despliegue.Perfil(Integer.parseInt(pl[0]), 0, pl[1], 9)));
                if (prep.necesitaDireccion) con.append(con.length() > 0 ? "," : "").append(pl[2]);
            }
            mira(con.toString().equals(x[1]), x[0] + " lleva motor para " + con);
        }

        // 1. sin placa
        for (String id : new String[] { "packs_listar", "packs_formatear" }) {
            Resultado r = haz(cs, id);
            mira(!r.ok && r.mensaje.contains("no hay ninguna placa conectada"), id + ", sin placa: " + r.mensaje);
        }
        mira(preguntas.isEmpty(), "y sin placa no se pregunta nada");
        conexion.set(cli);

        // 2. la zona, vacía (una partición recién hecha puede traer restos: se estrena)
        Map<String, Long> partes = new HashMap<>();
        partes.put("fs", 0x100000L);
        partes.put("packs", 0x200000L);
        cli.partApply(partes, 8000);
        Resultado r = haz(cs, "packs_listar");
        mira(r.ok && activos(r).isEmpty() && libre(r) > 0, "listar, vacía: " + r.mensaje);
        final long total = libre(r);

        // 3. grabar uno de la instalación, por su nombre
        r = haz(cs, "pack_grabar", "pack", "Stdlib");
        mira(r.ok && activos(r).contains("Stdlib") && "Stdlib".equals(r.datos().get("pack")) && libre(r) < total
                && !r.datos().containsKey("habia"), "grabar Stdlib: " + r.mensaje);
        mira(consola.stream().anyMatch(s -> s.startsWith("[Packs] grabado Stdlib.pack")), "lo dice la consola");
        mira(avances.stream().anyMatch(s -> s.contains("100 %")) && avances.stream().anyMatch(s -> s.equals("zona 1")),
                "con su avance, y la zona después");
        final long offViejo = ((Number) r.datos().get("offset")).longValue();

        // 4. sus ficheros
        r = haz(cs, "pack_ficheros", "pack", "Stdlib");
        mira(r.ok && String.valueOf(r.datos().get("ficheros")).contains("Core.mod")
                && ((Number) r.datos().get("total")).intValue() > ((List<?>) r.datos().get("ficheros")).size(),
                "los ficheros de Stdlib, y los que la placa no lista: " + r.mensaje);
        r = haz(cs, "pack_ficheros", "offset", (int) offViejo);
        mira(r.ok, "y por su offset: " + r.mensaje);
        r = haz(cs, "pack_ficheros", "pack", "NoEsta");
        mira(!r.ok && r.mensaje.contains("no hay ningún pack activo"), "de uno que no está: " + r.mensaje);

        // 5. otra vez: ya había uno
        r = haz(cs, "pack_grabar", "pack", "stdlib");
        mira(r.ok && r.datos().containsKey("habia") && java.util.Collections.frequency(activos(r), "Stdlib") == 2,
                "otra vez (en minúsculas): dice que ya había uno: " + r.datos().get("habia"));

        // 6. retirar: con dos, hay que decir cuál; el viejo, por su offset; otra vez, ya está; y el que queda, por su nombre
        r = haz(cs, "pack_borrar", "pack", "Stdlib");
        mira(!r.ok && r.mensaje.contains("hay 2 packs activos llamados «Stdlib»"), "dos con el mismo nombre: " + r.mensaje);
        r = haz(cs, "pack_borrar", "offset", offViejo);
        mira(r.ok && java.util.Collections.frequency(activos(r), "Stdlib") == 1, "el viejo, por su offset: " + r.mensaje);
        r = haz(cs, "pack_borrar", "offset", offViejo);
        mira(!r.ok && r.mensaje.contains("ya está retirado"), "otra vez: " + r.mensaje);
        r = haz(cs, "pack_borrar", "pack", "Stdlib");
        mira(r.ok && activos(r).isEmpty(), "el que queda, por su nombre: " + r.mensaje);
        r = haz(cs, "pack_borrar", "pack", "Stdlib");
        mira(!r.ok && r.mensaje.contains("no hay ningún pack activo"), "y ya no hay: " + r.mensaje);

        // 7. lo que no se deja
        r = haz(cs, "pack_grabar");
        mira(!r.ok && r.mensaje.contains("di uno de los dos"), "grabar, sin decir cuál: " + r.mensaje);
        r = haz(cs, "pack_grabar", "pack", "Stdlib", "fichero", "x.pack");
        mira(!r.ok && r.mensaje.contains("di uno de los dos"), "grabar, diciendo los dos: " + r.mensaje);
        r = haz(cs, "pack_grabar", "pack", "NoEsta");
        mira(!r.ok && r.mensaje.contains("los que hay: ") && r.mensaje.contains("Stdlib"), "uno que no hay: " + r.mensaje);
        r = haz(cs, "pack_grabar", "fichero", new File(tmp, "no.pack").getPath());
        mira(!r.ok && r.mensaje.contains("no existe"), "un fichero que no existe: " + r.mensaje);
        File falso = new File(tmp, "falso.pack");
        Files.write(falso.toPath(), "esto no es un pack".getBytes());
        r = haz(cs, "pack_grabar", "fichero", falso.getPath());
        mira(!r.ok, "un fichero que no es un pack: " + r.mensaje);

        // 8. un pack con motor, en una placa sin motor para él (el simulado): se graba sin nativo y lo dice
        r = haz(cs, "pack_grabar", "pack", "SQLite");
        mira(r.ok && activos(r).contains("SQLite") && r.mensaje.contains("podado")
                && consola.stream().anyMatch(s -> s.contains("SIN nativo")), "SQLite, sin nativo: " + r.mensaje);

        // 9. formatear, por su nombre: se pregunta, con la placa y lo que se pierde; con un no, nada
        r = haz(cs, "packs_formatear");
        mira(!r.ok && r.mensaje.contains("no lo ha autorizado") && activos(haz(cs, "packs_listar")).contains("SQLite"),
                "formatear, con un no: " + r.mensaje);
        mira(preguntas.size() == 1 && preguntas.get(0).contains("placa de prueba en el simulado")
                && preguntas.get(0).contains("Se borran TODOS sus packs: SQLite."), "la pregunta: "
                + (preguntas.isEmpty() ? "ninguna" : preguntas.get(0).replace("\n", " ")));
        contesta[0] = true;
        r = haz(cs, "packs_formatear");
        mira(r.ok && activos(r).isEmpty() && libre(r) == total, "formatear, con un sí: " + r.mensaje);

        // 10. el panel, sin enseñarlo: ve lo que hace otro
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("      (sin pantalla: el panel no se mira)");
        } else {
            final PacksPanel[] panel = new PacksPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new PacksPanel();
                panel[0].setComandos(cs, packs);
                panel[0].attach(cli);
            });
            haz(cs, "packs_listar");
            JTable t = dentro(panel[0], JTable.class);
            mira(t.getRowCount() == 0, "el panel, con la zona vacía");
            r = haz(cs, "pack_grabar", "pack", "Stdlib");
            SwingUtilities.invokeAndWait(() -> { });
            mira(r.ok && t.getRowCount() == 1 && "Stdlib".equals(t.getValueAt(0, 0)), "lo que graba otro se ve en el panel");
            List<JLabel> etiquetas = new ArrayList<>();
            todas(panel[0], etiquetas);
            mira(etiquetas.stream().anyMatch(l -> l.getText().startsWith("1 packs (1 activos)")), "y su resumen");
            SwingUtilities.invokeAndWait(() -> t.setRowSelectionInterval(0, 0));
            JTable fs = otra(panel[0], t);
            for (int i = 0; i < 60 && fs.getRowCount() == 0; i++) Thread.sleep(50);
            mira(fs.getRowCount() > 30, "al seleccionarlo, sus ficheros: " + fs.getRowCount());
            SwingUtilities.invokeAndWait(() -> panel[0].cierra());
            haz(cs, "pack_borrar", "pack", "Stdlib");
            SwingUtilities.invokeAndWait(() -> { });
            mira("activo".equals(t.getValueAt(0, 5)), "cerrado, ya no mira");
        }

        // 11. la IA, por su servidor
        ServidorIa ia = new ServidorIa(cs, s -> { }, 60000);
        ia.arranca(PUERTO_IA, Files.createTempDirectory("ia"));
        try {
            List<String> nombres = EntornoSmoke.herramientas(PUERTO_IA);
            mira(nombres.contains("packs_listar") && nombres.contains("pack_ficheros") && nombres.contains("pack_grabar")
                    && nombres.contains("pack_borrar") && nombres.contains("packs_formatear"), "la IA ve las cinco: " + nombres);
            Map<?, ?> rr = EntornoSmoke.llama(PUERTO_IA, "pack_grabar", p("pack", "DriverSpi"));
            mira(Boolean.FALSE.equals(rr.get("isError")), "la IA graba uno: " + corto(rr));
            rr = EntornoSmoke.llama(PUERTO_IA, "pack_grabar", p("fichero", "C:/Windows/x.pack"));
            mira(Boolean.TRUE.equals(rr.get("isError")) && corto(rr).contains("fuera de la carpeta"),
                    "un fichero de fuera de su carpeta, no: " + corto(rr));
            rr = EntornoSmoke.llama(PUERTO_IA, "packs_formatear", p("sobre", "x"));
            mira(Boolean.TRUE.equals(rr.get("isError")) && corto(rr).contains("no tiene el parámetro"),
                    "lo que es de la ventana no lo puede pasar: " + corto(rr));
            contesta[0] = false;
            int antes = preguntas.size();
            rr = EntornoSmoke.llama(PUERTO_IA, "packs_formatear", p());
            mira(Boolean.TRUE.equals(rr.get("isError")) && preguntas.size() == antes + 1, "formatear se pregunta: " + corto(rr));
        } finally {
            ia.para();
        }
    }

    private static String corto(Map<?, ?> r) {
        String s = String.valueOf(r.get("content"));
        return s.length() > 170 ? s.substring(0, 170) + "…" : s;
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

    private static void todas(java.awt.Container c, List<JLabel> out) {
        for (java.awt.Component h : c.getComponents()) {
            if (h instanceof JLabel && ((JLabel) h).getText() != null) out.add((JLabel) h);
            if (h instanceof java.awt.Container) todas((java.awt.Container) h, out);
        }
    }

    private static JTable otra(java.awt.Container c, JTable no) {
        for (java.awt.Component h : c.getComponents()) {
            if (h instanceof JTable && h != no) return (JTable) h;
            if (h instanceof java.awt.Container) {
                JTable t = otra((java.awt.Container) h, no);
                if (t != null) return t;
            }
        }
        return null;
    }
}
