package edu.bpgenvm;

import edu.bpgenvm.bytecode.OpCode;
import edu.bpgenvm.generador.ModWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V8 #768 (3-nov) — el bloque de datos de un módulo, hasta 64 KB (Eduardo: «si utilizas un word pasamos a 64K»).
 * El desplazamiento de un dato (una global, un literal, una constante, el descriptor de una clase) va en los 16 bits
 * de su operando y se lee como un word menos 65 536 (OpCode.desplazamientoDeDato; en la VM-C, bpvm_read_csoff_be):
 * lo de antes (de -32 768 a -1) se lee igual, lo nuevo llega a -65 532 y el 0 sigue siendo el centinela. Y el
 * ModWriter dice, con un error del programa, cuándo un módulo no cabe.
 */
class DatosDe64KTest {

    @Test @DisplayName("el word se lee menos 65 536; lo de antes, igual; el 0, centinela")
    void laRegla() {
        assertEquals(0, OpCode.desplazamientoDeDato(0), "el centinela de CHECKCAST y TRY_BEGIN");
        assertEquals(-1, OpCode.desplazamientoDeDato(0xFFFF));
        assertEquals(-32768, OpCode.desplazamientoDeDato(0x8000), "el tope de antes, igual que con signo");
        assertEquals(-32769, OpCode.desplazamientoDeDato(0x7FFF), "lo primero que antes no se podía");
        assertEquals(-OpCode.DATOS_MAX, OpCode.desplazamientoDeDato(4), "el dato más bajo");
        // el operando llega como lo lee readI16 de la VM-Java: con el signo extendido
        assertEquals(-4, OpCode.desplazamientoDeDato((short) -4));
        // ida y vuelta: lo que escribe el ModWriter (los 16 bits bajos) es lo que se lee
        for (int off = -4; off >= -OpCode.DATOS_MAX; off -= 4) {
            int word = ((short) off) & 0xFFFF;
            assertEquals(off, OpCode.desplazamientoDeDato(word), "desplazamiento " + off);
            if (off >= -32768) assertEquals(off, (short) word, "lo de antes se lee igual que con signo");
        }
    }

    @Test @DisplayName("65 532 bytes de datos caben; uno más, no, y lo dice")
    void elTope() {
        ModWriter w = new ModWriter();
        w.addModulo("Lleno");
        assertEquals(-OpCode.DATOS_MAX, w.addGlobalArray("a", (OpCode.DATOS_MAX - 4) / 4), "justo en el tope");
        ModWriter.DatosLlenos e = assertThrows(ModWriter.DatosLlenos.class, () -> w.addConstantInt("x", 1));
        assertTrue(e.getMessage().contains("el módulo 'Lleno' no cabe"), e.getMessage());
        assertTrue(e.getMessage().contains("pasan de 64 KB, el tope (65532 bytes); ya ocupan 65532 y 'x' (4 bytes) no cabe"),
                e.getMessage());
    }

    @Test @DisplayName("un literal que no cabe se nombra por su principio")
    void unLiteral() {
        ModWriter w = new ModWriter();
        w.addModulo("Texto");
        w.addGlobalArray("a", 10000);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30000; i++) sb.append((char) ('A' + i % 26));
        ModWriter.DatosLlenos e = assertThrows(ModWriter.DatosLlenos.class,
                () -> w.addConstantString("__str_0", sb.toString()));
        assertTrue(e.getMessage().contains("ya ocupan 40004 y el literal \"ABCDEFGHIJKLMNOPQRSTUVWX…\" (30000 bytes) no cabe"),
                e.getMessage());
    }

    // ---- V9 #781 (22-nov): en MOD9, sin tope; lo que no llega en 16 bits va tras WIDE ----

    @Test @DisplayName("V9 #781 — en MOD9 el tope de 64 KB se va: el dato siguiente cabe, a -65 536")
    void sinTopeEnMod9() {
        ModWriter w = new ModWriter();
        w.setFormato(9);
        w.addModulo("Grande");
        assertEquals(-OpCode.DATOS_MAX, w.addGlobalArray("a", (OpCode.DATOS_MAX - 4) / 4), "justo en el tope de 16 bits");
        assertEquals(-OpCode.DATOS_MAX - 4, w.addConstantInt("x", 1), "y el siguiente, más allá");
        assertFalse(OpCode.cabeEn16(-OpCode.DATOS_MAX - 4), "que va tras WIDE");
    }

    @Test @DisplayName("V9 #781 — qué llega en 16 bits: de -65 535 a -1, y el 0 del centinela")
    void loQueCabe() {
        assertTrue(OpCode.cabeEn16(0), "el centinela");
        assertTrue(OpCode.cabeEn16(-1));
        assertTrue(OpCode.cabeEn16(-OpCode.DATOS_MAX));
        assertTrue(OpCode.cabeEn16(-0xFFFF), "el último que llega");
        assertFalse(OpCode.cabeEn16(-0x10000), "su word sería el 0 del centinela");
        assertFalse(OpCode.cabeEn16(-100000));
        assertFalse(OpCode.cabeEn16(4), "un dato nunca está por encima del CS");
    }

    @Test @DisplayName("V9 #781 — WIDE va delante de las 19 que llevan un dato, y de ninguna más")
    void lasDeDato() {
        OpCode[] conDato = { OpCode.GET_GLOBAL, OpCode.SET_GLOBAL, OpCode.LEA_GLOBAL, OpCode.GET_GLOBAL_I8,
                OpCode.GET_GLOBAL_U8, OpCode.GET_GLOBAL_I16, OpCode.GET_GLOBAL_U16, OpCode.SET_GLOBAL_I8,
                OpCode.SET_GLOBAL_I16, OpCode.GET_GLOBAL_L, OpCode.SET_GLOBAL_L, OpCode.NEW_OBJECT, OpCode.INSTANCEOF,
                OpCode.TRY_BEGIN, OpCode.CHECKCAST, OpCode.CHECKCAST_EXT, OpCode.CHECKCAST_REF, OpCode.CHECKCAST_IFAZ_REF,
                OpCode.LEA_STR };
        int n = 0;
        for (OpCode op : OpCode.values()) {
            boolean esperado = java.util.Arrays.asList(conDato).contains(op);
            assertEquals(esperado, OpCode.llevaDato(op.code), op.name());
            if (esperado) n++;
        }
        assertEquals(19, n);
        assertFalse(OpCode.llevaDato(OpCode.GET_GLOBAL_S8.code), "la compacta ya llega con su byte");
        assertFalse(OpCode.llevaDato(OpCode.GET_LOCAL.code), "un local no es un dato del módulo");
        assertFalse(OpCode.llevaDato(OpCode.WIDE.code), "WIDE no va delante de WIDE");
        // y el operando ancho: cada dato, de 2 a 4 bytes; lo demás, igual
        assertEquals(4, OpCode.bytesAnchos(OpCode.GET_GLOBAL));
        assertEquals(4, OpCode.bytesAnchos(OpCode.LEA_STR));
        assertEquals(8, OpCode.bytesAnchos(OpCode.TRY_BEGIN), "el handler (i32) y la clase");
        assertEquals(8, OpCode.bytesAnchos(OpCode.CHECKCAST), "la clase y el nombre");
        assertEquals(8, OpCode.bytesAnchos(OpCode.CHECKCAST_EXT), "la clase (i32, parcheada) y el nombre");
        assertEquals(6, OpCode.bytesAnchos(OpCode.CHECKCAST_REF), "el idx de la ref y el nombre");
        assertThrows(IllegalArgumentException.class, () -> OpCode.bytesAnchos(OpCode.GET_LOCAL));
    }
}
