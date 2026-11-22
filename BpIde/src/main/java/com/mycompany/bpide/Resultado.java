package com.mycompany.bpide;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V9 H2.2 (22-nov) — lo que devuelve un comando ({@link Comandos}): si fue bien, su mensaje y sus datos (los errores de
 * la compilación, la salida, el código de salida del programa…). La ventana lo enseña como siempre; la IA (H2.3) lo
 * recibe tal cual, en datos, no en una captura.
 *
 * <p>Lo que antes era un diálogo dentro de la acción («Editor vacío», «Ya hay un programa en marcha», «No se pudo
 * guardar») es ahora un resultado {@link #aviso}: la ventana abre el mismo diálogo, y a la IA no la deja esperando.</p>
 */
public final class Resultado {

    /** Si el comando hizo lo que se le pedía. */
    public final boolean ok;
    /** Lo que pasó, en una línea (o en varias, como el diálogo de antes). */
    public final String mensaje;
    /** Lo suyo, en datos, por nombre. */
    public final Map<String, Object> datos = new LinkedHashMap<>();

    /** Si la ventana lo enseña en un diálogo, su título; null, sin diálogo (lo dice ya la consola). */
    final String tituloDialogo;
    /** El tipo de ese diálogo (JOptionPane.INFORMATION_MESSAGE, WARNING_MESSAGE, ERROR_MESSAGE). */
    final int tipoDialogo;

    private Resultado(boolean ok, String mensaje, String tituloDialogo, int tipoDialogo) {
        this.ok = ok;
        this.mensaje = mensaje;
        this.tituloDialogo = tituloDialogo;
        this.tipoDialogo = tipoDialogo;
    }

    /** Fue bien. */
    public static Resultado bien(String mensaje) {
        return new Resultado(true, mensaje, null, 0);
    }

    /** No fue bien, y la ventana no abre diálogo: lo dice ya la consola (una compilación con errores, por ejemplo). */
    public static Resultado mal(String mensaje) {
        return new Resultado(false, mensaje, null, 0);
    }

    /** No fue bien, y la ventana lo enseña en un diálogo, como antes de los comandos. */
    public static Resultado aviso(String titulo, String mensaje, int tipo) {
        return new Resultado(false, mensaje, titulo, tipo);
    }

    /** Añade un dato y devuelve el mismo resultado (se llena donde se crea). */
    public Resultado con(String clave, Object valor) {
        datos.put(clave, valor);
        return this;
    }

    /** Los datos, para leerlos. */
    public Map<String, Object> datos() {
        return Collections.unmodifiableMap(datos);
    }

    @Override
    public String toString() {
        return (ok ? "bien" : "mal") + ": " + mensaje + (datos.isEmpty() ? "" : " " + datos);
    }
}
