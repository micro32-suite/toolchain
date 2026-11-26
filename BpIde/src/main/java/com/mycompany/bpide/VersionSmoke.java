// ============================================================
// VersionSmoke.java — V9 #907: la versión de la imagen, al conectar.
//
//   1. qué se dice y cuándo se avisa (VersionPlaca), caso a caso;
//   2. por el camino de verdad: una placa de mentira en un puerto TCP, con
//      versión y sin ella, y la conexión del IDE (AbstractBpvmBackend); y lo que
//      dice `bp placa <…> info`, con su aviso, por la salida de errores.
//
// Uso:
//   java -cp BpIde/target/BpIde-8.5.jar com.mycompany.bpide.VersionSmoke
// Sale 0 si todo va, 1 si algo falla.
// ============================================================
package com.mycompany.bpide;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionSmoke {

    private static int fallos = 0;

    private static void mira(boolean cond, String que) {
        System.out.println((cond ? "  ok   : " : "  FALLO: ") + que);
        if (!cond) fallos++;
    }

    private static void igual(String espero, String sale, String que) {
        boolean va = espero == null ? sale == null : espero.equals(sale);
        mira(va, va ? que : que + ": «" + sale + "»");
    }

    private static final Pattern TIPO = Pattern.compile("\"type\":\"([A-Z_]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    /** Una placa de mentira: contesta al HELLO con ese nombre y esa versión (null: sin ella), y a lo demás, de trámite. */
    private static final class Placa implements AutoCloseable {
        private final ServerSocket ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        private final String quien, version;

        Placa(String quien, String version) throws IOException {
            this.quien = quien;
            this.version = version;
            Thread t = new Thread(this::sirve, "placa-de-mentira");
            t.setDaemon(true);
            t.start();
        }

        String destino() { return "127.0.0.1:" + ss.getLocalPort(); }

        private void sirve() {
            try {
                for (;;) {
                    try (Socket s = ss.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String l;
                        while ((l = in.readLine()) != null) {
                            Matcher mt = TIPO.matcher(l), mi = ID.matcher(l);
                            if (!mt.find() || !mi.find()) continue;
                            String id = mi.group(1);
                            String r = "HELLO".equals(mt.group(1))
                                    ? "{\"type\":\"HELLO_REPLY\",\"id\":" + id + ",\"protoVersion\":1,\"serverName\":\"" + quien
                                        + "\",\"serverBuild\":\"hoy\"" + (version == null ? "" : ",\"version\":\"" + version + "\"")
                                        + ",\"capabilities\":[\"META\"]}"
                                    : "{\"type\":\"" + mt.group(1) + "_REPLY\",\"id\":" + id + "}";
                            s.getOutputStream().write((r + "\n").getBytes(StandardCharsets.UTF_8));
                            s.getOutputStream().flush();
                        }
                    } catch (IOException seFue) { /* el siguiente */
                        if (ss.isClosed()) return;
                    }
                }
            } catch (Exception fin) { /* el ServerSocket, cerrado */ }
        }

        @Override public void close() throws IOException { ss.close(); }
    }

    /** Conecta y devuelve lo que dice el IDE y su aviso. */
    private static String[] conecta(String quien, String version) throws IOException {
        try (Placa p = new Placa(quien, version)) {
            RedBackend b = new RedBackend();
            String hola = b.connect(p.destino());
            String aviso = b.avisoVersion();
            b.close();
            return new String[] { hola, aviso };
        }
    }

    /** `bp placa <destino> info`: lo que escribe en la salida y en la de errores. */
    private static String[] info(String quien, String version) throws IOException {
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        try (Placa p = new Placa(quien, version)) {
            System.setOut(new PrintStream(bo, true, "UTF-8"));
            System.setErr(new PrintStream(be, true, "UTF-8"));
            PlacaCli.placa(new String[] { p.destino(), "info" });
        } finally {
            System.setOut(o);
            System.setErr(e);
        }
        return new String[] { bo.toString("UTF-8").trim(), be.toString("UTF-8").trim() };
    }

    public static void main(String[] args) throws Exception {
        System.out.println("-- comparar");
        mira(VersionPlaca.compara("8.5", "8.5") == 0, "8.5 = 8.5");
        mira(VersionPlaca.compara("8.5", "8.5.0") == 0, "8.5 = 8.5.0");
        mira(VersionPlaca.compara("8.5", "8.10") < 0, "8.5 < 8.10");
        mira(VersionPlaca.compara("9.0", "8.10") > 0, "9.0 > 8.10");
        mira(VersionPlaca.compara("8.5.1", "8.5") > 0, "8.5.1 > 8.5");

        System.out.println("-- lo que se dice");
        igual("imagen 8.5", VersionPlaca.dicha("bpvm-esp32p4", "8.5"), "una placa que la dice");
        igual("imagen anterior a la 8.5", VersionPlaca.dicha("bpvm-pico", ""), "una que no la dice");
        igual("versión 8.5", VersionPlaca.dicha("bpvm-sim", "8.5"), "el micro simulado");
        igual("", VersionPlaca.dicha("bpvm-java", ""), "la VM-Java no la dice, y no se echa en falta");
        igual("8.5 (la de esta instalación)", VersionPlaca.enElInfo("bpvm-pico", "8.5", "8.5"), "el INFO, la misma");
        igual("8.5; esta instalación es la 9.0", VersionPlaca.enElInfo("bpvm-pico", "8.5", "9.0"), "el INFO, otra");
        igual("anterior a la 8.5 (no la dice); esta instalación es la 9.0", VersionPlaca.enElInfo("bpvm-pico", "", "9.0"),
                "el INFO, sin ella");
        igual("", VersionPlaca.enElInfo("bpvm-java", "", "9.0"), "el INFO, la VM-Java");

        System.out.println("-- cuándo se avisa");
        igual(null, VersionPlaca.aviso("bpvm-pico", "8.5", "8.5"), "la misma: nada");
        igual(null, VersionPlaca.aviso("bpvm-java", "", "8.5"), "la VM-Java: nada");
        igual(null, VersionPlaca.aviso("bpvm-pico", "", ""), "un IDE en clases sueltas: nada");
        igual("la imagen de la placa es anterior a la 8.5 (no dice su versión) y esta instalación es la 8.5: lo nuevo puede no"
                + " enlazar o no ir; grábale la de esta instalación (docs/INSTALAR_FIRMWARE.md)",
                VersionPlaca.aviso("bpvm-pico", "", "8.5"), "sin versión");
        igual("la imagen de la placa es la 8.5 y esta instalación es la 9.0: lo nuevo puede no enlazar o no ir; grábale la de"
                + " esta instalación (docs/INSTALAR_FIRMWARE.md)", VersionPlaca.aviso("bpvm-pico", "8.5", "9.0"), "más vieja");
        igual("la imagen de la placa es la 9.0, más nueva que esta instalación (la 8.5): pon al día la instalación",
                VersionPlaca.aviso("bpvm-pico", "9.0", "8.5"), "más nueva");
        igual("el micro simulado es de la 8.5 y esta instalación es la 9.0: lo nuevo puede no enlazar o no ir; usa el de esta"
                + " instalación", VersionPlaca.aviso("bpvm-sim", "8.5", "9.0"), "el micro simulado, de otra");
        igual("el micro simulado es anterior a la 8.5 (no dice su versión) y esta instalación es la 8.5: lo nuevo puede no"
                + " enlazar o no ir; usa el de esta instalación", VersionPlaca.aviso("bpvm-sim", "", "8.5"),
                "el micro simulado, sin ella");

        String mia = VersionPlaca.mia();
        System.out.println("-- por el cable (esta instalación: " + mia + ")");
        mira(!mia.isEmpty(), "la de esta instalación, del manifest del jar");
        String[] r = conecta("bpvm-esp32p4", mia);
        igual("bpvm-esp32p4 por la red, imagen " + mia, r[0], "la misma: lo que se ve al conectar");
        igual(null, r[1], "la misma: sin aviso");
        r = conecta("bpvm-esp32p4", null);
        igual("bpvm-esp32p4 por la red, imagen anterior a la 8.5", r[0], "una imagen que no la dice");
        mira(r[1] != null && r[1].contains("no dice su versión") && r[1].contains("INSTALAR_FIRMWARE"), "y su aviso");
        r = conecta("bpvm-esp32p4", "99.1");
        mira(r[1] != null && r[1].contains("99.1, más nueva"), "una más nueva, y su aviso");
        r = conecta("bpvm-java", null);
        igual("bpvm-java por la red", r[0], "la VM-Java, como siempre");
        igual(null, r[1], "la VM-Java, sin aviso");

        System.out.println("-- bp placa info");
        r = info("bpvm-pico", mia);
        igual("version: " + mia + " (la de esta instalación)", r[0], "la misma");
        igual("", r[1], "y nada en la de errores");
        r = info("bpvm-pico", null);
        mira(r[0].startsWith("version: anterior a la 8.5 (no la dice)"), "sin ella");
        mira(r[1].startsWith("bp: ⚠ la imagen de la placa es anterior a la 8.5"), "y su aviso, en la de errores");

        System.out.println(fallos == 0 ? "VERSION SMOKE OK" : "VERSION SMOKE: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }
}
