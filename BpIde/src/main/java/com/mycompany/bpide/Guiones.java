package com.mycompany.bpide;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * V9 #891 (25-nov) — LOS GUIONES, ejecutados ({@link Guion}): los lanza la persona (el menú Guiones, o la consola) o la IA
 * (el comando {@code guion}), línea a línea, y para en la primera que falla. Cada línea es una llamada por su nombre, como
 * las de la IA: con los mismos límites (lo que no se deshace se le pregunta a la persona, lo lance quien lo lance).
 *
 * <p>Decidido (Eduardo, 25-nov): <i>«2) Añadir una opción sobreescribir (rewrite o abreviado rw) para que no pregunte cada
 * vez. 3) Lo mismo, una opción de mostrar pero por defecto no.»</i> Las dos, del comando {@code guion}; y {@code rw}, también
 * de las líneas que lo tienen.</p>
 */
public final class Guiones {

    private static final String TITULO = "Guion";
    /** Lo que se enseña de un guion antes de empezar. */
    private static final int MAX_MOSTRAR = 30;

    private final Comandos comandos;
    private final Consumer<String> consola;
    private final Component ventana;
    private Carrera enMarcha;
    private File ultimaCarpeta;

    /** `consola`: la del IDE, desde cualquier hilo; `ventana`, la de su diálogo de elegir fichero. */
    public Guiones(Comandos comandos, Consumer<String> consola, Component ventana) {
        this.comandos = comandos;
        this.consola = consola != null ? consola : s -> { };
        this.ventana = ventana;
        comandos.registra("guion", "Ejecutar un guion…", this::ejecuta)
                .explica("Ejecuta un guion: un fichero de texto con un comando del IDE por línea —estas mismas herramientas—,"
                        + " con sus parámetros: «entorno_poner clave=packRam valor=512». Un valor con espacios, entre"
                        + " comillas; un parámetro de sí o no, a secas, es true; una línea que empieza por «//», un"
                        + " comentario (la primera, su descripción). Se mira entero antes de empezar (un error, con su"
                        + " línea), se hace línea a línea y para en la primera que falla. Uno de la instalación («nombre»:"
                        + " los de «guiones_listar») o un .guion del PC («fichero»); las rutas que lleve, desde su carpeta."
                        + " Devuelve «pasos», los hechos, y si paró, en qué «linea». Lo que no se deshace se le pregunta a"
                        + " la persona que usa el IDE, como siempre.")
                .parametro("nombre", "uno de la instalación, por su nombre")
                .parametro("fichero", "ruta", "o la ruta de un .guion")
                .parametro("rw", "sino", "true: sobrescribe, sin preguntar, lo que ya esté con otro valor (el «rw» de sus"
                        + " líneas, puesto en todas)")
                .parametro("mostrar", "sino", "true: antes de empezar se le enseña a la persona que usa el IDE, y ella dice"
                        + " si se ejecuta")
                .pide(this::pideElGuion);
        comandos.registra("guiones_listar", "Los guiones de la instalación", p -> listo(lista()))
                .explica("Los guiones de la instalación (instalar la interfaz gráfica o la base de datos en una placa…): el"
                        + " «nombre» de cada uno, para «guion», y su «descripcion». Y la «carpeta» en la que están.");
        comandos.registra("guion_parar", "Parar el guion", p -> listo(para()))
                .explica("Para el guion en marcha: acaba el paso que está haciendo y no sigue.");
    }

    /** ¿Hay uno en marcha? (En el EDT.) */
    boolean enMarcha() { return enMarcha != null; }

    /* ===== los de la instalación ===== */

    /** Un guion de la instalación por su nombre, o null. */
    private static Path deLaInstalacion(String nombre) {
        for (Path f : losDeLaInstalacion()) {
            if (Guion.nombreDe(f).equalsIgnoreCase(nombre) || f.getFileName().toString().equalsIgnoreCase(nombre)) return f;
        }
        return null;
    }

