// ============================================================
// VmJavaRunnerSmoke.java
// V9 #848 — el «Arrancar» de «VM Java (TCP v1)» sin la ventana: lo que
// hace el panel, paso a paso. Arranca la VM Java con VmJavaRunner, se
// conecta con un BpvmBackend (el HELLO y el LIST), se desconecta y se
// VUELVE a conectar —el panel puede hacerlo sin pararla—, la para y
// comprueba que ya no escucha.
//
//   java -cp BpIde-<versión>.jar com.mycompany.bpide.VmJavaRunnerSmoke [puerto]
//
// Sale con 0 si todo va; con 1 y el porqué, si no.
// ============================================================
package com.mycompany.bpide;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

public final class VmJavaRunnerSmoke {

    private VmJavaRunnerSmoke() { }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7399;
        VmJavaRunner vm = new VmJavaRunner(s -> System.out.print(s));
        int fallos = 0;
        try {
            String ep = vm.start(port);
            System.out.println("[smoke] arrancada en " + ep);
            for (int vez = 1; vez <= 2; vez++) {
                Backend b = new BpvmBackend();
                String hello = b.connect("127.0.0.1:" + port);
                List<Backend.Entry> files = b.list();
                System.out.println("[smoke] conexión " + vez + ": " + hello + " | LIST: " + files.size() + " entradas");
                b.close();
            }
        } catch (Exception e) {
            System.out.println("[smoke] FALLO: " + e);
            fallos++;
        } finally {
            vm.stop();
        }
        if (vm.isRunning()) {
            System.out.println("[smoke] FALLO: la VM sigue viva tras parar");
            fallos++;
        }
        if (escucha(port)) {   // el sistema puede tardar un instante en soltar el puerto: se le da un segundo
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (escucha(port)) {
                System.out.println("[smoke] FALLO: el puerto " + port + " sigue escuchando tras parar");
                fallos++;
            }
        }
        System.out.println(fallos == 0 ? "[smoke] OK" : "[smoke] " + fallos + " fallo(s)");
        System.exit(fallos == 0 ? 0 : 1);
    }

    private static boolean escucha(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
