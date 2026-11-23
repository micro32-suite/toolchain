// ============================================================
// CapturaPng.java — V9 H1.2 (#853): una captura .shot a PNG, sin Python
// ============================================================
package basicplus.bp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Una captura {@code .shot} (la de {@code Gui.shot}) a PNG: lo de {@code bpgenvm-c/tools/shot2png.py}, en Java, para
 * quien tiene el ZIP y no Python ({@code bp png}). El formato, en {@code docs/SHOT_FORMAT.md}: una cabecera de 16 B y N
 * bloques, todo little-endian, píxel RGB565, cada bloque crudo o en LZ4 de bloque. Hace lo mismo que el Python, con
 * los mismos mensajes y los mismos avisos, y escribe el PNG igual (RGB de 8 bits, filtro 0 en cada fila, zlib 9): sale
 * el mismo fichero, byte a byte.
 */
public final class CapturaPng {

    private CapturaPng() { }

    static final byte[] MAGIC = { 'B', 'P', 'S', 'H' };
    static final int VERSION = 1, FMT_RGB565 = 1, CODEC_CRUDO = 0, CODEC_LZ4 = 1;
    static final int CAB_FICHERO = 16, CAB_BLOQUE = 16;

    /** Un error de formato o de datos: se dice y se sale con 1. */
    static final class ErrorDeCaptura extends Exception {
        ErrorDeCaptura(String m) { super(m); }
    }

    /** Lo leído de un .shot: la imagen en RGB888 (filas seguidas, de arriba abajo) y lo que se cuenta de ella. */
    static final class Captura {
        int w, h, rot, codec, bloques;
        long comprimidos;
        byte[] rgb;
        final List<String> avisos = new ArrayList<>();
    }

