package com.mycompany.bpide;

import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;

/**
 * V9 #876 (25-nov) — LAS VARIABLES DE ENTORNO DE LA PLACA, como comandos. Eduardo: <i>«crear las acciones en el IDE para
 * gestionar el enviroment. Despues ajustamos los botones para que utilicen las acciones y por último habilitamos que la IA
 * pueda utilizar las acciones. Es lo mismo que ya hemos hecho pero aplicado a las variables de entorno.»</i>
 *
 * <p>Dos partes. La lógica, sin ventana ({@link #pon}, {@link #borra}, sobre el cliente del cable): la que vivía en los
 * botones de {@link EnvDialog}. Y sus tres comandos ({@code entorno_listar}, {@code entorno_poner} y
 * {@code entorno_borrar}), los mismos para esos botones y para la IA. Quien tiene la lista a la vista (el diálogo) se
 * apunta con {@link #mira}: la ve cambiar la cambie quien la cambie.</p>
 *
 * <p>Un cambio son tres mensajes: la lista de antes (para decir lo que había, y no tocar la flash si ya valía eso), el
 * cambio y la lista de después, que es también la comprobación: lo que se dice es lo que tiene la placa.</p>
 */
public final class EntornoPlaca {

    private static final long T = 8000;    // timeout wire
    /** Lo que le cabe a la placa en un mensaje (bpvm_bmgr_req_t: 64 y 192 bytes, con su cero final). Uno más largo lo
     *  rechaza con «falta key» o «falta value»: aquí se dice lo que pasa. */
    private static final int MAX_CLAVE = 63;
    private static final int MAX_VALOR = 191;

    private static final String TITULO = "Variables de entorno";
    /** Lo que hay que saber tras un cambio: el ENV se lee al arrancar (la referencia, apéndice A, dice cuál y cuándo). */
    private static final String CUANDO = ". Casi todas se leen al arrancar: para que valga, reinicia la placa";

    private final Comandos comandos;
    private final Supplier<BpvmClient> cliente;
    private final Consumer<String> consola;
    private final List<Consumer<List<BpvmClient.EnvVar>>> mirando = new CopyOnWriteArrayList<>();

    /** `cliente`: el de la conexión de ahora (null sin ella); se pide en cada comando, que entre uno y otro puede haber
     *  cambiado la placa. `consola`: la del IDE, desde cualquier hilo. */
    public EntornoPlaca(Comandos comandos, Supplier<BpvmClient> cliente, Consumer<String> consola) {
        this.comandos = comandos;
        this.cliente = cliente;
        this.consola = consola != null ? consola : s -> { };
        registra();
    }

    /* ===== la lógica, sin ventana ===== */

    /** Un cambio hecho: la clave (como la tiene la placa), lo que valía antes (null: no estaba), lo que vale ahora (null:
     *  borrada) y todas las que quedan. */
    public static final class Cambio {
        public final String clave, antes, ahora;
        public final List<BpvmClient.EnvVar> quedan;
        Cambio(String clave, String antes, String ahora, List<BpvmClient.EnvVar> quedan) {
            this.clave = clave;
            this.antes = antes;
            this.ahora = ahora;
            this.quedan = quedan;
        }
    }

    /** ¿La gestiona la sección de particiones (part.&lt;x&gt;.size)? En la placa las claves no distinguen mayúsculas. */
    static boolean deParticiones(String clave) {
        String k = clave == null ? "" : clave.toLowerCase(Locale.ROOT);
        return k.startsWith("part.") && k.endsWith(".size");
    }

    /** Las que se ven y se cambian: todas menos las de las particiones. */
    static List<BpvmClient.EnvVar> editables(List<BpvmClient.EnvVar> todas) {
        List<BpvmClient.EnvVar> out = new ArrayList<>();
        for (BpvmClient.EnvVar e : todas) if (!deParticiones(e.key)) out.add(e);
        return out;
    }

