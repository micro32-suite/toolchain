package com.mycompany.bpide;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * V9 H2.2 (22-nov) — la prueba de la capa de los comandos ({@link Comandos}), sin ventana:
 * <pre>java -Djava.awt.headless=true -cp BpIde-8.5.jar com.mycompany.bpide.ComandosSmoke</pre>
 * Lo que se mira: un comando llamado por su nombre desde otro hilo corre en el EDT y devuelve su resultado; uno que no
 * existe, o una lógica que revienta, es un resultado «mal», no una excepción; lo largo completa el resultado al acabar;
 * la Action es una y lleva el texto, la ayuda y el atajo del comando; y desde la ventana, un diálogo cancelado no
 * ejecuta nada. Y de la segunda tanda: el que es su diálogo sólo se abre desde la ventana, y desde la ventana con los
 * parámetros ya dichos (un reciente del menú) no se pide nada. Sale con 0 si todo va bien.
 */
public final class ComandosSmoke {

    private static int fallos = 0;

    private static void mira(boolean ok, String que) {
        System.out.println((ok ? "ok    " : "FALLO ") + que);
        if (!ok) fallos++;
    }

    public static void main(String[] args) throws Exception {
        Comandos cs = new Comandos(null);
        AtomicBoolean enElEdt = new AtomicBoolean(false);
        AtomicInteger veces = new AtomicInteger();

        cs.registra("suma", "Suma", p -> {
            enElEdt.set(SwingUtilities.isEventDispatchThread());
            veces.incrementAndGet();
            int a = Integer.parseInt(String.valueOf(p.get("a")));
            int b = Integer.parseInt(String.valueOf(p.get("b")));
            return CompletableFuture.completedFuture(Resultado.bien("suma").con("total", a + b));
        }).corto("+").ayuda("Suma dos números").atajo(KeyStroke.getKeyStroke("ctrl F7"))
          .parametro("a", "un número").parametro("b", "otro");

        cs.registra("revienta", "Revienta", p -> { throw new IllegalStateException("a propósito"); });

        cs.registra("tarde", "Tarde", p -> {
            CompletableFuture<Resultado> f = new CompletableFuture<>();
            Thread t = new Thread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                f.complete(Resultado.bien("acabó tarde"));
            });
            t.setDaemon(true);
            t.start();
            return f;
        });

        cs.registra("cancelable", "Cancelable", p -> {
            veces.incrementAndGet();
            return CompletableFuture.completedFuture(Resultado.bien("no debería llegar"));
        }).pide(() -> null);   // como un selector de ficheros cancelado

        // 1. por su nombre, desde un hilo que no es el EDT
        Map<String, Object> p = new HashMap<>();
        p.put("a", 2);
        p.put("b", 40);
        Resultado r = cs.ejecuta("suma", p).get(5, TimeUnit.SECONDS);
        mira(r.ok && Integer.valueOf(42).equals(r.datos.get("total")), "por su nombre: " + r);
        mira(enElEdt.get(), "la lógica corre en el EDT aunque la llamen desde otro hilo");

        // 2. un nombre que no existe, y una lógica que revienta: resultados, no excepciones
        Resultado no = cs.ejecuta("noexiste", null).get(5, TimeUnit.SECONDS);
        mira(!no.ok && no.mensaje.contains("noexiste"), "un nombre que no existe: " + no);
        Resultado rev = cs.ejecuta("revienta", null).get(5, TimeUnit.SECONDS);
        mira(!rev.ok && rev.mensaje.contains("a propósito"), "una lógica que revienta: " + rev);

        // 3. lo largo: el resultado llega al acabar
        Resultado tarde = cs.ejecuta("tarde", null).get(5, TimeUnit.SECONDS);
        mira(tarde.ok && "acabó tarde".equals(tarde.mensaje), "lo largo, al acabar: " + tarde);

        // 4. la Action: una para el menú y el botón, con lo del comando
        Action a1 = cs.accion("suma");
        Action a2 = cs.accion("suma");
        mira(a1 == a2, "la Action de un comando es una sola");
        mira("Suma".equals(a1.getValue(Action.NAME)), "su texto es el del menú");
        mira("Suma dos números".equals(a1.getValue(Action.SHORT_DESCRIPTION)), "su ayuda");
        mira(KeyStroke.getKeyStroke("ctrl F7").equals(a1.getValue(Action.ACCELERATOR_KEY)), "su atajo");
        mira("+".equals(cs.get("suma").textoCorto()), "el texto corto, para la barra");
        mira(cs.get("suma").parametros().containsKey("a") && cs.get("suma").parametros().containsKey("b"),
                "sus parámetros, para la IA");

        // 5. un nombre, una vez
        boolean repetido = false;
        try { cs.registra("suma", "Otra", q -> null); } catch (IllegalArgumentException e) { repetido = true; }
        mira(repetido, "un nombre repetido no se registra");

        // 6. desde la ventana, un diálogo cancelado no ejecuta nada
        int antes = veces.get();
        final Object[] devuelto = { "sin llamar" };
        SwingUtilities.invokeAndWait(() -> devuelto[0] = cs.desdeLaVentana("cancelable"));
        mira(devuelto[0] == null && veces.get() == antes, "cancelado en su diálogo, no se ejecuta");

        // 7. la lista, para quien la pida
        mira(cs.describe().size() == 4 && cs.describe().get(0).startsWith("suma — Suma"), "la lista: " + cs.describe());

        // 8. el que es su diálogo: por su nombre, no se ejecuta (y lo dice); desde la ventana, sí
        AtomicInteger abierto = new AtomicInteger();
        cs.registra("dialogo", "Diálogo", q -> {
            abierto.incrementAndGet();
            return CompletableFuture.completedFuture(Resultado.bien("hecho"));
        }).soloVentana();
        Resultado sv = cs.ejecuta("dialogo", null).get(5, TimeUnit.SECONDS);
        mira(!sv.ok && sv.mensaje.contains("sólo se usa desde la ventana") && abierto.get() == 0,
                "sólo desde la ventana, por su nombre no se abre: " + sv);
        AtomicReference<CompletableFuture<Resultado>> desdeV = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> desdeV.set(cs.desdeLaVentana("dialogo")));
        Resultado rv = desdeV.get().get(5, TimeUnit.SECONDS);
        mira(rv.ok && abierto.get() == 1, "y desde la ventana, sí: " + rv);
        mira(cs.describe().get(4).endsWith("(sólo desde la ventana)"), "la lista lo dice: " + cs.describe().get(4));

        // 9. desde la ventana con los parámetros ya dichos (un reciente): no pide nada, y le llegan
        AtomicBoolean pidio = new AtomicBoolean(false);
        cs.registra("conpide", "Con pide", q -> CompletableFuture.completedFuture(
                Resultado.bien("recibido").con("fichero", q.get("fichero"))))
          .pide(() -> { pidio.set(true); return new HashMap<>(); });
        Map<String, Object> dichos = new HashMap<>();
        dichos.put("fichero", "C:/x/y.bp");
        AtomicReference<CompletableFuture<Resultado>> desdeR = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> desdeR.set(cs.desdeLaVentana("conpide", dichos)));
        Resultado rr = desdeR.get().get(5, TimeUnit.SECONDS);
        mira(rr.ok && "C:/x/y.bp".equals(rr.datos.get("fichero")) && !pidio.get(),
                "con los parámetros ya dichos, no pide y le llegan: " + rr);

        // 10. muestra: un resultado sin aviso no enseña nada (ni revienta sin ventana)
        boolean sinAviso = true;
        try { cs.muestra(Resultado.bien("nada que enseñar")); cs.muestra(null); }
        catch (RuntimeException e) { sinAviso = false; }
        mira(sinAviso, "un resultado sin aviso no enseña ningún diálogo");

        System.out.println(fallos == 0 ? "ComandosSmoke: TODO BIEN" : "ComandosSmoke: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }
}
