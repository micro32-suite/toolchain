package com.mycompany.bpide;

/**
 * Diagnóstico inmutable producido por el frontend o la VM. Se almacena en
 * la {@link ObservableList} de errores que la pestaña "Errores" muestra.
 * Cada instancia corresponde a una línea matcheada por el regex
 * {@code <fichero>:LINE:COL: (error|aviso) <categoría>: <mensaje>} (V9 #856: con el fichero de CADA error; antes,
 * {@code [LINE:COL] …}, sin él, y el IDE los ponía todos en la pestaña activa).
 */
public final class CompileError {
    public final String  file;        // basename del .bp ("Json.bp")
    public final java.nio.file.Path ruta;   // V9 #856: la ruta entera del fuente del error (null si no se sabe)
    public final int     line;        // 1-based
    public final int     column;      // 1-based
    public final String  kind;        // "error" o "aviso"
    public final String  category;    // "sintáctico" / "semántico" / ...
    public final String  message;     // texto sin prefijo

    public CompileError(String file, java.nio.file.Path ruta, int line, int column,
                        String kind, String category, String message) {
        this.file     = file;
        this.ruta     = ruta;
        this.line     = line;
        this.column   = column;
        this.kind     = kind;
        this.category = category;
        this.message  = message;
    }

    @Override public String toString() {
        return file + ":" + line + ":" + column + " " + kind + " " + category + ": " + message;
    }
}
