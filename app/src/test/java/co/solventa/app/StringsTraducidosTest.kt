package co.solventa.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Cada texto traducible del español (por defecto) existe también en inglés, y al revés. */
class StringsTraducidosTest {

    private fun claves(ruta: String): Set<String> {
        val documento = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(ruta))
        val nodos = documento.getElementsByTagName("string")
        return (0 until nodos.length)
            .map { nodos.item(it).attributes }
            .filter { it.getNamedItem("translatable")?.nodeValue != "false" }
            .map { it.getNamedItem("name").nodeValue }
            .toSet()
    }

    @Test
    fun `español e inglés tienen los mismos textos`() {
        val espanol = claves("src/main/res/values/strings.xml")
        val ingles = claves("src/main/res/values-en/strings.xml")

        assertEquals("faltan en inglés", emptySet<String>(), espanol - ingles)
        assertEquals("sobran en inglés", emptySet<String>(), ingles - espanol)
    }
}
