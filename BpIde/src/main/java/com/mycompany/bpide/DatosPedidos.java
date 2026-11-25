package com.mycompany.bpide;

import edu.bpgenvm.util.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * V9 #870 (25-nov) — LOS DATOS QUE PIDE UN PROGRAMA ({@code IO.prompt}, en la VM Java): los contesta quien lo lanzó (Eduardo,
 * 23-nov: <i>«Contesta quien lo lanzó»</i>). Desde la ventana, su formulario ({@link PromptDialog}). Lanzado por su nombre (la
 * IA, un guion), el comando que lo lanzó acaba ahí, con lo que pide («pide»); el programa espera, y el comando
 * {@code responder} le da los valores y devuelve cómo sigue: el fin, u otros datos.
 *
 * <p>Uno por programa lanzado por su nombre. Todo en el EDT, menos {@link #escribe}.</p>
 */
final class DatosPedidos {

    /** A quien se le dice lo siguiente (el fin, o lo que pide); null: nadie espera. */
    private CompletableFuture<Resultado> espera;
    private BpvmClient cliente;
    private long promptId;
    private boolean pidiendo;

    /** La salida del programa, en orden con sus peticiones (la consola la recibe más tarde), y lo ya devuelto. */
    private final StringBuffer salida = new StringBuffer();
    private int dada;

    DatosPedidos(CompletableFuture<Resultado> espera) { this.espera = espera; }

    /** Lo que imprime el programa (en el hilo del cable). */
    void escribe(String s) { salida.append(s); }

    /** ¿Está esperando unos datos? */
    boolean pidiendo() { return pidiendo; }

    /** El programa pide datos: lo apunta, y el resultado que lo dice. */
    Resultado pide(BpvmClient c, long id, String spec) {
        cliente = c;
        promptId = id;
        pidiendo = true;
        Object que = spec;
        try { que = Json.parse(spec); } catch (RuntimeException e) { /* no es JSON: tal cual */ }
        String nueva = salida.substring(Math.min(dada, salida.length()));
        dada = salida.length();
        return Resultado.bien("el programa pide datos" + campos(que) + ": contéstalos con «responder» (espera hasta entonces)")
                .con("pide", que).con("salida", nueva);
    }

    /** Se lo dice a quien esperaba (a nadie, si ya se fue). */
    void di(Resultado r) {
        CompletableFuture<Resultado> f = espera;
        espera = null;
        if (f != null) f.complete(r);
    }

    /** Le da los valores al programa; `sigue`, a quien se le dirá cómo sigue. */
    void responde(String valores, CompletableFuture<Resultado> sigue) {
        pidiendo = false;
        espera = sigue;
        final BpvmClient c = cliente;
        final long id = promptId;
        Thread t = new Thread(() -> c.respondToPrompt(id, valores), "ide-responder");
        t.setDaemon(true);
        t.start();
    }

    /** « (nombre, edad)»: los campos que pide, si se dejan leer. */
    private static String campos(Object que) {
        List<String> ns = new ArrayList<>();
        Object fs = que instanceof Map ? ((Map<?, ?>) que).get("fields") : null;
        if (fs instanceof List) {
            for (Object f : (List<?>) fs) {
                Object n = f instanceof Map ? ((Map<?, ?>) f).get("name") : null;
                if (n != null) ns.add(n.toString());
            }
        }
        return ns.isEmpty() ? "" : " (" + String.join(", ", ns) + ")";
    }
}
