package lt.skautai

import lt.skautai.util.LithuanianNameVocativeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class LithuanianNameVocativeFormatterTest {
    @Test
    fun `formats common masculine names`() {
        assertEquals("Augustai", LithuanianNameVocativeFormatter.vocative("Augustas"))
        assertEquals("Jonai", LithuanianNameVocativeFormatter.vocative("Jonas"))
        assertEquals("Mariau", LithuanianNameVocativeFormatter.vocative("Marius"))
        assertEquals("Pauliau", LithuanianNameVocativeFormatter.vocative("Paulius"))
        assertEquals("Andriau", LithuanianNameVocativeFormatter.vocative("Andrius"))
        assertEquals("Tautvydi", LithuanianNameVocativeFormatter.vocative("Tautvydis"))
        assertEquals("Mindaugį", LithuanianNameVocativeFormatter.vocative("Mindaugys"))
    }

    @Test
    fun `formats common feminine names`() {
        assertEquals("Egle", LithuanianNameVocativeFormatter.vocative("Eglė"))
        assertEquals("Dovile", LithuanianNameVocativeFormatter.vocative("Dovilė"))
        assertEquals("Ieva", LithuanianNameVocativeFormatter.vocative("Ieva"))
        assertEquals("Austėja", LithuanianNameVocativeFormatter.vocative("Austėja"))
    }

    @Test
    fun `uses first name and preserves case`() {
        assertEquals("Augustai", LithuanianNameVocativeFormatter.firstNameVocative("Augustas Česnavičius"))
        assertEquals("AUGUSTAI", LithuanianNameVocativeFormatter.vocative("AUGUSTAS"))
    }

    @Test
    fun `uses neutral fallback for blank name`() {
        assertEquals("skaute", LithuanianNameVocativeFormatter.firstNameVocative(" "))
        assertEquals("skaute", LithuanianNameVocativeFormatter.firstNameVocative(null))
    }
}
