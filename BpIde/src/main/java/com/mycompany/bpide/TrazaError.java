// ============================================================
// TrazaError.java
// V8 #582 — DÓNDE falló un programa, con sus nombres.
//
// La placa no tiene el .dbg: en el `stackTrace` del EXITED da posiciones
// («Prueba+50 Prueba+72 Core+1200», de dentro afuera) y el PC pone los nombres,
// como en el depurador. Cada posición es la instrucción SIGUIENTE a la que falló
// o llamó, así que la línea es la de la posición menos uno. Lo que cae en la línea
// 0 (el __init del módulo) no es del programa, y no se cuenta.
//
// Las carpetas donde mirar los .dbg las va diciendo el IDE al compilar (conoce);
// la stdlib se busca sola. Un módulo sin .dbg sale tal cual («Core+1200»): se
// pierde la línea, no el dato. El espejo en Python, tools/traza_dbg.py.
// V9 #855 (c): la traducción, en edu.bpgenvm.vm.debug.Traza (la VM Java y `bp`
// la usan también), con la función; aquí, lo del IDE: las carpetas y la stdlib.
// ============================================================
package com.mycompany.bpide;

import edu.bpgenvm.vm.debug.Traza;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class TrazaError {
    private static final List<Path> carpetas = new CopyOnWriteArrayList<>();

    private TrazaError() {}

    /** Una carpeta con los .dbg de lo que se va a ejecutar: la última que se dice, la primera que se mira. */
    static void conoce(Path carpeta) {
        if (carpeta == null) return;
        carpetas.remove(carpeta);
        carpetas.add(0, carpeta);
    }

    /** La traza del EXITED, marco a marco: «Prueba.bp:23», o «Mod+rel» si no hay .dbg de ese módulo. */
    static List<String> traduce(String traza) {
        List<Path> donde = new ArrayList<>(carpetas);
        Path stdlib = IdePrefs.installSubdir("bpstdlib");
        if (stdlib != null) donde.add(stdlib);
        return traduce(traza, donde);
    }

    /** V9 #855 (c) — el traductor, uno (Eduardo: «Un traductor, en Java»), el de la VM Java, que también usan ella misma
     *  y `bp`; ahora con la función: «Prueba.bp:23 (Main)». */
    static List<String> traduce(String traza, List<Path> donde) {
        return Traza.marcos(traza, donde);
    }

    /** V8 #788 — las líneas de la consola: «  en X» el primero y «  llamado desde Y» los demás. Las mismas en el
     *  camino de la placa (AbstractBpvmBackend.run) y en el de la VM local (TrazaDeLaVmLocal), y en la VM Java y `bp`. */
    static List<String> lineas(List<String> donde) {
        return Traza.lineas(donde);
    }
}
