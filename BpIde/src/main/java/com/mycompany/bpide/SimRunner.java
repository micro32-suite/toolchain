// ============================================================
// SimRunner.java
// H10 — arranque y parada del MICRO SIMULADO (bpvm-sim), la placa
// de mentira que corre en el PC. El IDE la levanta como subproceso
// y luego se conecta a ella por TCP igual que a una placa de verdad
// (mismo wire v1, mismo BpvmBackend) — por eso aquí no hay nada de
// protocolo: sólo localizar el binario, lanzarlo con la config del
// usuario, esperar a que escuche y matarlo al terminar.
//
// La "flash", la imagen del FS y —desde V9 (#809)— la zona de packs
// viven en simDataDir y PERSISTEN entre arranques, como en una placa:
// si subes un fichero o grabas un pack y paras el simulador, al volver
// sigue ahí. Borrar esa carpeta = placa nueva.
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Ciclo de vida del proceso bpvm-sim. Una instancia por IDE. */
public final class SimRunner {

    private Process proc;
    private Thread  pump;
    private final Consumer<String> log;   // a la consola del IDE (puede ser null)

    public SimRunner(Consumer<String> log) { this.log = log; }

    /** Una línea, sin «\n»: quien la recibe pone el suyo (la consola del IDE; `bp`, la salida de errores). Con él,
     *  tras cada línea del simulado salía otra en blanco en la consola (V9 #874). */
    private void say(String s) { if (log != null) log.accept(s); }

    public boolean isRunning() { return proc != null && proc.isAlive(); }

    /** Ruta del ejecutable del simulador, o null si no aparece. Sale de la MISMA
     *  raíz de bpgenvm-c que ya se configura para el AOT (una ruta, no dos). */
    public static Path locateExe(IdePrefs prefs) {
        // 1) La INSTALACIÓN: en el ZIP el binario viaja ya compilado en bin/, que
        //    es donde lo encontrará quien se baje el paquete y no tenga fuentes.
        Path bin = IdePrefs.installSubdir("bin");
        if (bin != null) {
            Path p = enExiste(bin);
            if (p != null) return p;
        }
        // 2) El árbol de fuentes: <bpgenvm-c>/build, lo que hay al desarrollar.
        String root = (prefs != null && prefs.aotBpgenvmDir != null && !prefs.aotBpgenvmDir.isEmpty())
                ? prefs.aotBpgenvmDir : AotBuild.autodetectBpgenvm(null);
        if (root == null) return null;
        return enExiste(Paths.get(root, "build"));
    }

    /** bpvm-sim[.exe] dentro de `dir`, o null. Un solo sitio para las dos formas
     *  del nombre — así añadir un candidato nuevo no duplica el .exe/.sin-exe. */
    private static Path enExiste(Path dir) {
        Path win = dir.resolve("bpvm-sim.exe");
        if (Files.isRegularFile(win)) return win;
        Path nix = dir.resolve("bpvm-sim");
        if (Files.isRegularFile(nix)) return nix;
        return null;
    }

    /** Mensaje de ayuda cuando falta el binario: decir QUÉ hacer, no sólo que falla. */
    public static String missingExeHelp(IdePrefs prefs) {
        // Si esto es una INSTALACIÓN (hay bin/ junto al jar), el binario deberia
        // venir en el paquete: decirle a alguien que "lo compile" seria un mal
        // consejo — lo que le pasa es que su copia esta incompleta.
        Path bin = IdePrefs.installSubdir("bin");
        if (bin != null) {
            return "falta " + bin.resolve("bpvm-sim.exe")
                 + " — deberia venir en el paquete; vuelve a descomprimir el ZIP entero";
        }
        String root = (prefs != null && prefs.aotBpgenvmDir != null && !prefs.aotBpgenvmDir.isEmpty())
                ? prefs.aotBpgenvmDir : AotBuild.autodetectBpgenvm(null);
        if (root == null) {
            return "no encuentro la raíz de bpgenvm-c; configúrala en "
                 + "Project → Micro simulado… (o en Ajustes AOT)";
        }
        return "falta " + Paths.get(root, "build", "bpvm-sim.exe")
             + " — compílalo con:  make sim   (o  make LVGL=1 sim  para tener ventana)";
    }

