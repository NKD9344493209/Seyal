package com.seyal.app.util

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes real PowerPoint (.pptx) and Word (.docx) files with nothing but the zip writer every
 * phone has: both formats are zip files of XML. The look is fixed and clean: a dark title slide,
 * white content slides with a green accent, at most seven bullets a slide.
 */
object Office {
    /** One slide or one section of a document. */
    class Block(val heading: String, val bullets: List<String> = emptyList(), val text: String = "")

    private const val NAVY = "1F2A44"
    private const val GREEN = "3DB68A"
    private const val INK = "2B2B2B"
    private const val MUTED = "6B7280"

    private fun esc(s: String): String = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;"); ch == '<' -> append("&lt;"); ch == '>' -> append("&gt;"); ch == '"' -> append("&quot;")
            // Control characters are not allowed in XML.
            ch < ' ' && ch != '\n' && ch != '\t' -> {}
            else -> append(ch)
        }
    }

    private fun zip(files: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry() } }
        return out.toByteArray()
    }

    private const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/package/2006/relationships"

    // ================================================================ PowerPoint

    /** Slides as they will be written: long bullet lists continue on a following slide. */
    internal fun paginate(blocks: List<Block>, perSlide: Int = 7): List<Block> = blocks.flatMap { b ->
        val items = b.bullets.map { it.trim() }.filter { it.isNotBlank() } + b.text.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (items.size <= perSlide) listOf(Block(b.heading, items))
        else {
            // Spread evenly (9 → 5 + 4), not a full slide and a nearly empty one.
            val parts = (items.size + perSlide - 1) / perSlide
            items.chunked((items.size + parts - 1) / parts).mapIndexed { i, part -> Block(if (i == 0) b.heading else "${b.heading} (cont.)", part) }
        }
    }

    private fun rect(id: Int, x: Long, y: Long, cx: Long, cy: Long, fill: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Shape $id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:solidFill><a:srgbClr val=\"$fill\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>"

    private class Para(val text: String, val size: Int, val color: String, val bold: Boolean = false, val bullet: Boolean = false, val align: String = "l")

    private fun textBox(id: Int, x: Long, y: Long, cx: Long, cy: Long, paras: List<Para>, anchor: String = "t", shrink: Boolean = false): String {
        val body = paras.joinToString("") { p ->
            val pPr = if (p.bullet) "<a:pPr marL=\"457200\" indent=\"-457200\" algn=\"${p.align}\"><a:spcBef><a:spcPts val=\"${if (p.size >= 28) 1600 else 1100}\"/></a:spcBef><a:buClr><a:srgbClr val=\"$GREEN\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/></a:pPr>"
            else "<a:pPr algn=\"${p.align}\"><a:buNone/></a:pPr>"
            "<a:p>$pPr<a:r><a:rPr lang=\"en-US\" sz=\"${p.size * 100}\" b=\"${if (p.bold) 1 else 0}\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"${p.color}\"/></a:solidFill><a:latin typeface=\"Calibri\"/><a:cs typeface=\"Calibri\"/></a:rPr><a:t>${esc(p.text)}</a:t></a:r></a:p>"
        }.ifEmpty { "<a:p><a:endParaRPr lang=\"en-US\"/></a:p>" }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Text $id\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"$anchor\">${if (shrink) "<a:normAutofit/>" else "<a:noAutofit/>"}</a:bodyPr><a:lstStyle/>$body</p:txBody></p:sp>"
    }

    private fun slide(background: String, shapes: String) =
        HEAD + "<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$background\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            shapes + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"

    /** Font size for a slide's bullets: smaller when there is more to fit. */
    internal fun bulletSize(items: List<String>): Int {
        val chars = items.sumOf { it.length }
        return when { items.size <= 4 && chars < 280 -> 32; items.size <= 6 && chars < 440 -> 28; chars < 600 -> 24; chars < 800 -> 20; else -> 18 }
    }

    /**
     * A 16:9 deck: a title slide, then one slide per block (long lists continue on the next slide).
     * [byline] is the small line on the title slide (author, date).
     */
    fun pptx(title: String, subtitle: String, byline: String, blocks: List<Block>): ByteArray {
        val w = 12_192_000L; val h = 6_858_000L
        val pages = paginate(blocks)
        val slides = mutableListOf<String>()
        // Title slide
        slides += slide(
            NAVY,
            rect(2, 0, 0, 228_600, h, GREEN) +
                textBox(3, 914_400, 2_057_400, w - 1_828_800, 1_600_200, listOf(Para(title.ifBlank { "Presentation" }, if (title.length > 60) 36 else 44, "FFFFFF", bold = true)), anchor = "b", shrink = true) +
                rect(4, 914_400, 3_810_000, 1_371_600, 45_720, GREEN) +
                textBox(5, 914_400, 3_962_400, w - 1_828_800, 914_400, listOfNotNull(subtitle.takeIf { it.isNotBlank() }?.let { Para(it, 22, "C9D3E6") }), shrink = true) +
                textBox(6, 914_400, 5_715_000, w - 1_828_800, 381_000, listOfNotNull(byline.takeIf { it.isNotBlank() }?.let { Para(it, 14, "8FA0BF") })),
        )
        pages.forEachIndexed { i, b ->
            val size = bulletSize(b.bullets)
            slides += slide(
                "FFFFFF",
                rect(2, 0, 0, w, 114_300, GREEN) +
                    textBox(3, 685_800, 457_200, w - 1_371_600, 914_400, listOf(Para(b.heading.ifBlank { title }, if (b.heading.length > 44) 30 else 36, NAVY, bold = true)), anchor = "ctr", shrink = true) +
                    rect(4, 685_800, 1_447_800, 914_400, 38_100, GREEN) +
                    textBox(5, 685_800, 1_752_600, w - 1_371_600, 4_267_200, b.bullets.map { Para(it, size, INK, bullet = true) }, shrink = b.bullets.sumOf { it.length } > 420) +
                    textBox(6, w - 1_828_800, 6_324_600, 1_143_000, 304_800, listOf(Para("${i + 2}", 12, MUTED, align = "r"))),
            )
        }

        val files = mutableListOf<Pair<String, String>>()
        files += "[Content_Types].xml" to HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            slides.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>"
        files += "_rels/.rels" to HEAD + "<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>"
        files += "docProps/core.xml" to core(title)
        files += "ppt/presentation.xml" to HEAD + "<p:presentation xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>" + slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 3}\"/>" } + "</p:sldIdLst>" +
            "<p:sldSz cx=\"$w\" cy=\"$h\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>"
        files += "ppt/_rels/presentation.xml.rels" to HEAD + "<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$NS_R/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$NS_R/theme\" Target=\"theme/theme1.xml\"/>" +
            slides.indices.joinToString("") { "<Relationship Id=\"rId${it + 3}\" Type=\"$NS_R/slide\" Target=\"slides/slide${it + 1}.xml\"/>" } + "</Relationships>"
        val emptyTree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>"
        files += "ppt/slideMasters/slideMaster1.xml" to HEAD + "<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">$emptyTree" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>"
        files += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to HEAD + "<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$NS_R/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$NS_R/theme\" Target=\"../theme/theme1.xml\"/></Relationships>"
        files += "ppt/slideLayouts/slideLayout1.xml" to HEAD + "<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"blank\" preserve=\"1\">" +
            emptyTree.replace("<p:cSld>", "<p:cSld name=\"Blank\">") + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
        files += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to HEAD + "<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$NS_R/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>"
        files += "ppt/theme/theme1.xml" to theme()
        slides.forEachIndexed { i, xml ->
            files += "ppt/slides/slide${i + 1}.xml" to xml
            files += "ppt/slides/_rels/slide${i + 1}.xml.rels" to HEAD + "<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$NS_R/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/></Relationships>"
        }
        return zip(files)
    }

    private fun core(title: String) = HEAD + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>${esc(title)}</dc:title><dc:creator>Seyal</dc:creator></cp:coreProperties>"

    private fun theme(): String {
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        val line = "<a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>"
        val effect = "<a:effectStyle><a:effectLst/></a:effectStyle>"
        fun c(name: String, hex: String) = "<a:$name><a:srgbClr val=\"$hex\"/></a:$name>"
        return HEAD + "<a:theme xmlns:a=\"$NS_A\" name=\"Seyal\"><a:themeElements><a:clrScheme name=\"Seyal\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            c("dk2", NAVY) + c("lt2", "EEF1F6") + c("accent1", GREEN) + c("accent2", "2F6FED") + c("accent3", "F2A93B") + c("accent4", "E5484D") + c("accent5", "7C5CFC") + c("accent6", "14B8A6") +
            c("hlink", "2F6FED") + c("folHlink", "7C5CFC") + "</a:clrScheme>" +
            "<a:fontScheme name=\"Seyal\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"Seyal\"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst><a:lnStyleLst>$line$line$line</a:lnStyleLst><a:effectStyleLst>$effect$effect$effect</a:effectStyleLst><a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements></a:theme>"
    }

    // ================================================================ Word

    private fun run(text: String, size: Int, bold: Boolean = false, color: String = INK) =
        "<w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>${if (bold) "<w:b/>" else ""}<w:color w:val=\"$color\"/><w:sz w:val=\"${size * 2}\"/><w:szCs w:val=\"${size * 2}\"/></w:rPr><w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r>"

    private fun para(runs: String, before: Int = 0, after: Int = 120, indent: Int = 0, hanging: Int = 0, keepNext: Boolean = false) =
        "<w:p><w:pPr>${if (keepNext) "<w:keepNext/>" else ""}<w:spacing w:before=\"$before\" w:after=\"$after\" w:line=\"288\" w:lineRule=\"auto\"/>${if (indent > 0) "<w:ind w:left=\"$indent\" w:hanging=\"$hanging\"/>" else ""}</w:pPr>$runs</w:p>"

    /** An A4 document: a title, an optional line under it, then a heading and text or bullets per block. */
    fun docx(title: String, subtitle: String, blocks: List<Block>): ByteArray {
        val body = StringBuilder()
        if (title.isNotBlank()) body.append(para(run(title, 26, bold = true, color = NAVY), after = 60))
        if (subtitle.isNotBlank()) body.append(para(run(subtitle, 12, color = MUTED), after = 240))
        for (b in blocks) {
            if (b.heading.isNotBlank()) body.append(para(run(b.heading, 15, bold = true, color = NAVY), before = 240, after = 100, keepNext = true))
            b.text.split('\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { body.append(para(run(it, 11))) }
            b.bullets.map { it.trim() }.filter { it.isNotBlank() }.forEach { body.append(para(run("•\t", 11, color = GREEN) + run(it, 11), after = 60, indent = 360, hanging = 360)) }
        }
        val doc = HEAD + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body" +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>"
        return zip(
            listOf(
                "[Content_Types].xml" to HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>",
                "_rels/.rels" to HEAD + "<Relationships xmlns=\"$REL\"><Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"word/document.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>",
                "docProps/core.xml" to core(title),
                "word/document.xml" to doc,
            ),
        )
    }

    /** A safe file name from a title: "Q3 plan: v2" → "Q3 plan v2". */
    fun fileName(title: String, ext: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]+"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "Document" } + "." + ext
}