    /** {@code bp png entrada.shot [salida.png]}: 0 si convierte, 1 si algo falla (el motivo, en {@code err}). */
    public static int convierte(String entrada, String salida, PrintStream out, PrintStream err) {
        if (salida == null) {
            int punto = entrada.lastIndexOf('.');
            int barra = Math.max(entrada.lastIndexOf('/'), entrada.lastIndexOf('\\'));
            salida = (punto > barra ? entrada.substring(0, punto) : entrada) + ".png";
        }
        Captura c;
        int tam;
        try {
            c = lee(Files.readAllBytes(Paths.get(entrada)));
            byte[] png = png(c.w, c.h, c.rgb);
            Files.write(Paths.get(salida), png);
            tam = png.length;
        } catch (ErrorDeCaptura e) {
            err.println("bp png: " + entrada + ": " + e.getMessage());
            return 1;
        } catch (java.nio.file.NoSuchFileException e) {
            err.println("bp png: no existe " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("bp png: " + e.getMessage());
            return 1;
        }
        long crudo = (long) c.w * c.h * 2;
        double ratio = c.comprimidos > 0 ? (double) crudo / c.comprimidos : 0.0;
        String linea = String.format(Locale.ROOT,
                "%s: %dx%d rot=%d codec=%d (%s) %d bloques, %d B comprimidos de %d B crudos (%.1fx) -> %s (%d B)",
                entrada, c.w, c.h, c.rot, c.codec, c.codec == CODEC_LZ4 ? "LZ4" : "crudo", c.bloques, c.comprimidos,
                crudo, ratio, salida, tam);
        if (c.rot != 0) {
            linea += String.format(Locale.ROOT,
                    " [la cabecera pide rot=%d (%d grados); la imagen es la LOGICA, no se gira]", c.rot, c.rot * 90);
        }
        out.println(linea);
        for (String a : c.avisos) err.println("bp png: aviso: " + a);
        return 0;
    }

    /** Lee un .shot entero. Coloca cada bloque en el rectángulo que dice su cabecera (no supone franjas de ancho
     *  completo ni orden de y) y avisa, sin fallar, de lo que queda sin cubrir o se cubre dos veces. */
    static Captura lee(byte[] datos) throws ErrorDeCaptura {
        if (datos.length < CAB_FICHERO) {
            throw new ErrorDeCaptura("fichero demasiado corto para la cabecera (" + datos.length + " B)");
        }
        ByteBuffer bb = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 4; i++) {
            if (datos[i] != MAGIC[i]) throw new ErrorDeCaptura("magic: no es un .shot (se esperaba BPSH)");
        }
        int version = datos[4] & 0xFF, fmt = datos[5] & 0xFF;
        Captura c = new Captura();
        c.codec = datos[6] & 0xFF;
        c.rot = datos[7] & 0xFF;
        c.w = bb.getShort(8) & 0xFFFF;
        c.h = bb.getShort(10) & 0xFFFF;
        long nbloques = bb.getInt(12) & 0xFFFFFFFFL;
        if (version != VERSION) {
            throw new ErrorDeCaptura("version " + version + " no soportada (esta herramienta lee la " + VERSION + ")");
        }
        if (fmt != FMT_RGB565) throw new ErrorDeCaptura("fmt " + fmt + " no soportado (solo 1 = RGB565)");
        if (c.codec != CODEC_CRUDO && c.codec != CODEC_LZ4) {
            throw new ErrorDeCaptura("codec " + c.codec + " desconocido (0 = crudo, 1 = LZ4)");
        }
        if (c.rot > 3) throw new ErrorDeCaptura("rot " + c.rot + " fuera de rango (0..3)");
        if (c.w == 0 || c.h == 0) throw new ErrorDeCaptura("pantalla de " + c.w + "x" + c.h + ": dimension nula");

        c.rgb = new byte[c.w * c.h * 3];
        byte[] cobertura = new byte[c.w * c.h];   // cuántas veces se pinta cada píxel (hasta 2: basta para avisar)
        int pos = CAB_FICHERO;
        for (long k = 0; k < nbloques; k++) {
            if (pos + CAB_BLOQUE > datos.length) {
                throw new ErrorDeCaptura("bloque " + k + ": fichero truncado en su cabecera (offset " + pos + ")");
            }
            int x1 = bb.getShort(pos) & 0xFFFF, y1 = bb.getShort(pos + 2) & 0xFFFF;
            int x2 = bb.getShort(pos + 4) & 0xFFFF, y2 = bb.getShort(pos + 6) & 0xFFFF;
            long rawLen = bb.getInt(pos + 8) & 0xFFFFFFFFL, compLen = bb.getInt(pos + 12) & 0xFFFFFFFFL;
            pos += CAB_BLOQUE;
            if (x1 > x2 || y1 > y2) {
                throw new ErrorDeCaptura("bloque " + k + ": rectangulo invertido (" + x1 + "," + y1 + ")-(" + x2 + ","
                        + y2 + ")");
            }
            if (x2 >= c.w || y2 >= c.h) {
                throw new ErrorDeCaptura("bloque " + k + ": rectangulo (" + x1 + "," + y1 + ")-(" + x2 + "," + y2
                        + ") fuera de la pantalla " + c.w + "x" + c.h);
            }
            int bw = x2 - x1 + 1, bh = y2 - y1 + 1;
            long esperado = (long) bw * bh * 2;
            if (rawLen != esperado) {
                throw new ErrorDeCaptura("bloque " + k + ": rawLen=" + rawLen + " no cuadra con el rectangulo " + bw
                        + "x" + bh + " (" + esperado + " B)");
            }
            if (pos + compLen > datos.length) {
                throw new ErrorDeCaptura("bloque " + k + ": fichero truncado en los datos (compLen=" + compLen
                        + ", quedan " + (datos.length - pos) + ")");
            }
            byte[] crudo;
            if (c.codec == CODEC_CRUDO) {
                if (compLen != rawLen) {
                    throw new ErrorDeCaptura("bloque " + k + ": codec crudo pero compLen=" + compLen + " != rawLen="
                            + rawLen);
                }
                crudo = new byte[(int) rawLen];
                System.arraycopy(datos, pos, crudo, 0, (int) rawLen);
            } else {
                try {
                    crudo = lz4(datos, pos, (int) compLen, (int) rawLen);
                } catch (ErrorDeCaptura e) {
                    throw new ErrorDeCaptura("bloque " + k + ": " + e.getMessage());
                }
            }
            pos += (int) compLen;
            c.comprimidos += compLen;
            for (int fila = 0; fila < bh; fila++) {
                int y = y1 + fila;
                for (int col = 0; col < bw; col++) {
                    int i = (fila * bw + col) * 2;
                    int v = (crudo[i] & 0xFF) | ((crudo[i + 1] & 0xFF) << 8);
                    int r5 = (v >> 11) & 0x1F, g6 = (v >> 5) & 0x3F, b5 = v & 0x1F;
                    int p = y * c.w + x1 + col;
                    // RGB565 -> RGB888 por REPLICACIÓN DE BITS, como LVGL y el driver del host: 0 -> 0 y 31 -> 255.
                    c.rgb[p * 3]     = (byte) ((r5 << 3) | (r5 >> 2));
                    c.rgb[p * 3 + 1] = (byte) ((g6 << 2) | (g6 >> 4));
                    c.rgb[p * 3 + 2] = (byte) ((b5 << 3) | (b5 >> 2));
                    if (cobertura[p] < 2) cobertura[p]++;
                }
            }
            c.bloques++;
        }
        if (pos != datos.length) c.avisos.add("sobran " + (datos.length - pos) + " B tras el ultimo bloque");
        int sinPintar = 0, repetidos = 0;
        for (byte n : cobertura) {
            if (n == 0) sinPintar++;
            else if (n > 1) repetidos++;
        }
        if (sinPintar > 0) c.avisos.add(sinPintar + " pixeles sin cubrir por ningun bloque (quedan en negro)");
        if (repetidos > 0) c.avisos.add(repetidos + " pixeles cubiertos por mas de un bloque (gana el ultimo)");
        return c;
    }