    private static List<Path> losDeLaInstalacion() {
        List<Path> out = new ArrayList<>();
        Path dir = IdePrefs.guionesDir();
        if (dir == null) return out;
        File[] fs = dir.toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".guion"));
        if (fs != null) for (File f : fs) out.add(f.toPath());
        java.util.Collections.sort(out);
        return out;
    }

    private static Resultado lista() {
        List<Map<String, Object>> gs = new ArrayList<>();
        StringBuilder s = new StringBuilder();
        for (Path f : losDeLaInstalacion()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", Guion.nombreDe(f));
            String d;
            try { d = Guion.descripcionDe(f); } catch (IOException e) { d = "(no se puede leer: " + e.getMessage() + ")"; }
            m.put("descripcion", d);
            gs.add(m);
            s.append("\n  ").append(m.get("nombre")).append(" — ").append(d);
        }
        Path dir = IdePrefs.guionesDir();
        return Resultado.bien(gs.isEmpty() ? "la instalación no trae ningún guion" : gs.size() + " guiones:" + s)
                .con("carpeta", dir == null ? null : dir.toAbsolutePath().normalize().toString()).con("guiones", gs);
    }

    /* ===== ejecutar ===== */

    /** «guion»: se lee, se mira entero y, si vale, se ejecuta. */
    private CompletableFuture<Resultado> ejecuta(Map<String, Object> p) {
        String nombre = texto(p.get("nombre"));
        String ruta = texto(p.get("fichero"));
        if ((nombre == null) == (ruta == null)) {
            return aviso("guion: di uno de los dos, «nombre» (uno de la instalación) o «fichero»");
        }
        if (enMarcha != null) {
            return aviso("ya hay un guion en marcha (" + enMarcha.g.nombre + "): espera a que acabe, o páralo con"
                    + " «guion_parar»");
        }
        Path f = ruta != null ? Paths.get(ruta) : deLaInstalacion(nombre);
        if (f == null) {
            List<String> los = new ArrayList<>();
            for (Path g : losDeLaInstalacion()) los.add(Guion.nombreDe(g));
            return aviso("no hay ningún guion «" + nombre + "» en la instalación; los que hay: "
                    + (los.isEmpty() ? "ninguno" : String.join(", ", los)));
        }
        if (!Files.isRegularFile(f)) return aviso("no existe el fichero " + f);
        Object q = p.get(Comandos.QUIEN);
        final Comandos.Quien quien = q instanceof Comandos.Quien ? (Comandos.Quien) q : null;
        final Guion g;
        try {
            g = Guion.lee(f, comandos, quien != null ? quien.ruta : null);
        } catch (Guion.Malo m) {
            String que = "el guion " + Guion.nombreDe(f) + " no vale, y no se ha hecho nada:";
            for (String e : m.errores) que += "\n  " + e;
            consola.accept("[guion] " + que);
            return listo(Resultado.aviso(TITULO, que, JOptionPane.WARNING_MESSAGE).con("errores", m.errores));
        } catch (IOException e) {
            return aviso("no se puede leer " + f + ": " + e.getMessage());
        }
        if (Boolean.TRUE.equals(p.get("mostrar")) && !comandos.pregunta(quien, comoSeEnsena(g))) {
            return listo(Resultado.mal("el guion " + g.nombre + " no se ha ejecutado: la persona que usa el IDE no lo ha"
                    + " autorizado"));
        }
        enMarcha = new Carrera(g, quien, Boolean.TRUE.equals(p.get("rw")));
        consola.accept("[guion] " + g.nombre + (g.descripcion.isEmpty() ? "" : " — " + g.descripcion) + " ("
                + cuantos(g.pasos.size()) + ")");
        Carrera c = enMarcha;
        c.siguiente();
        return c.fin;
    }

    private static String cuantos(int n) { return n == 1 ? "1 paso" : n + " pasos"; }

    private static String comoSeEnsena(Guion g) {
        StringBuilder s = new StringBuilder("EJECUTAR el guion ").append(g.nombre);
        if (!g.descripcion.isEmpty()) s.append(" (").append(g.descripcion).append(')');
        s.append(":\n");
        for (int i = 0; i < g.pasos.size() && i < MAX_MOSTRAR; i++) {
            Guion.Paso paso = g.pasos.get(i);
            s.append("\n   ").append(Guion.escribe(paso.comando.id, paso.parametros));
        }
        if (g.pasos.size() > MAX_MOSTRAR) s.append("\n   … (").append(g.pasos.size() - MAX_MOSTRAR).append(" más)");
        return s.toString();
    }

    /** Un guion en marcha. Todo en el EDT. */
    private final class Carrera {
        final Guion g;
        final Comandos.Quien quien;
        final boolean rw;
        final long t0 = System.currentTimeMillis();
        final CompletableFuture<Resultado> fin = new CompletableFuture<>();
        final List<Map<String, Object>> hechos = new ArrayList<>();
        int i;
        boolean parar;

        Carrera(Guion g, Comandos.Quien quien, boolean rw) {
            this.g = g;
            this.quien = quien != null ? quien : new Comandos.Quien("la ventana", null);
            this.rw = rw;
        }

        void siguiente() {
            if (parar) {
                acaba(Resultado.mal("el guion " + g.nombre + ", parado: hechos " + i + " de " + g.pasos.size()));
                return;
            }
            if (i == g.pasos.size()) {
                acaba(Resultado.bien("el guion " + g.nombre + ", hecho: " + cuantos(i) + " en "
                        + (System.currentTimeMillis() - t0 + 500) / 1000 + " s"));
                return;
            }
            final Guion.Paso paso = g.pasos.get(i);
            Map<String, Object> p = new LinkedHashMap<>(paso.parametros);
            if (rw && paso.comando.parametros().containsKey("rw")) p.putIfAbsent("rw", Boolean.TRUE);
            consola.accept("[guion] " + (i + 1) + "/" + g.pasos.size() + " " + Guion.escribe(paso.comando.id, p));
            comandos.ejecuta(paso.comando.id, p, quien.llamado("el guion " + g.nombre + " (línea " + paso.linea + ")"))
                    .thenAccept(r -> SwingUtilities.invokeLater(() -> hecho(paso, r)));
        }

        private void hecho(Guion.Paso paso, Resultado r) {
            String mensaje = r.mensaje == null ? "" : r.mensaje;
            if (mensaje.startsWith(paso.comando.id + ": ")) mensaje = mensaje.substring(paso.comando.id.length() + 2);
            consola.accept("[guion]     " + (r.ok ? "bien" : "MAL") + ": " + mensaje.split("\n", 2)[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("linea", paso.linea);
            m.put("comando", paso.comando.id);
            m.put("ok", r.ok);
            m.put("mensaje", mensaje);
            hechos.add(m);
            /* V9 #870 — el programa pide datos: los da la línea siguiente («responder»); si no es eso, el guion para (el
             * programa se queda esperando). */
            boolean sinResponder = r.ok && r.datos().containsKey("pide")
                    && (i + 1 == g.pasos.size() || !g.pasos.get(i + 1).comando.id.equals("responder"));
            if (!r.ok || sinResponder) {
                acaba(Resultado.aviso(TITULO, "el guion " + g.nombre + ", parado en la línea " + paso.linea + " ("
                        + paso.comando.id + "): " + mensaje + (sinResponder ? "; el guion no responde, y el programa sigue"
                        + " esperando («parar» lo para)" : "") + "\nHechos " + i + " de " + cuantos(g.pasos.size()) + ".",
                        JOptionPane.WARNING_MESSAGE).con("linea", paso.linea));
                return;
            }
            i++;
            siguiente();
        }

        private void acaba(Resultado r) {
            enMarcha = null;
            consola.accept("[guion] " + r.mensaje.split("\n", 2)[0]);
            fin.complete(r.con("guion", g.nombre).con("pasos", hechos));
        }
    }

    private Resultado para() {
        if (enMarcha == null) {
            return Resultado.aviso(TITULO, "no hay ningún guion en marcha", JOptionPane.INFORMATION_MESSAGE);
        }
        enMarcha.parar = true;
        return Resultado.bien("el guion " + enMarcha.g.nombre + " para cuando acabe el paso que está haciendo");
    }

    /* ===== la ventana ===== */

    /** El fichero, en su diálogo (de entrada, la carpeta de los de la instalación), con las dos opciones, sin marcar. */
    private Map<String, Object> pideElGuion() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Ejecutar un guion");
        fc.setFileFilter(new FileNameExtensionFilter("Guiones (*.guion)", "guion"));
        Path inst = IdePrefs.guionesDir();
        File dir = ultimaCarpeta != null ? ultimaCarpeta : inst != null ? inst.toFile() : null;
        if (dir != null && dir.isDirectory()) fc.setCurrentDirectory(dir);
        JCheckBox mostrar = new JCheckBox("Mostrarlo antes de empezar");
        JCheckBox rw = new JCheckBox("<html>Sobrescribir sin preguntar<br>lo que ya esté (rw)</html>");
        JPanel opciones = new JPanel(new GridLayout(0, 1, 0, 8));
        opciones.add(mostrar);
        opciones.add(rw);
        JPanel lado = new JPanel(new BorderLayout());
        lado.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 4));
        lado.add(opciones, BorderLayout.NORTH);
        fc.setAccessory(lado);
        if (fc.showDialog(ventana, "Ejecutar") != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) return null;
        ultimaCarpeta = fc.getSelectedFile().getParentFile();
        Map<String, Object> p = new HashMap<>();
        p.put("fichero", fc.getSelectedFile().getAbsolutePath());
        p.put("mostrar", mostrar.isSelected());
        p.put("rw", rw.isSelected());
        return p;
    }

    private static String texto(Object v) {
        String s = v == null ? "" : v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static CompletableFuture<Resultado> listo(Resultado r) { return CompletableFuture.completedFuture(r); }

    private static CompletableFuture<Resultado> aviso(String que) {
        return listo(Resultado.aviso(TITULO, que, JOptionPane.WARNING_MESSAGE));
    }
}
