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
        /** V9 H2.3 — el tipo de cada parámetro (para el esquema de la IA): texto, numero, sino, lista, ruta (una del PC,
         *  dentro de la carpeta de la IA) o rutas. */
        private final Map<String, String> tipos = new LinkedHashMap<>();
        /** V9 H2.3 — los que la IA tiene que pasar siempre (la ventana los saca de lo seleccionado). */
        private final List<String> necesarios = new ArrayList<>();
        /** V9 H2.3 — qué hace y qué devuelve, dicho para la IA (la descripción de su herramienta). */
        private String explicacion;
        /** Lo que hace, en el EDT; el resultado, cuando acabe. */
        private final Function<Map<String, Object>, CompletableFuture<Resultado>> logica;
        /** Desde la ventana, lo que falta, por sus diálogos; null = el usuario lo canceló (no se hace nada). */
        private Supplier<Map<String, Object>> pideALaVentana = HashMap::new;
        /** El comando ES su diálogo (una configuración, la gestión de la placa…): sólo desde la ventana. */
        private boolean soloVentana;
        /** V9 #875 — lo que se le pregunta a la persona antes de hacerlo por su nombre (la IA); null: nada. */
        private Function<Map<String, Object>, CompletableFuture<String>> pregunta;

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
        /** Un parámetro que admite, con lo que es (lo lee la IA). Un texto. */
        public Comando parametro(String nombre, String queEs) { return parametro(nombre, "texto", queEs); }

        /** V9 H2.3 — un parámetro con su tipo: texto, numero, sino, lista, ruta (del PC: dentro de la carpeta de la IA)
         *  o rutas. */
        public Comando parametro(String nombre, String tipo, String queEs) {
            if (!TIPOS.contains(tipo)) throw new IllegalArgumentException("tipo de parámetro desconocido: " + tipo);
            parametros.put(nombre, queEs);
            tipos.put(nombre, tipo);
            return this;
        }

        /** V9 H2.3 — los parámetros que la IA tiene que pasar siempre. */
        public Comando necesita(String... nombres) {
            for (String n : nombres) {
                if (!parametros.containsKey(n)) throw new IllegalArgumentException("'" + n + "' no es un parámetro de " + id);
                necesarios.add(n);
            }
            return this;
        }

        /** V9 H2.3 — qué hace y qué devuelve, para la IA. */
        public Comando explica(String t) { this.explicacion = t; return this; }
        /** Cómo pide la ventana lo que falta: devuelve los parámetros, o null si el usuario cancela. */
        public Comando pide(Supplier<Map<String, Object>> s) { this.pideALaVentana = s; return this; }
        /** El comando es su propio diálogo: llamado por su nombre, un resultado que lo dice (no abre nada). */
        public Comando soloVentana() { this.soloVentana = true; return this; }
        /** V9 #875 (Eduardo, 25-nov: «1) si») — lo que no se deshace: llamado por su nombre (la IA, un guion), antes se le
         *  pregunta a la persona que usa el IDE, con lo que va a pasar (`texto`, de sus parámetros, en infinitivo:
         *  «FORMATEAR la zona…»; null: no hay qué preguntar, y sigue) y «No» por defecto. Desde la ventana no: su botón ya
         *  pregunta lo suyo. */
        public Comando pregunta(Function<Map<String, Object>, CompletableFuture<String>> texto) {
            this.pregunta = texto;
            return this;
        }

        public String texto() { return texto; }
        public String textoCorto() { return textoCorto; }
        public String ayuda() { return ayuda; }
        public boolean esSoloVentana() { return soloVentana; }
        public Map<String, String> parametros() { return Collections.unmodifiableMap(parametros); }
        /** V9 H2.3 — el tipo de un parámetro (texto si no se dijo). */
        public String tipoDe(String nombre) { String t = tipos.get(nombre); return t != null ? t : "texto"; }
        public List<String> necesarios() { return Collections.unmodifiableList(necesarios); }
        /** V9 H2.3 — para la IA: su explicación; sin ella, la ayuda del botón o el texto del menú. */
        public String explicacion() {
            if (explicacion != null) return explicacion;
            return ayuda != null ? ayuda : texto;
        }
    }

    /** V9 H2.3 — los tipos de los parámetros. */
    static final java.util.Set<String> TIPOS = new java.util.HashSet<>(java.util.Arrays.asList(
            "texto", "numero", "sino", "lista", "ruta", "rutas"));

    /** V9 #891 — quién llama a un comando por su nombre: la IA, un guion o la consola. Lo dice la pregunta de lo que no se
     *  deshace («La IA pide…»); y `ruta` es cómo toma una del PC: devuelve la absoluta, o lanza con el porqué si no la puede
     *  tocar (la IA, fuera de su carpeta). */
    public static final class Quien {
        public final String nombre;
        final Function<String, String> ruta;
        /** La persona, que está delante (la consola): los datos que pida un programa, en su formulario (#870). */
        final boolean persona;

        public Quien(String nombre, Function<String, String> ruta) { this(nombre, ruta, false); }

        Quien(String nombre, Function<String, String> ruta, boolean persona) {
            this.nombre = nombre;
            this.ruta = ruta;
            this.persona = persona;
        }

        /** El mismo, con otro nombre («el guion X, línea 3»); ya no es la persona: lo que haga, sin ella. */
        Quien llamado(String otro) { return new Quien(otro, ruta); }
    }

    /** V9 #891 — el parámetro, sin documentar, con el que un comando sabe quién lo llama (la IA no puede pasarlo). */
    static final String QUIEN = "quien";

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
     * cuando acabe. Nunca abre un diálogo (lo que la ventana avisaría, va en el resultado), salvo el de un comando que
     * no se deshace: ése se lo pregunta antes a la persona ({@link Comando#pregunta}). Un nombre que no existe, o una
     * lógica que revienta, también es un resultado (mal), no una excepción.
     */
    public CompletableFuture<Resultado> ejecuta(String id, Map<String, Object> parametros) {
        return ejecuta(id, parametros, null);
    }

    /** Lo mismo, diciendo quién lo llama (V9 #891). */
    public CompletableFuture<Resultado> ejecuta(String id, Map<String, Object> parametros, Quien quien) {
        Comando c = porId.get(id);
        if (c == null) {
            return CompletableFuture.completedFuture(Resultado.mal("no hay ningún comando '" + id + "'"));
        }
        if (c.soloVentana) {
            return CompletableFuture.completedFuture(Resultado.mal("'" + id + "' abre un diálogo de la ventana: sólo se"
                    + " usa desde la ventana"));
        }
        Map<String, Object> p = new LinkedHashMap<>();
        if (parametros != null) p.putAll(parametros);
        if (quien != null) p.put(QUIEN, quien);
        return c.pregunta != null ? trasPreguntar(c, p, quien) : corre(c, p);
    }

    /** V9 #891 (era de ServidorIa) — los argumentos de una llamada por su nombre, mirados y puestos en su tipo (en `args`):
     *  sólo los documentados, los que hacen falta y las rutas que se pueden tocar (`ruta`). Por qué no valen, o null. */
    static String revisa(Comando c, Map<String, Object> args, Function<String, String> ruta) {
        List<String> sobran = new ArrayList<>();
        for (String k : args.keySet()) if (!c.parametros.containsKey(k)) sobran.add(k);
        if (!sobran.isEmpty()) {
            return c.id + " no tiene " + (sobran.size() == 1 ? "el parámetro " : "los parámetros ") + sobran
                    + "; los suyos: " + c.parametros.keySet();
        }
        for (String n : c.necesarios) {
            if (args.get(n) == null) return c.id + " necesita «" + n + "»: " + c.parametros.get(n);
        }
        for (Map.Entry<String, Object> a : args.entrySet()) {
            Object v = a.getValue();
            if (v == null) continue;
            switch (c.tipoDe(a.getKey())) {
                case "numero":
                    if (v instanceof Number) break;
                    try {
                        a.setValue(Long.valueOf(v.toString().trim()));
                    } catch (NumberFormatException e) {
                        return c.id + ": «" + a.getKey() + "» es un número, no «" + v + "»";
                    }
                    break;
                case "sino":
                    if (v instanceof Boolean) break;
                    if (!v.toString().equals("true") && !v.toString().equals("false")) {
                        return c.id + ": «" + a.getKey() + "» es true o false, no «" + v + "»";
                    }
                    a.setValue(Boolean.valueOf(v.toString()));
                    break;
                case "lista":
                    if (!(v instanceof Collection)) a.setValue(new ArrayList<>(Collections.singletonList(v)));
                    break;
                case "ruta":
                case "rutas": {
                    List<Object> rs = new ArrayList<>();
                    for (Object x : v instanceof Collection ? (Collection<?>) v : Collections.singletonList(v)) {
                        try {
                            rs.add(ruta.apply(String.valueOf(x)));
                        } catch (IllegalArgumentException e) {   // y InvalidPathException, que lo es
                            return c.id + ": «" + x + "» " + e.getMessage();
                        }
                    }
                    if (c.tipoDe(a.getKey()).equals("rutas")) a.setValue(rs);
                    else if (rs.size() == 1) a.setValue(rs.get(0));
                    else return c.id + ": «" + a.getKey() + "» es una sola ruta";
                    break;
                }
                default:
                    if (v instanceof Collection) return c.id + ": «" + a.getKey() + "» va una sola vez";
            }
        }
        return null;
    }

    /** V9 #875 — quien pregunta a la persona: sí o no. El de verdad, un diálogo sobre la ventana; una prueba pone el suyo. */
    private java.util.function.Predicate<String> preguntador = this::preguntaALaPersona;

    void setPreguntador(java.util.function.Predicate<String> p) { preguntador = p; }

    /** V9 #891 — pregunta a la persona si se sigue, con lo que va a pasar (`texto`) y quién lo pide. En el EDT. */
    boolean pregunta(Quien quien, String texto) {
        String n = quien != null ? quien.nombre : null;
        return preguntador.test((n == null ? "" : Character.toUpperCase(n.charAt(0)) + n.substring(1) + " pide ") + texto);
    }

    /** El diálogo: lo que va a pasar, y «No» por defecto (un Intro sin mirar no autoriza nada). En el EDT. */
    private boolean preguntaALaPersona(String texto) {
        Object[] opciones = { "Sí, adelante", "No" };
        if (ventana instanceof java.awt.Window) ((java.awt.Window) ventana).toFront();
        return JOptionPane.showOptionDialog(ventana, texto + "\n\n¿Sigo?", "Antes de seguir",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, opciones, opciones[1]) == 0;
    }

    /** Un comando que se pregunta: el texto de la pregunta (puede tardar: lee la placa), la respuesta de la persona y, con
     *  un sí, el comando. */
    private CompletableFuture<Resultado> trasPreguntar(Comando c, Map<String, Object> p, Quien quien) {
        final CompletableFuture<Resultado> fin = new CompletableFuture<>();
        Runnable empieza = () -> {
            CompletableFuture<String> t;
            try {
                t = c.pregunta.apply(p);
            } catch (Throwable e) {
                fin.complete(Resultado.mal(c.id + ": " + causa(e)));
                return;
            }
            t.whenComplete((texto, ex) -> SwingUtilities.invokeLater(() -> {
                if (ex != null) fin.complete(Resultado.mal(c.id + ": " + causa(ex)));
                else if (texto != null && !pregunta(quien, texto)) {
                    fin.complete(Resultado.mal(c.id + ": la persona que usa el IDE no lo ha autorizado"));
                } else {
                    corre(c, p).thenAccept(fin::complete);
                }
            }));
        };
        if (SwingUtilities.isEventDispatchThread()) empieza.run();
        else SwingUtilities.invokeLater(empieza);
        return fin;
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
        Runnable aviso = () -> JOptionPane.showMessageDialog(r.padre(ventana), r.loQueEnsena(), r.tituloDialogo,
                r.tipoDialogo);
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