    /** Packs de la biblioteca del IDE que se "graban" en el micro simulado.
     *  Tope 8 = el del propio simulador; si hay más, se avisa en vez de recortar
     *  en silencio (un pack que falta se manifiesta como un módulo que no existe,
     *  y eso es un rato de búsqueda). */
    static List<Path> packsToBurn(IdePrefs prefs) {
        List<Path> out = new ArrayList<>();
        String dir = prefs.packsDirEffective();
        if (dir == null) return out;
        Path d = Paths.get(dir);
        if (!Files.isDirectory(d)) return out;
        try (java.util.stream.Stream<Path> s = Files.list(d)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pack"))
             .sorted()
             .forEach(out::add);
        } catch (IOException ignored) {
            /* carpeta ilegible: se arranca sin packs y el aviso de start() lo dice */
        }
        return out;
    }

    /** Lo que se vacía de la placa simulada, en su carpeta de datos: su FS y su zona de packs. Su flash
     *  ({@code micro.flash}, el ENV: las particiones y lo que se le haya puesto) se queda. */
    static final String[] VACIABLE = { "micro.fs", "micro.packs" };

    /** V9 #871 — VACÍA el micro simulado (Eduardo, 23-nov: «un parámetro para vaciar la VM y que arranque como si
     *  fuese la primera vez»): borra su FS y su zona de packs, y nada más de su carpeta. Su configuración se queda: al
     *  probarlo, con la flash borrada arrancaba sin configurar (estado 0, sin particiones) y el IDE pedía la gestión de
     *  placa (Eduardo: «quizás destruir la partición no entraba»). El arranque siguiente: con la zona de packs virgen,
     *  se graba la librería de esta instalación (#809), y en el FS no queda nada de antes (en la prueba de la IA del
     *  23-nov, un /app con Core, Gui… de una versión anterior tapaba los de la placa). Con el simulador en marcha no se
     *  puede (sus ficheros están abiertos): lanza. Devuelve los que ha borrado. */
    synchronized List<Path> vacia(IdePrefs prefs) throws IOException {
        if (isRunning()) throw new IOException("el micro simulado está en marcha: páralo antes de vaciarlo");
        Path dataDir = Paths.get(prefs.simDataDirEffective());
        List<Path> borrados = new ArrayList<>();
        for (String f : VACIABLE) {
            Path p = dataDir.resolve(f);
            /* Windows suelta los ficheros de un proceso que acaba de morir con un poco de retraso: unos reintentos,
             * hasta ~3 s; si sigue sin poder, el motivo de Windows. */
            for (int intento = 0; ; intento++) {
                try {
                    if (Files.deleteIfExists(p)) borrados.add(p);
                    break;
                } catch (java.nio.file.FileSystemException ex) {
                    if (intento >= 15) throw ex;
                    try { Thread.sleep(200); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; }
                }
            }
        }
        return borrados;
    }