    /** La de esa clave (sin distinguir mayúsculas, como la placa), o null. */
    private static BpvmClient.EnvVar busca(List<BpvmClient.EnvVar> vars, String clave) {
        for (BpvmClient.EnvVar e : vars) if (e.key.equalsIgnoreCase(clave)) return e;
        return null;
    }

    /** Por qué esa clave no se puede tocar desde aquí, o null si se puede. */
    static String noValeLaClave(String clave) {
        if (clave == null || clave.isEmpty()) return "falta la clave.";
        if (deParticiones(clave)) return "Los tamaños de partición se editan en la sección de particiones.";
        for (int i = 0; i < clave.length(); i++) {
            char c = clave.charAt(i);
            if (c == '=' || c <= ' ') return "una clave no lleva espacios ni «=»: «" + clave + "».";
        }
        int n = clave.getBytes(StandardCharsets.UTF_8).length;
        return n > MAX_CLAVE ? "la clave es demasiado larga (" + n + " bytes; a la placa le caben " + MAX_CLAVE + ")." : null;
    }

    /** Por qué ese valor no vale, o null si vale. */
    static String noValeElValor(String valor) {
        if (valor == null || valor.isEmpty()) return "falta el valor (para quitar la variable, se borra).";
        for (int i = 0; i < valor.length(); i++) {
            if (valor.charAt(i) < ' ') return "un valor es una línea: no lleva saltos ni tabuladores.";
        }
        int n = valor.getBytes(StandardCharsets.UTF_8).length;
        return n > MAX_VALOR ? "el valor es demasiado largo (" + n + " bytes; a la placa le caben " + MAX_VALOR + ")." : null;
    }

    /** Pone `clave`=`valor`. Si ya valía eso, no escribe (la flash de la placa no se gasta en balde). */
    public static Cambio pon(BpvmClient c, String clave, String valor) throws IOException {
        List<BpvmClient.EnvVar> hay = c.envList(T);
        BpvmClient.EnvVar estaba = busca(hay, clave);
        if (estaba != null && estaba.value.equals(valor)) return new Cambio(estaba.key, valor, valor, hay);
        c.envSet(clave, valor, T);
        List<BpvmClient.EnvVar> quedan = c.envList(T);
        BpvmClient.EnvVar esta = busca(quedan, clave);
        if (esta == null || !esta.value.equals(valor)) {
            throw new IOException("la placa no guardó " + clave + "=" + valor
                    + (esta == null ? "" : ": tiene «" + esta.value + "»"));
        }
        return new Cambio(esta.key, estaba == null ? null : estaba.value, esta.value, quedan);
    }

    /** Borra `clave`; null si no estaba (no se escribe nada). */
    public static Cambio borra(BpvmClient c, String clave) throws IOException {
        List<BpvmClient.EnvVar> hay = c.envList(T);
        BpvmClient.EnvVar estaba = busca(hay, clave);
        if (estaba == null) return null;
        c.envDel(estaba.key, T);
        List<BpvmClient.EnvVar> quedan = c.envList(T);
        if (busca(quedan, clave) != null) throw new IOException("la placa no borró " + estaba.key);
        return new Cambio(estaba.key, estaba.value, null, quedan);
    }

    /* ===== los comandos ===== */

