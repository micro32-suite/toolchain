package com.mycompany.bpide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * V9 #891 (25-nov) — UN GUION: los comandos del IDE ({@link Comandos}), uno por línea, en un fichero de texto. Idea de
 * Eduardo: <i>«Estoy pensando si con todos los comandos que estamos proporcionando a la IA, se podrían poner en un script,
 * por si queremos automatizar una tarea, ya sea que la ejecute un usuario o una IA.»</i>
 *
 * <p>Una línea: el nombre del comando y sus parámetros, {@code nombre=valor}, separados por espacios; un valor con espacios,
 * entre comillas (una comilla dentro, doblada, como en BASIC); un parámetro de sí o no, a secas ({@code rw}), es true; uno
 * que es una lista, repetido. Una línea que empieza por {@code //}, un comentario; la primera, su descripción. Sin variables,
 * condiciones ni bucles: lo que los necesita es un programa.</p>
 *
 * <p>{@link #lee} lo mira ENTERO antes de que se ejecute nada: un comando que no hay o un parámetro que no vale son un error
 * con su línea. Quien lo ejecuta es {@link Guiones}.</p>
 */
final class Guion {

    /** Un paso: su línea en el fichero, el comando y sus parámetros, ya mirados y en su tipo. */
    static final class Paso {
        final int linea;
        final Comandos.Comando comando;
        final Map<String, Object> parametros;

        Paso(int linea, Comandos.Comando comando, Map<String, Object> parametros) {
            this.linea = linea;
            this.comando = comando;
            this.parametros = parametros;
        }
    }

    /** Lo que no vale de un guion: todos sus errores, cada uno con su línea. */
    static final class Malo extends Exception {
        final List<String> errores;

        Malo(List<String> errores) {
            super(String.join("\n", errores));
            this.errores = errores;
        }
    }

    /** El del fichero, sin «.guion». */
    final String nombre;
    /** Su primera línea de comentario, o "". */
    final String descripcion;
    final List<Paso> pasos;

    private Guion(String nombre, String descripcion, List<Paso> pasos) {
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.pasos = pasos;
    }

    /** Lee y mira un guion. Sus rutas, desde su carpeta; y por `ruta` (la de quien lo lanza; null: la que sea). */
    static Guion lee(Path fichero, Comandos comandos, Function<String, String> ruta) throws IOException, Malo {
        final Path carpeta = fichero.toAbsolutePath().normalize().getParent();
        Function<String, String> desdeAqui = r -> {
            Path a = Paths.get(r);
            String abs = (a.isAbsolute() ? a : carpeta.resolve(a)).toAbsolutePath().normalize().toString();
            return ruta != null ? ruta.apply(abs) : abs;
        };
        List<Paso> pasos = new ArrayList<>();
        List<String> errores = new ArrayList<>();
        int n = 0;
        for (String l : lineas(fichero)) {
            n++;
            l = l.trim();
            if (l.isEmpty() || l.startsWith("//")) continue;
            try {
                Paso paso = paso(n, l, comandos, desdeAqui);
                pasos.add(paso);
            } catch (IllegalArgumentException e) {
                errores.add("línea " + n + ": " + e.getMessage());
            }
        }
        if (pasos.isEmpty() && errores.isEmpty()) errores.add("no tiene ningún comando");
        if (!errores.isEmpty()) throw new Malo(errores);
        return new Guion(nombreDe(fichero), descripcionDe(fichero), pasos);
    }

    /** Una línea, mirada; lanza con el porqué si no vale. */
    private static Paso paso(int n, String linea, Comandos comandos, Function<String, String> ruta) {
        Linea l = parte(linea);
        Comandos.Comando c = comandos.get(l.comando);
        if (c == null) throw new IllegalArgumentException("no hay ningún comando «" + l.comando + "»");
        if (c.id.equals("guion")) throw new IllegalArgumentException("un guion no llama a otro");
        if (c.esSoloVentana()) {
            throw new IllegalArgumentException("«" + c.id + "» abre un diálogo: sólo se usa desde la ventana");
        }
        for (Map.Entry<String, Object> e : l.parametros.entrySet()) {   // el que va a secas (Boolean) sólo vale si es de sí o no
            if (e.getValue() instanceof Boolean && c.parametros().containsKey(e.getKey())
                    && !c.tipoDe(e.getKey()).equals("sino")) {
                throw new IllegalArgumentException("a «" + e.getKey() + "» le falta el valor: " + e.getKey() + "=…");
            }
        }
        String no = Comandos.revisa(c, l.parametros, ruta);
        if (no != null) throw new IllegalArgumentException(no);
        return new Paso(n, c, l.parametros);
    }

    /* ===== la forma de una línea ===== */

    /** Una línea partida: el comando y sus parámetros, en texto (el repetido, una lista; el que va a secas, true). */
    static final class Linea {
        final String comando;
        final Map<String, Object> parametros = new LinkedHashMap<>();

        Linea(String comando) { this.comando = comando; }
    }

    /** Parte una línea; lanza con el porqué si no tiene la forma. */
    static Linea parte(String linea) {
        Linea l = null;
        StringBuilder t = new StringBuilder();   // el trozo, ya sin comillas
        int igual = -1;                          // dónde está su primer «=» fuera de comillas
        boolean hay = false, dentro = false;
        for (int i = 0; i <= linea.length(); i++) {
            char c = i < linea.length() ? linea.charAt(i) : ' ';
            if (dentro) {
                if (i == linea.length()) throw new IllegalArgumentException("faltan las comillas de cierre");
                if (c != '"') { t.append(c); continue; }
                if (i + 1 < linea.length() && linea.charAt(i + 1) == '"') { t.append('"'); i++; } else dentro = false;
            } else if (c == '"') {
                dentro = hay = true;
            } else if (c > ' ') {
                if (c == '=' && igual < 0) igual = t.length();
                t.append(c);
                hay = true;
            } else if (hay) {
                if (l == null) l = new Linea(t.toString());
                else pon(l.parametros, t.toString(), igual);
                t.setLength(0);
                igual = -1;
                hay = false;
            }
        }
        if (l == null) throw new IllegalArgumentException("una línea empieza por el nombre de un comando");
        return l;
    }

    private static void pon(Map<String, Object> p, String trozo, int igual) {
        String clave = igual < 0 ? trozo : trozo.substring(0, igual);
        Object valor = igual < 0 ? Boolean.TRUE : trozo.substring(igual + 1);
        if (clave.isEmpty()) throw new IllegalArgumentException("a «" + trozo + "» le falta el nombre del parámetro");
        Object habia = p.get(clave);
        if (habia == null) {
            p.put(clave, valor);
        } else if (habia instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> lista = (List<Object>) habia;
            lista.add(valor);
        } else {
            List<Object> lista = new ArrayList<>();
            lista.add(habia);
            lista.add(valor);
            p.put(clave, lista);
        }
    }

    /** Un comando y sus parámetros, en la forma de una línea de guion (la consola enseña así lo que hace la IA). */
    static String escribe(String id, Map<String, Object> parametros) {
        StringBuilder s = new StringBuilder(id);
        for (Map.Entry<String, Object> e : parametros.entrySet()) {
            if (e.getKey().equals(Comandos.QUIEN) || e.getValue() == null) continue;
            if (e.getValue() instanceof Collection) {
                for (Object v : (Collection<?>) e.getValue()) s.append(' ').append(e.getKey()).append('=').append(valor(v));
            } else {
                s.append(' ').append(e.getKey()).append('=').append(valor(e.getValue()));
            }
        }
        return s.toString();
    }

    private static String valor(Object v) {
        String t = String.valueOf(v).replace("\r", "").replace('\n', '⏎');
        boolean comillas = t.isEmpty() || t.indexOf('"') >= 0;
        for (int i = 0; i < t.length() && !comillas; i++) comillas = t.charAt(i) <= ' ';
        return comillas ? '"' + t.replace("\"", "\"\"") + '"' : t;
    }

    /* ===== el fichero ===== */

    private static List<String> lineas(Path fichero) throws IOException {
        String t = new String(Files.readAllBytes(fichero), StandardCharsets.UTF_8);
        if (t.startsWith("﻿")) t = t.substring(1);
        List<String> out = new ArrayList<>();
        for (String l : t.split("\\r?\\n|\\r", -1)) out.add(l);
        return out;
    }

    static String nombreDe(Path fichero) {
        String n = fichero.getFileName().toString();
        return n.toLowerCase().endsWith(".guion") ? n.substring(0, n.length() - 6) : n;
    }

    /** La primera línea de un guion, si es un comentario; si no, "". */
    static String descripcionDe(Path fichero) throws IOException {
        for (String l : lineas(fichero)) {
            l = l.trim();
            if (l.startsWith("//")) return l.substring(2).trim();
            if (!l.isEmpty()) return "";
        }
        return "";
    }
}
