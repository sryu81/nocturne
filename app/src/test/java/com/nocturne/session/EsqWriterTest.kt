package com.nocturne.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Round-trips [EsqWriter]'s output against the real Ekos `.esq` fixture
 * (`kstars/Tests/ekos/scheduler/9filters.esq`, format 2.6, copied into
 * `src/test/resources/fixtures/`) — confirms the schema EsqWriter emits
 * actually matches what real Ekos writes/reads, not just what the docs claim.
 */
class EsqWriterTest {

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))

    private fun jobElements(doc: Document): List<Element> {
        val nodes = doc.getElementsByTagName("Job")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun directChildText(el: Element, tag: String): String? {
        val nodes = el.getElementsByTagName(tag)
        return if (nodes.length == 0) null else nodes.item(0).textContent
    }

    private fun propertyElement(job: Element, vectorName: String): Element? {
        val vectors = job.getElementsByTagName("PropertyVector")
        return (0 until vectors.length).map { vectors.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == vectorName }
    }

    @Test
    fun `round-trips real 9filters esq fixture field-for-field`() {
        val fixtureXml = javaClass.classLoader!!.getResource("fixtures/9filters.esq")!!.readText()
        val fixtureDoc = parse(fixtureXml)
        val fixtureJobs = jobElements(fixtureDoc)
        assertEquals(9, fixtureJobs.size) // sanity: this really is the 9-filter fixture

        // Build Nocturne Blocks carrying the exact same values the fixture's own Jobs hold.
        val blocks = fixtureJobs.mapIndexed { i, job ->
            val gain = propertyElement(job, "CCD_GAIN")?.getElementsByTagName("OneElement")?.item(0)?.textContent?.toInt() ?: 0
            val offset = propertyElement(job, "CCD_OFFSET")?.getElementsByTagName("OneElement")?.item(0)?.textContent?.toInt() ?: 0
            Block(
                id = "b$i",
                filter = directChildText(job, "Filter")!!,
                exposureSec = directChildText(job, "Exposure")!!.toDouble().toInt(),
                subCount = directChildText(job, "Count")!!.toInt(),
                doneCount = 0,
                gain = gain,
                offset = offset,
                binning = directChildText(job, "X")!!.toInt(),
                ditherEvery = directChildText(job, "GuideDitherPerJob")!!.toInt(),
            )
        }
        val job = SequenceJob(id = "j1", targetId = "t1", blocks = blocks)

        val generated = EsqWriter.write(
            job, targetName = "EasternVeil",
            enforceRefocusEveryN = true, refocusEveryN = 60,
            enforceAutofocusOnTemperature = true, maxFocusTemperatureDelta = 1.0,
        )
        val generatedDoc = parse(generated)
        val generatedJobs = jobElements(generatedDoc)

        assertEquals("2.6", generatedDoc.documentElement.getAttribute("version"))
        assertEquals(fixtureJobs.size, generatedJobs.size)
        fixtureJobs.zip(generatedJobs).forEach { (expected, actual) ->
            assertEquals(directChildText(expected, "Filter"), directChildText(actual, "Filter"))
            assertEquals(directChildText(expected, "Count"), directChildText(actual, "Count"))
            assertEquals(directChildText(expected, "GuideDitherPerJob"), directChildText(actual, "GuideDitherPerJob"))
            assertEquals("Mono", directChildText(actual, "Format"))
            assertEquals("FITS", directChildText(actual, "Encoding"))
            assertEquals("Light", directChildText(actual, "Type"))
            assertEquals("EasternVeil", directChildText(actual, "TargetName"))
        }

        // Job index 3 (H_Alpha) is the fixture's only one with real gain/offset — confirm it round-tripped.
        val haGenerated = generatedJobs[3]
        assertEquals("50", propertyElement(haGenerated, "CCD_GAIN")!!.getElementsByTagName("OneElement").item(0).textContent)
        assertEquals("10", propertyElement(haGenerated, "CCD_OFFSET")!!.getElementsByTagName("OneElement").item(0).textContent)

        assertEquals("60", directChildText(generatedDoc.documentElement, "RefocusEveryN"))
    }

    @Test
    fun `formatDecimal drops trailing zero for whole numbers, keeps fraction otherwise`() {
        val job = SequenceJob(id = "j1", targetId = "t1", blocks = emptyList())

        val whole = EsqWriter.write(job, "T", enforceRefocusEveryN = true, refocusEveryN = 45, enforceAutofocusOnTemperature = true, maxFocusTemperatureDelta = 1.0)
        assertTrue(whole.contains(">1</RefocusOnTemperatureDelta>"))

        // Matches the fixture's own <HFRDeviation>1.12</HFRDeviation> style — a real non-integer value on the wire.
        val fraction = EsqWriter.write(job, "T", enforceRefocusEveryN = true, refocusEveryN = 45, enforceAutofocusOnTemperature = true, maxFocusTemperatureDelta = 1.12)
        assertTrue(fraction.contains(">1.12</RefocusOnTemperatureDelta>"))
    }

    @Test
    fun `xml-escapes filter and target names`() {
        val block = Block(
            id = "b1", filter = "H&Alpha", exposureSec = 60, subCount = 1,
            doneCount = 0, gain = 0, offset = 0, binning = 1, ditherEvery = 0,
        )
        val job = SequenceJob(id = "j1", targetId = "t1", blocks = listOf(block))

        val xml = EsqWriter.write(job, targetName = "M31 <test>", enforceRefocusEveryN = true, refocusEveryN = 45, enforceAutofocusOnTemperature = true, maxFocusTemperatureDelta = 1.0)

        assertTrue(xml.contains("H&amp;Alpha"))
        assertTrue(xml.contains("M31 &lt;test&gt;"))
        parse(xml) // must still be well-formed XML after escaping
    }
}