    private void registra() {
        comandos.registra("entorno_listar", "Variables de entorno: listar", this::lista)
                .corto("Refrescar")
                .explica("Las variables de entorno de la placa conectada (o del micro simulado): los ajustes clave=valor"
                        + " que viven en su flash, fuera de sus ficheros, y que lee su firmware (la memoria de los packs,"
                        + " el panel, la tarjeta SD, la radio…). Devuelve «variables», cada una con su «clave» y su"
                        + " «valor»; los tamaños de las particiones van aparte, en «de_particiones»: no se cambian desde"
                        + " aquí. Qué claves hay y qué valores admite cada una, en la referencia, apéndice A.");
        comandos.registra("entorno_poner", "Variables de entorno: poner", this::pone)
                .corto("Añadir / editar…")
                .explica("Pone una variable de entorno en la placa conectada (o en el micro simulado): la crea, o le"
                        + " cambia el valor. Se escribe en su flash y sobrevive a regrabar la imagen; si ya valía eso, no"
                        + " se escribe. Devuelve lo que había («antes», si estaba: para poder dejarla como estaba) y todas"
                        + " las que quedan («variables»). Casi todas se leen al arrancar: valen tras «placa_reiniciar»,"
                        + " con «reconectar». Pon sólo claves de la referencia, apéndice A, con un valor de los que dice"
                        + " para esa placa: una que el firmware no conoce se guarda y nadie la lee, y lo que se da a"
                        + " packRam o a SQLite se le quita a la memoria de los programas.")
                .parametro("clave", "la variable (packRam, display, sd, log…); no distingue mayúsculas")
                .parametro("valor", "su valor, en una línea, como en la referencia (512, st7701, cs:23,bus:2…)")
                .necesita("clave", "valor");
        comandos.registra("entorno_borrar", "Variables de entorno: borrar", this::borra)
                .corto("Borrar")
                .explica("Borra una variable de entorno de la placa conectada (o del micro simulado): el firmware vuelve a"
                        + " lo que hace sin ella (casi siempre, tras «placa_reiniciar»). Devuelve lo que valía («antes») y"
                        + " las que quedan («variables»).")
                .parametro("clave", "la variable que borrar")
                .necesita("clave");
    }

    /** «entorno_listar»: las variables de la placa conectada. */
    private CompletableFuture<Resultado> lista(Map<String, Object> p) {
        return enSegundoPlano(p, c -> c.envList(T), todas -> {
            avisa(todas);
            int n = editables(todas).size();
            return conLasQueQuedan(Resultado.bien(n == 0 ? "ninguna variable de entorno"
                    : n == 1 ? "1 variable de entorno" : n + " variables de entorno"), todas);
        });
    }

    /** «entorno_poner»: crea o cambia una variable. Las reglas, antes de tocar la placa. */
    private CompletableFuture<Resultado> pone(Map<String, Object> p) {
        final String clave = texto(p.get("clave"));
        final String valor = texto(p.get("valor"));
        String no = noValeLaClave(clave);
        if (no == null) no = noValeElValor(valor);
        if (no != null) return noVale(p, "entorno_poner: " + no, no);
        return enSegundoPlano(p, c -> pon(c, clave, valor), ch -> {
            avisa(ch.quedan);
            if (ch.ahora.equals(ch.antes)) {
                return conElCambio(Resultado.bien(ch.clave + " ya valía " + ch.ahora + ": no se ha tocado nada"), ch);
            }
            String que = ch.clave + "=" + ch.ahora + (ch.antes == null ? " (no estaba)" : " (antes, " + ch.antes + ")");
            consola.accept("[Placa] entorno: " + que);
            return conElCambio(Resultado.bien(que + CUANDO), ch);
        });
    }

    /** «entorno_borrar»: quita una variable. */
    private CompletableFuture<Resultado> borra(Map<String, Object> p) {
        final String clave = texto(p.get("clave"));
        String no = noValeLaClave(clave);
        if (no != null) return noVale(p, "entorno_borrar: " + no, no);
        return enSegundoPlano(p, c -> borra(c, clave), ch -> {
            if (ch == null) {
                String m = "no hay ninguna variable «" + clave + "» en la placa.";
                return sobre(p, Resultado.aviso(TITULO, m, JOptionPane.INFORMATION_MESSAGE));
            }
            avisa(ch.quedan);
            String que = ch.clave + ", borrada (valía " + ch.antes + ")";
            consola.accept("[Placa] entorno: " + que);
            return conElCambio(Resultado.bien(que + CUANDO), ch);
        });
    }

