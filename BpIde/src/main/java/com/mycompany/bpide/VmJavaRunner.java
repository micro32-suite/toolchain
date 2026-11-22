// ============================================================
// VmJavaRunner.java
// V9 #848 — arranque y parada de la VM JAVA del PC como placa: el
// «Arrancar» de «VM Java (TCP v1)» en el panel de la placa. Hasta
// ahora esa opción sólo se conectaba a una VM que ya estuviera en
// marcha (localhost:7332), y nadie la arrancaba; el micro simulado,
// en cambio, tenía su botón (SimRunner). Esto es lo mismo para la
// VM Java: lanzarla como subproceso con la misma orden que el Run
// (BpvmClient.ordenVmJava), esperar a que escuche y pararla al
// terminar. El protocolo es el de siempre: el panel se conecta luego
// con su BpvmBackend, como a cualquier VM remota.
//
// Su FS y su flash viven en una carpeta propia que PERSISTE entre
// arranques, como en una placa (o en el micro simulado): lo que
// subes sigue ahí al volver. Borrar esa carpeta = placa nueva.
// ============================================================
package com.mycompany.bpide;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;

/** Ciclo de vida de la VM Java del PC arrancada desde el panel de la placa. Una instancia por IDE. */
public final class VmJavaRunner {

    /** Lo que tarda como mucho en escuchar: arrancar una JVM es más lento que el binario del simulador. */
    private static final int ESPERA_MS = 15000;

    private Process proc;
    private Thread  pump;
    private final Consumer<String> log;   // a la consola del IDE (puede ser null)

    public VmJavaRunner(Consumer<String> log) { this.log = log; }

    private void say(String s) { if (log != null) log.accept(s); }

    public boolean isRunning() { return proc != null && proc.isAlive(); }

    /** La carpeta de la VM Java como placa: su FS ({@code fs/}) y su flash ({@code flash/}). Nunca null. La propiedad
     *  {@code bpide.vmjava.dir} la cambia (las pruebas, para no tocar la del usuario). */
    public static Path carpeta() {
        String otra = System.getProperty("bpide.vmjava.dir");
        if (otra != null && !otra.isEmpty()) return Paths.get(otra);
        String home = System.getProperty("user.home");
        return Paths.get(home != null ? home : ".", ".bpide-vmjava");
    }

    /** La stdlib que se le da: la del {@code BpVM.cfg} de la carpeta desde la que se lanzó el IDE, y si no hay, la de
     *  su instalación (V9 #867: el cfg junto al jar o subiendo desde él; sin él, la {@code bpstdlib/} que viaja junto
     *  al jar en el ZIP). Null si no aparece ninguna: la VM la buscará por su cuenta. */
    static String stdlibDir() {
        try {
            edu.bpgenvm.config.VmConfig cfg = IdePrefs.cfgPara(null);
            if (cfg.stdlibDir != null && !cfg.stdlibDir.isEmpty()) return cfg.stdlibDir;
        } catch (Throwable ignored) { /* sin cfg legible: sin stdlib */ }
        return null;
    }

    /** Arranca la VM Java escuchando en {@code port} y espera a que acepte conexiones. Devuelve el endpoint
     *  "127.0.0.1:puerto", o lanza si no arranca. */
    public synchronized String start(int port) throws IOException {
        if (isRunning()) return "127.0.0.1:" + port;

        Path dir = carpeta();
        Path fs = Files.createDirectories(dir.resolve("fs"));
        Path flash = Files.createDirectories(dir.resolve("flash"));
        String stdlib = stdlibDir();
        if (stdlib == null)
            say("[vm-java] aviso: no encuentro la stdlib (ni BpVM.cfg ni bpstdlib/ junto al IDE): los programas que"
              + " la usen fallarán con 'falta el modulo Core'\n");

        List<String> cmd = BpvmClient.ordenVmJava(port, fs.toString(), flash.toString(), stdlib,
                /*waitClient=*/false, /*modOrProject=*/null);
        say("[vm-java] " + String.join(" ", cmd) + "\n");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        proc = pb.start();

        /* Su salida va a la consola del IDE: si la VM se queja (el puerto ocupado, la stdlib que no está), que se
         * vea, en vez de morir en silencio. */
        final Process p = proc;
        pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) say("[vm-java] " + line + "\n");
            } catch (IOException ignored) {
                /* el proceso murió mientras leíamos: no es noticia */
            }
        }, "vm-java-output");
        pump.setDaemon(true);
        pump.start();

        /* Esperar a que ESCUCHE de verdad, sondeando el puerto (como el simulador). */
        long deadline = System.currentTimeMillis() + ESPERA_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                proc = null;
                throw new IOException("la VM Java terminó nada más arrancar (exit " + p.exitValue()
                        + ") — mira la consola");
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 250);
                say("[vm-java] escuchando en 127.0.0.1:" + port + "\n");
                return "127.0.0.1:" + port;
            } catch (IOException notYet) {
                try { Thread.sleep(150); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        stop();
        throw new IOException("la VM Java no aceptó conexiones en " + (ESPERA_MS / 1000) + " s (¿el puerto " + port
                + " ocupado?)");
    }

    /** Para la VM Java. Idempotente. */
    public synchronized void stop() {
        if (proc == null) return;
        Process p = proc;
        proc = null;
        p.destroy();
        /* Esperar a que se vaya DE VERDAD, también si hay que forzarla: si no, el puerto sigue cogido un momento y un
         * «Arrancar» seguido, o el siguiente IDE, se lo encuentra ocupado. */
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        say("[vm-java] parada\n");
    }

    /** "localhost:7332" → 7332 si el host es este PC; -1 si es otro sitio (ahí no se arranca nada) o no se lee. */
    static int puertoLocal(String endpoint) {
        if (endpoint == null) return -1;
        String e = endpoint.trim();
        int dos = e.lastIndexOf(':');
        if (dos <= 0) return -1;
        String host = e.substring(0, dos).trim().toLowerCase();
        if (!(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]")))
            return -1;
        try {
            int port = Integer.parseInt(e.substring(dos + 1).trim());
            return (port > 0 && port < 65536) ? port : -1;
        } catch (NumberFormatException nf) {
            return -1;
        }
    }
}
