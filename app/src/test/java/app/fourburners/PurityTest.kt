package app.fourburners

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class PurityTest {
    @Test fun `pure packages have no android imports`() {
        val roots = listOf("model", "parse", "engine", "recipe", "format")
            .map { File("src/main/java/app/fourburners/$it") }.filter { it.exists() }
        val bad = roots.flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { f -> f.readLines().any { l -> l.startsWith("import android") } }
        assertEquals(emptyList<File>(), bad)
    }
}