    /** Un bloque LZ4 estándar (sin frame), a exactamente {@code rawLen} bytes. Secuencia: un token (4 bits de
     *  literales y 4 de match), la extensión de los literales (bytes de 255 y uno menor), los literales, el offset (u16
     *  LE) y la extensión del match; el match mide su nibble + 4. La última secuencia sólo lleva literales. Un match
     *  con un offset menor que su longitud se solapa: se copia byte a byte, que es lo que define el formato. */
    static byte[] lz4(byte[] src, int ini, int len, int rawLen) throws ErrorDeCaptura {
        byte[] dst = new byte[rawLen];
        int n = ini + len, i = ini, o = 0;
        while (i < n) {
            int token = src[i++] & 0xFF;
            int lit = token >>> 4;
            if (lit == 15) {
                int b;
                do {
                    if (i >= n) throw new ErrorDeCaptura("LZ4: bloque truncado leyendo una longitud extendida");
                    b = src[i++] & 0xFF;
                    lit += b;
                } while (b == 255);
            }
            if (i + lit > n) {
                throw new ErrorDeCaptura("LZ4: bloque truncado dentro de los literales (pide " + lit + " B, quedan "
                        + (n - i) + ")");
            }
            if (o + lit > rawLen) throw new ErrorDeCaptura("LZ4: el bloque descomprime a mas de rawLen=" + rawLen + " B");
            System.arraycopy(src, i, dst, o, lit);
            i += lit;
            o += lit;
            if (i == n) break;                 // última secuencia: sólo literales
            if (i + 2 > n) throw new ErrorDeCaptura("LZ4: bloque truncado leyendo el offset");
            int offset = (src[i] & 0xFF) | ((src[i + 1] & 0xFF) << 8);
            i += 2;
            if (offset == 0) throw new ErrorDeCaptura("LZ4: offset 0 (invalido)");
            if (offset > o) {
                throw new ErrorDeCaptura("LZ4: offset " + offset + " apunta antes del principio (hay " + o + " B)");
            }
            int mlen = token & 0x0F;
            if (mlen == 15) {
                int b;
                do {
                    if (i >= n) throw new ErrorDeCaptura("LZ4: bloque truncado leyendo una longitud extendida");
                    b = src[i++] & 0xFF;
                    mlen += b;
                } while (b == 255);
            }
            mlen += 4;
            if (o + mlen > rawLen) throw new ErrorDeCaptura("LZ4: el bloque descomprime a mas de rawLen=" + rawLen + " B");
            for (int j = 0; j < mlen; j++, o++) dst[o] = dst[o - offset];   // byte a byte: así vale el solape
        }
        if (o != rawLen) throw new ErrorDeCaptura("LZ4: descomprimio " + o + " B y el bloque declara rawLen=" + rawLen);
        return dst;
    }

    /** Un PNG mínimo, como el de shot2png.py: RGB de 8 bits, filtro 0 en cada fila, todo en un IDAT con zlib 9. */
    static byte[] png(int w, int h, byte[] rgb) throws IOException {
        int stride = w * 3;
        byte[] filas = new byte[h * (stride + 1)];
        for (int y = 0; y < h; y++) {
            filas[y * (stride + 1)] = 0;                                  // filtro None
            System.arraycopy(rgb, y * stride, filas, y * (stride + 1) + 1, stride);
        }
        Deflater d = new Deflater(9);
        d.setInput(filas);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        while (!d.finished()) z.write(buf, 0, d.deflate(buf));
        d.end();
        ByteBuffer ihdr = ByteBuffer.allocate(13);                        // big-endian, como pide PNG
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
        trozo(out, "IHDR", ihdr.array());
        trozo(out, "IDAT", z.toByteArray());
        trozo(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void trozo(ByteArrayOutputStream out, String tipo, byte[] datos) throws IOException {
        byte[] t = tipo.getBytes("US-ASCII");
        out.write(ByteBuffer.allocate(4).putInt(datos.length).array());
        out.write(t);
        out.write(datos);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(datos);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
