package com.mycompany.bpide;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.bpgenvm.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * V9 H2.3 (22-nov) — EL SERVIDOR PARA LA IA: MCP por HTTP («Streamable HTTP»), dentro del propio IDE.
 *
 * <p>Cada comando del IDE ({@link Comandos}) que no es «sólo desde la ventana» es una herramienta, con su nombre, su
 * explicación para la IA y el esquema de sus parámetros. La IA (Claude Code: {@code claude mcp add --transport http
 * basicplus http://127.0.0.1:<puerto>/mcp}) las lista y las llama, y recibe el resultado del comando en datos.</p>
 *
 * <p>Las reglas (las del análisis del H2, `#728`): escucha SÓLO en 127.0.0.1 y está apagado por defecto (se enciende
 * desde el menú IA, o con el sí de la persona cuando la IA abre el IDE con --ia: `#873`); de los argumentos, sólo los parámetros documentados (lo que la ventana mete por su cuenta no lo
 * puede pasar la IA, y así no abre diálogos en la pantalla de quien usa el IDE); una ruta del PC, dentro de la carpeta
 * de la IA (la que se eligió al encenderlo); «un programa a la vez», el de los comandos; cada llamada y su resultado, en
 * la consola del IDE («[ia] …»); y un plazo por llamada (un programa que no acaba no deja a la IA esperando siempre).</p>
 *
 * <p>El protocolo: un punto, {@code /mcp}; JSON-RPC 2.0 por POST (initialize, ping, tools/list, tools/call), la respuesta
 * en application/json; una notificación, 202 sin cuerpo; sin SSE (el GET, 405). El JSON, el de la casa.</p>
 */
public final class ServidorIa {

    /** Las versiones del protocolo que habla: si la IA pide una de éstas, ésa; si no, la primera. */
    static final String[] VERSIONES = { "2025-06-18", "2025-03-26" };

    private final Comandos comandos;
    private final Consumer<String> consola;
    private final long plazoMs;

    private volatile HttpServer server;
    private volatile ExecutorService pool;
    private volatile Path carpeta;
    private volatile int puerto;

    /** `consola`: la del IDE (desde cualquier hilo); `plazoMs`: lo que se espera a un comando antes de contestar que
     *  sigue en marcha. */
    public ServidorIa(Comandos comandos, Consumer<String> consola, long plazoMs) {
        this.comandos = comandos;
        this.consola = consola;
        this.plazoMs = plazoMs;
    }

    /** Lo pone en marcha en 127.0.0.1:`puerto`, con `carpeta` como la de la IA. */
    public synchronized void arranca(int puerto, Path carpeta) throws IOException {
        para();
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), puerto), 0);
        s.createContext("/mcp", this::atiende);
        ExecutorService p = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ia-mcp");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(p);   // una llamada larga (ejecutar) no frena a las demás (parar)
        s.start();
        this.server = s;
        this.pool = p;
        this.puerto = puerto;
        this.carpeta = carpeta.toAbsolutePath().normalize();
    }

    public synchronized void para() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    public boolean enMarcha() { return server != null; }

    public String url() { return "http://127.0.0.1:" + puerto + "/mcp"; }

    public Path carpeta() { return carpeta; }

    // ---- HTTP ----

    private void atiende(HttpExchange ex) {
        try {
            String origen = ex.getRequestHeaders().getFirst("Origin");
            if (origen != null && !origenLocal(origen)) {   // un navegador desde otro sitio (DNS rebinding): no
                responde(ex, 403, null);
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.getResponseHeaders().add("Allow", "POST");
                responde(ex, 405, null);
                return;
            }
            Object msg;
            try {
                msg = Json.parse(leeTodo(ex.getRequestBody()));
            } catch (RuntimeException e) {
                respondeJson(ex, error(null, -32700, "JSON mal formado: " + e.getMessage()));
                return;
            }
            if (!(msg instanceof Map)) {
                respondeJson(ex, error(null, -32600, "se esperaba un mensaje JSON-RPC (un objeto; sin lotes)"));
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) msg;
            Object metodo = m.get("method");
            if (!(metodo instanceof String) || !m.containsKey("id")) {   // una notificación (o una respuesta): nada
                responde(ex, 202, null);
                return;
            }
            Object id = m.get("id");
            Map<String, Object> params = mapa(m.get("params"));
            Object respuesta;
            switch ((String) metodo) {
                case "initialize": respuesta = ok(id, initialize(params)); break;
                case "ping":       respuesta = ok(id, new LinkedHashMap<String, Object>()); break;
                case "tools/list": respuesta = ok(id, herramientas()); break;
                case "tools/call": {
                    String nombre = params.get("name") instanceof String ? (String) params.get("name") : null;
                    Comandos.Comando c = nombre != null ? comandos.get(nombre) : null;
                    if (c == null || c.esSoloVentana()) {
                        respuesta = error(id, -32602, "no hay ninguna herramienta «" + nombre + "»");
                    } else {
                        respuesta = ok(id, llama(c, mapa(params.get("arguments"))));
                    }
                    break;
                }
                default: respuesta = error(id, -32601, "método desconocido: " + metodo);
            }
            respondeJson(ex, respuesta);
        } catch (Throwable t) {
            try { respondeJson(ex, error(null, -32603, "error interno: " + t)); } catch (Throwable ignored) { }
        } finally {
            ex.close();
        }
    }

    private static boolean origenLocal(String origen) {
        String o = origen.toLowerCase(java.util.Locale.ROOT);
        return o.equals("null") || o.matches("https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?/?");
    }

    private static String leeTodo(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void responde(HttpExchange ex, int codigo, byte[] cuerpo) throws IOException {
        ex.sendResponseHeaders(codigo, cuerpo == null ? -1 : cuerpo.length);
        if (cuerpo != null) {
            try (OutputStream os = ex.getResponseBody()) { os.write(cuerpo); }
        }
    }

    private static void respondeJson(HttpExchange ex, Object json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        responde(ex, 200, Json.write(json).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapa(Object v) {
        return (v instanceof Map) ? (Map<String, Object>) v : new LinkedHashMap<String, Object>();
    }

    private static Map<String, Object> ok(Object id, Object resultado) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", resultado);
        return r;
    }

    private static Map<String, Object> error(Object id, long codigo, String mensaje) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", codigo);
        e.put("message", mensaje);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", e);
        return r;
    }

    // ---- MCP ----

    private Map<String, Object> initialize(Map<String, Object> params) {
        Object pedida = params.get("protocolVersion");
        String version = VERSIONES[0];
        for (String v : VERSIONES) if (v.equals(pedida)) version = v;
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("listChanged", false);
        Map<String, Object> capacidades = new LinkedHashMap<>();
        capacidades.put("tools", tools);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "basicplus-ide");
        info.put("title", "IDE de BasicPlus");
        info.put("version", "9");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", version);
        r.put("capabilities", capacidades);
        r.put("serverInfo", info);
        r.put("instructions", instrucciones());
        return r;
    }

    private String instrucciones() {
        return "Manejas el IDE de BasicPlus: un lenguaje con sintaxis BASIC moderna (y JC, la misma con la de Java y C#)"
                + " para microcontroladores de 32 bits, que compila a bytecode (.mod) y corre igual en el PC y en las"
                + " placas. Cada herramienta es un comando del IDE, lo mismo que sus menús y sus botones, y quien usa el"
                + " IDE ve lo que haces en su consola («[ia] …»). Para trabajar: escribe el fuente (.bp o .jc) en el"
                + " disco y ábrelo con «abrir» (lo de la pestaña activa, o el proyecto abierto, es lo que compilan y"
                + " ejecutan los demás); «compilar» da los errores con su línea; «ejecutar» lo ejecuta en el PC y"
                + " devuelve lo que imprime («salida») y su código de salida («codigo»). Para una placa o el micro"
                + " simulado (una placa que corre en el PC): «placa_conectar» o «micro_simulado», y después"
                + " «ejecutar_placa». Los ajustes de la placa (sus variables de entorno: la memoria de los packs, el panel,"
                + " la SD…): «entorno_listar», «entorno_poner» y «entorno_borrar»; se leen al arrancar («placa_reiniciar»"
                + " y «placa_conectar»). Para depurar: «punto_parada», «depurar» y los pasos. Las rutas del PC, dentro de "
                + carpeta + " (una relativa se toma desde ahí). Un programa a la vez; uno que no acaba (una GUI), se"
                + " para con «parar». Con la placa, una herramienta cada vez, no varias a la vez: atiende un listado,"
                + " una subida o una bajada cada vez, y a lo que llega a destiempo contesta «ocupada». La documentación"
                + " del lenguaje está en la carpeta docs de la instalación (el manual, la referencia y la guía del IDE)."
                + guia();
    }

    /** V9 H1.3 (#854) — la guía para IA, si está en la carpeta (el IDE la deja al encender el servidor). */
    private String guia() {
        Path g = carpeta != null ? carpeta.resolve(GuiaIa.GUIA) : null;
        if (g == null || !java.nio.file.Files.isRegularFile(g)) return "";
        return " ANTES DE ESCRIBIR BasicPlus, lee la guía para IA, " + g + " (en inglés): no es Java ni Visual Basic,"
                + " y dice dónde está la API, cómo se compila y se ejecuta (también sin el IDE: bp) y las trampas de"
                + " quien viene de Java o de C#.";
    }

    private Map<String, Object> herramientas() {
        List<Object> lista = new ArrayList<>();
        for (Comandos.Comando c : comandos.todos()) {
            if (c.esSoloVentana()) continue;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", c.id);
            h.put("title", c.texto());
            h.put("description", c.explicacion());
            h.put("inputSchema", esquema(c));
            lista.add(h);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tools", lista);
        return r;
    }

    private Map<String, Object> esquema(Comandos.Comando c) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, String> p : c.parametros().entrySet()) {
            String tipo = c.tipoDe(p.getKey());
            Map<String, Object> s = new LinkedHashMap<>();
            switch (tipo) {
                case "numero": s.put("type", "integer"); break;
                case "sino":   s.put("type", "boolean"); break;
                case "lista":
                case "rutas": {
                    s.put("type", "array");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", "string");
                    s.put("items", item);
                    break;
                }
                default: s.put("type", "string");
            }
            String desc = p.getValue();
            if (tipo.equals("ruta") || tipo.equals("rutas")) desc += " (del PC, dentro de " + carpeta + ")";
            s.put("description", desc);
            props.put(p.getKey(), s);
        }
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "object");
        e.put("properties", props);
        if (!c.necesarios().isEmpty()) e.put("required", new ArrayList<Object>(c.necesarios()));
        e.put("additionalProperties", false);
        return e;
    }

    /** Una llamada: los argumentos, mirados (sólo los documentados, los que hacen falta, y las rutas dentro de la
     *  carpeta); el comando, por su nombre; y su resultado, en datos. */
    private Map<String, Object> llama(Comandos.Comando c, Map<String, Object> args) {
        Map<String, Object> p = new LinkedHashMap<>();
        List<String> sobran = new ArrayList<>();
        for (Map.Entry<String, Object> a : args.entrySet()) {
            if (c.parametros().containsKey(a.getKey())) p.put(a.getKey(), a.getValue());
            else sobran.add(a.getKey());
        }
        if (!sobran.isEmpty()) {
            return error(c.id + " no tiene " + (sobran.size() == 1 ? "el parámetro " : "los parámetros ") + sobran
                    + "; los suyos: " + c.parametros().keySet());
        }
        for (String n : c.necesarios()) {
            if (p.get(n) == null) return error(c.id + " necesita «" + n + "»: " + c.parametros().get(n));
        }
        for (Map.Entry<String, Object> a : p.entrySet()) {
            String tipo = c.tipoDe(a.getKey());
            if (tipo.equals("ruta")) {
                String r = dentro(a.getValue());
                if (r == null) return error(c.id + ": «" + a.getValue() + "» está fuera de la carpeta de la IA (" + carpeta + ")");
                a.setValue(r);
            } else if (tipo.equals("rutas")) {
                List<Object> rs = new ArrayList<>();
                Collection<?> vs = (a.getValue() instanceof Collection) ? (Collection<?>) a.getValue()
                                                                       : Collections.singletonList(a.getValue());
                for (Object v : vs) {
                    String r = dentro(v);
                    if (r == null) return error(c.id + ": «" + v + "» está fuera de la carpeta de la IA (" + carpeta + ")");
                    rs.add(r);
                }
                a.setValue(rs);
            }
        }
        consola.accept("[ia] " + c.id + (p.isEmpty() ? "" : " " + Json.write(p)) + "\n");
        Resultado r;
        try {
            r = comandos.ejecuta(c.id, p).get(plazoMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            String msg = c.id + ": sin acabar en " + (plazoMs / 1000) + " s; sigue en marcha (para pararlo, «parar»;"
                    + " depurando, «parar_depuracion»)";
            consola.accept("[ia] → " + msg + "\n");
            return error(msg);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return error(c.id + ": interrumpido");
        } catch (Exception e) {
            return error(c.id + ": " + e);
        }
        String primera = r.mensaje == null ? "" : r.mensaje.split("\n", 2)[0];
        consola.accept("[ia] → " + (r.ok ? "bien" : "mal") + ": " + primera + "\n");
        Map<String, Object> estructura = new LinkedHashMap<>();
        estructura.put("ok", r.ok);
        estructura.put("mensaje", r.mensaje);
        estructura.put("datos", r.datos());
        String texto = r.mensaje + (r.datos().isEmpty() ? "" : "\n" + Json.write(r.datos()));
        return resultado(texto, estructura, !r.ok);
    }

    /** La ruta, absoluta y normalizada, si está dentro de la carpeta de la IA (una relativa, desde ella); null si no. */
    private String dentro(Object v) {
        if (v == null) return null;
        Path r = Paths.get(v.toString());
        if (!r.isAbsolute()) r = carpeta.resolve(r);
        r = r.toAbsolutePath().normalize();
        return r.startsWith(carpeta) ? r.toString() : null;
    }

    private static Map<String, Object> resultado(String texto, Map<String, Object> estructura, boolean esError) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "text");
        t.put("text", texto);
        List<Object> contenido = new ArrayList<>();
        contenido.add(t);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", contenido);
        if (estructura != null) r.put("structuredContent", estructura);
        r.put("isError", esError);
        return r;
    }

    private static Map<String, Object> error(String mensaje) {
        return resultado(mensaje, null, true);
    }
}
