package edu.bpgenvm;

import edu.bpgenvm.vm.VirtualMachine;

/**
 * V9 #855 (b) — LAS DEMOS (los Main* de desarrollo) HACEN DE ANFITRIÓN: el inicio y el fin de la ejecución los ponen
 * ellas, no la VM. En la salida estándar de la VM, sólo lo que imprime el programa (Eduardo, 23-nov: «En las VM la salida
 * estándar tal cual … Es el IDE el que puede añadir los mensajes de compilación, el Inicio y Fin de ejecución»). Las
 * pruebas (VmTestSupport) leen lo de entre las dos marcas: lo demás que escribe una demo («[Compilador] …», «Esperado:»)
 * no es del programa.
 */
final class Demos {

    static final String INICIO = "=== INICIANDO EJECUCIÓN DE LA VM ===";
    static final String FIN = "=== FIN DE LA EJECUCIÓN ===";

    private Demos() {}

    /** Ejecuta lo cargado en {@code vm}, entre las dos marcas (con una línea en blanco delante, como antes). */
    static void corre(VirtualMachine vm) {
        System.out.println();
        System.out.println(INICIO);
        vm.run();
        System.out.println(FIN);
    }
}