    /** Arranca el simulador con la configuración de `prefs` y espera a que acepte
     *  conexiones. Devuelve el endpoint "host:puerto", o lanza si no arranca. */
    public synchronized String start(IdePrefs prefs) throws IOException {
        if (isRunning()) return "127.0.0.1:" + prefs.simPort;

        Path exe = locateExe(prefs);
        if (exe == null) throw new IOException(missingExeHelp(prefs));

        Path dataDir = Paths.get(prefs.simDataDirEffective());
        Files.createDirectories(dataDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("--port=" + prefs.simPort);
        cmd.add("--flash-file=" + dataDir.resolve("micro.flash"));
        cmd.add("--fs=" + dataDir.resolve("micro.fs"));
        cmd.add("--mem=" + (prefs.simRamKb * 1024L));
        cmd.add("--psram=" + (prefs.simPsramKb * 1024L));
        cmd.add("--flash=" + (prefs.simFlashKb * 1024L));
        if (prefs.simNoScreen) cmd.add("--no-screen");
        else cmd.add("--screen=" + prefs.simScreenW + "x" + prefs.simScreenH);

        /* La LIBRERÍA, "grabada" en la zona de packs del micro simulado.
         * Una placa real trae la stdlib embebida en el firmware, por eso el IDE
         * nunca sube Core (ni Str, ni…): da por hecho que ya está. El simulado
         * arranca con el FS vacío, así que sin esto el primer programa que use
         * cualquier cosa de la librería muere con "falta el modulo Core en el
         * FS". Montar los packs es el equivalente honesto: el código se ejecuta
         * XIP desde la zona de packs, sin copiarlo al FS, igual que en placa. */
        /* V9 #809 — la zona de packs, PERMANENTE, en micro.packs (como micro.flash): lo que se graba desde el panel de
         * Packs se queda al cerrar. Y la librería se graba sólo con la zona VIRGEN —el primer arranque, o tras borrar
         * la carpeta del simulado—, como en una placa (Eduardo, 22-nov: «Sólo la 1ª vez»): después, lo instalado se
         * queda, y se cambia desde el panel de Packs. */
        Path zonaPacks = dataDir.resolve("micro.packs");
        boolean virgen = !Files.exists(zonaPacks);
        cmd.add("--packs-file=" + zonaPacks);
        int nPacks = 0;
        if (virgen) {
            for (Path p : packsToBurn(prefs)) {
                cmd.add("--pack=" + p);
                nPacks++;
            }
            if (nPacks == 0)
                say("[sim] aviso: sin librería de packs — los programas que usen la "
                  + "stdlib fallarán con 'falta el modulo Core'. Configúrala en el "
                  + "engranaje del micro simulado.");
        } else {
            say("[sim] la zona de packs, la de la última vez (" + zonaPacks.getFileName() + "): para cambiar un pack,"
              + " el panel de Packs; para volver a la librería de esta instalación, «Vaciarlo», en el engranaje");
        }

        say("[sim] " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dataDir.toFile());
        pb.redirectErrorStream(true);
        proc = pb.start();

        /* Su stdout va a la consola del IDE: si el simulador se queja (FS que no
         * monta, puerto ocupado...), que se vea, en vez de morir en silencio. */
        final Process p = proc;
        pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) say("[sim] " + line);
            } catch (IOException ignored) {
                /* el proceso murió mientras leíamos: no es noticia */
            }
        }, "bpvm-sim-output");
        pump.setDaemon(true);
        pump.start();

        /* Esperar a que ESCUCHE de verdad. Conectar "un poco después" a ciegas es
         * lo que produce el fallo intermitente de "connection refused" en máquinas
         * lentas; aquí se sondea el puerto hasta 5 s. */
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                proc = null;
                throw new IOException("el simulador terminó nada más arrancar (exit "
                        + p.exitValue() + ") — mira la consola");
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", prefs.simPort), 250);
                say("[sim] escuchando en 127.0.0.1:" + prefs.simPort);
                return "127.0.0.1:" + prefs.simPort;
            } catch (IOException notYet) {
                try { Thread.sleep(100); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        stop();
        throw new IOException("el simulador no aceptó conexiones en 5 s (puerto "
                + prefs.simPort + " ocupado?)");
    }

    /** Para el simulador. Idempotente. */
    public synchronized void stop() {
        if (proc == null) return;
        Process p = proc;
        proc = null;
        p.destroy();
        try {
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                /* V9 #871 — y hasta que acabe de verdad: con sus ficheros abiertos, vaciarlo (o un arranque nuevo
                 * sobre los mismos) fallaría en Windows. */
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        say("[sim] parado");
    }
}
