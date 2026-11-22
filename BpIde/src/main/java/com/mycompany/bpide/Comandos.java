package com.mycompany.bpide;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * V9 H2.2 (22-nov) — LOS COMANDOS DEL IDE. Idea de Eduardo: <i>«Quizás sería mejor trabajar con comandos. Java tiene
 * una clase para ello. Sería transformar los eventos de los botones a comandos y luego es trivial llamar a un comando
 * desde un botón. La ventaja es que los comandos se pueden llamar desde casi cualquier sitio.»</i>
 *
 * <p>Cada acción del IDE es un {@link Comando}: su nombre (con el que lo llama quien no es la ventana: la IA, H2.3), su
 * texto (de un solo sitio; los dos idiomas, después), los parámetros que admite y lo que hace, que devuelve un
 * {@link Resultado} cuando acaba. Tiene dos caras:</p>
 * <ul>
 *   <li><b>la ventana</b>: su {@link Action} (la clase de Swing para esto), la MISMA en el menú y en el botón. Pide con
 *       sus diálogos lo que falta (el fichero que abrir) y, si el resultado lo pide, enseña su diálogo, como antes;</li>
 *   <li><b>quien lo llame por su nombre</b> ({@link #ejecuta}): pasa los parámetros y recibe el resultado en datos, sin
 *       ningún diálogo de por medio.</li>
 * </ul>
 * <p>La lógica, una vez, en el comando. Corre en el hilo de la ventana (el EDT), como antes: {@link #ejecuta} la lleva
 * allí si la llaman desde otro hilo; lo largo (compilar, ejecutar) sigue en su SwingWorker y completa el resultado al
 * acabar.</p>
 */
public final class Comandos {

    /** Una acción del IDE. Se crea con {@link Comandos#registra} y se completa encadenando lo que tenga. */
    public static final class Comando {
        /** El nombre con el que se llama: «compilar», «ejecutar», «abrir»… (sin espacios ni acentos). */
        public final String id;
        private final String texto;
        private String textoCorto;
        private String ayuda;
        private KeyStroke atajo;
        private final Map<String, String> parametros = new LinkedHashMap<>();
        /** Lo que hace, en el EDT; el resultado, cuando acabe. */
        private final Function<Map<String, Object>, CompletableFuture<Resultado>> logica;
        /** Desde la ventana, lo que falta, por sus diálogos; null = el usuario lo canceló (no se hace nada). */
        private Supplier<Map<String, Object>> pideALaVentana = HashMap::new;
        /** El comando ES su diálogo (una configuración, la gestión de la placa…): sólo desde la ventana. */
        private boolean soloVentana;

        private Comando(String id, String texto, Function<Map<String, Object>, CompletableFuture<Resultado>> logica) {
            this.id = id;
            this.texto = texto;
            this.textoCorto = texto;
            this.logica = logica;
        }

        /** El texto de la barra, si es distinto del del menú. */
        public Comando corto(String t) { this.textoCorto = t; return this; }
        /** La ayuda del botón (tooltip). */
        public Comando ayuda(String t) { this.ayuda = t; return this; }
        /** El atajo de teclado (el del menú). */
        public Comando atajo(KeyStroke k) { this.atajo = k; return this; }
        /** Un parámetro que admite, con lo que es (lo lee la IA). */
        public Comando parametro(String nombre, String queEs) { parametros.put(nombre, queEs); return this; }
        /** Cómo pide la ventana lo que falta: devuelve los parámetros, o null si el usuario cancela. */
        public Comando pide(Supplier<Map<String, Object>> s) { this.pideALaVentana = s; return this; }
        /** El comando es su propio diálogo: llamado por su nombre, un resultado que lo dice (no abre nada). */
        public Comando soloVentana() { this.soloVentana = true; return this; }

        public String texto() { return texto; }
        public String textoCorto() { return textoCorto; }
        public String ayuda() { return ayuda; }
        public boolean esSoloVentana() { return soloVentana; }
        public Map<String, String> parametros() { return Collections.unmodifiableMap(parametros); }
    }

    private final java.awt.Component ventana;
    private final Map<String, Comando> porId = new LinkedHashMap<>();
    private final Map<String, Accion> acciones = new HashMap<>();

    /** `ventana`: la de los diálogos que piden y que avisan. */
    public Comandos(java.awt.Component ventana) {
        this.ventana = ventana;
    }

    /** Registra un comando: su nombre, el texto del menú y lo que hace (en el EDT). Un nombre, una vez. */
    public Comando registra(String id, String texto,
                            Function<Map<String, Object>, CompletableFuture<Resultado>> logica) {
        if (porId.containsKey(id)) throw new IllegalArgumentException("comando repetido: " + id);
        Comando c = new Comando(id, texto, logica);
        porId.put(id, c);
        return c;
    }

    /** El comando de ese nombre, o null. */
    public Comando get(String id) {
        return porId.get(id);
    }

    /** Todos, en el orden en que se registraron. */
    public Collection<Comando> todos() {
        return Collections.unmodifiableCollection(porId.values());
    }

    /** Su cara en la ventana: la Action del menú y del botón (la misma para los dos), creada la primera vez. */
    public Action accion(String id) {
        Comando c = porId.get(id);
        if (c == null) throw new IllegalArgumentException("no hay ningún comando '" + id + "'");
        return acciones.computeIfAbsent(id, k -> new Accion(c));
    }

    /**
     * Ejecuta un comando por su nombre, desde cualquier hilo, con sus parámetros: la lógica, en el EDT; el resultado,
     * cuando acabe. Nunca abre un diálogo: lo que la ventana avisaría, va en el resultado. Un nombre que no existe, o
     * una lógica que revienta, también es un resultado (mal), no una excepción.
     */
    public CompletableFuture<Resultado> ejecuta(String id, Map<String, Object> parametros) {
        Comando c = porId.get(id);
        if (c == null) {
            return CompletableFuture.completedFuture(Resultado.mal("no hay ningún comando '" + id + "'"));
        }
        if (c.soloVentana) {
            return CompletableFuture.completedFuture(Resultado.mal("'" + id + "' abre un diálogo de la ventana: sólo se"
                    + " usa desde la ventana"));
        }
        return corre(c, parametros);
    }

    /** Corre la lógica de un comando en el EDT; el resultado, cuando acabe (lo de {@link #ejecuta}, sin mirar si es
     *  sólo de la ventana: desde la ventana, lo es siempre). */
    private CompletableFuture<Resultado> corre(Comando c, Map<String, Object> parametros) {
        final String id = c.id;
        final Map<String, Object> p = parametros != null ? parametros : Collections.<String, Object>emptyMap();
        CompletableFuture<Resultado> f = new CompletableFuture<>();
        Runnable corre = () -> {
            try {
                CompletableFuture<Resultado> r = c.logica.apply(p);
                if (r == null) {
                    f.complete(Resultado.mal(id + ": no devolvió resultado"));
                    return;
                }
                r.whenComplete((res, ex) -> {
                    if (ex != null) f.complete(Resultado.mal(id + ": " + causa(ex)));
                    else f.complete(res != null ? res : Resultado.mal(id + ": no devolvió resultado"));
                });
            } catch (Throwable t) {
                f.complete(Resultado.mal(id + ": " + causa(t)));
            }
        };
        if (SwingUtilities.isEventDispatchThread()) corre.run();
        else SwingUtilities.invokeLater(corre);
        return f;
    }

    /**
     * Desde la ventana (un botón, el menú, o el código del IDE que antes llamaba al método de la acción): pide lo que
     * falta por sus diálogos, ejecuta y, si el resultado lo pide, enseña su diálogo. Devuelve el resultado, o null si
     * el usuario canceló. En el EDT.
     */
    public CompletableFuture<Resultado> desdeLaVentana(String id) {
        Comando c = porId.get(id);
        if (c == null) throw new IllegalArgumentException("no hay ningún comando '" + id + "'");
        Map<String, Object> p = c.pideALaVentana.get();
        if (p == null) return null;   // cancelado en su diálogo: como antes, no se hace nada
        return desdeLaVentana(id, p);
    }

    /**
     * Desde la ventana, con los parámetros ya dichos (un reciente del menú: el fichero que abrir), sin pedir nada:
     * ejecuta y, si el resultado lo pide, enseña su diálogo. En el EDT.
     */
    public CompletableFuture<Resultado> desdeLaVentana(String id, Map<String, Object> parametros) {
        Comando c = porId.get(id);
        if (c == null) throw new IllegalArgumentException("no hay ningún comando '" + id + "'");
        CompletableFuture<Resultado> f = corre(c, parametros);
        f.thenAccept(this::muestra);
        return f;
    }

    /** El diálogo que pide un resultado (un aviso), en la ventana; nada si no lo pide. Lo usa también el código de la
     *  ventana que llama a la lógica sin pasar por un comando (el doble clic del árbol). */
    public void muestra(Resultado r) {
        if (r == null || r.tituloDialogo == null) return;
        Runnable aviso = () -> JOptionPane.showMessageDialog(ventana, r.mensaje, r.tituloDialogo, r.tipoDialogo);
        if (SwingUtilities.isEventDispatchThread()) aviso.run();
        else SwingUtilities.invokeLater(aviso);
    }

    /** Los nombres, con su texto y sus parámetros (para quien los liste: la IA). */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Comando c : porId.values()) {
            out.add(c.id + " — " + c.texto + (c.parametros.isEmpty() ? "" : " " + c.parametros)
                    + (c.soloVentana ? " (sólo desde la ventana)" : ""));
        }
        return out;
    }

    private static String causa(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return c.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    /** La cara en la ventana: la Action de Swing (la del menú y la del botón). */
    private final class Accion extends AbstractAction {
        private final Comando c;

        Accion(Comando c) {
            super(c.texto);
            this.c = c;
            if (c.ayuda != null) putValue(SHORT_DESCRIPTION, c.ayuda);
            if (c.atajo != null) putValue(ACCELERATOR_KEY, c.atajo);
            putValue(ACTION_COMMAND_KEY, c.id);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            desdeLaVentana(c.id);
        }
    }
}