    private static String texto(Object v) {
        return v == null ? null : v.toString().trim();   // sin espacios alrededor del «=» (la referencia, apéndice A)
    }

    /** No vale lo que se pide: no se toca la placa. La ventana lo enseña (`corto`); a quien lo llame por su nombre, con
     *  el del comando. */
    private CompletableFuture<Resultado> noVale(Map<String, Object> p, String conNombre, String corto) {
        boolean ventana = p.get("sobre") != null;
        return CompletableFuture.completedFuture(sobre(p,
                Resultado.aviso(TITULO, ventana ? corto : conNombre, JOptionPane.INFORMATION_MESSAGE)));
    }

    /** El diálogo del resultado, sobre la ventana que lo pidió (la mete ella en `sobre`; la IA no puede pasarla). */
    private static Resultado sobre(Map<String, Object> p, Resultado r) {
        Object s = p.get("sobre");
        return s instanceof Component ? r.sobre((Component) s) : r;
    }

    private static Resultado conElCambio(Resultado r, Cambio ch) {
        r.con("clave", ch.clave);
        if (ch.antes != null) r.con("antes", ch.antes);
        if (ch.ahora != null) r.con("valor", ch.ahora);
        return conLasQueQuedan(r, ch.quedan);
    }

    private static Resultado conLasQueQuedan(Resultado r, List<BpvmClient.EnvVar> todas) {
        List<Map<String, Object>> vars = new ArrayList<>(), part = new ArrayList<>();
        for (BpvmClient.EnvVar e : todas) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clave", e.key);
            m.put("valor", e.value);
            (deParticiones(e.key) ? part : vars).add(m);
        }
        r.con("variables", vars);
        if (!part.isEmpty()) r.con("de_particiones", part);
        return r;
    }

    /* ===== quien las tiene a la vista ===== */

    /** Desde ahora, `quien` recibe la lista (las que se editan, en el EDT) cada vez que se lee o se cambia. */
    public void mira(Consumer<List<BpvmClient.EnvVar>> quien) { mirando.add(quien); }

    public void dejaDeMirar(Consumer<List<BpvmClient.EnvVar>> quien) { mirando.remove(quien); }

    private void avisa(List<BpvmClient.EnvVar> todas) {
        List<BpvmClient.EnvVar> e = editables(todas);
        for (Consumer<List<BpvmClient.EnvVar>> m : mirando) m.accept(e);
    }

    /* ===== el cable, nunca en el EDT ===== */

    private interface Tarea<R> { R corre(BpvmClient c) throws IOException; }

    private <R> CompletableFuture<Resultado> enSegundoPlano(Map<String, Object> p, Tarea<R> tarea,
                                                            Function<R, Resultado> alAcabar) {
        final BpvmClient c = cliente.get();
        if (c == null) {
            return CompletableFuture.completedFuture(sobre(p, Resultado.aviso(TITULO,
                    "no hay ninguna placa conectada", JOptionPane.WARNING_MESSAGE)));
        }
        final CompletableFuture<Resultado> fin = new CompletableFuture<>();
        new SwingWorker<R, Void>() {
            @Override protected R doInBackground() throws Exception { return tarea.corre(c); }
            @Override protected void done() {
                try {
                    fin.complete(alAcabar.apply(get()));
                } catch (Exception e) {
                    String m = PicoExplorer.motivo(e.getCause() != null ? e.getCause() : e);
                    if (m.contains("[UNSUPPORTED]")) {
                        m = "aquí no hay variables de entorno: las tienen las placas y el micro simulado, no la VM Java ("
                                + m + ")";
                    }
                    consola.accept("[Placa] entorno: " + m);
                    fin.complete(sobre(p, Resultado.aviso("Error de gestión de placa", m, JOptionPane.ERROR_MESSAGE)));
                }
            }
        }.execute();
        return fin;
    }
}
