package com.tcl.csot.zhiye.service.chat;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 问答附件的文本提取：Word / Excel / PPT / PDF / 纯文本。 */
class AskAttachmentsTest {

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    void extractsOfficeAndText() throws Exception {
        byte[] docx = zip(Map.of("word/document.xml",
                "<w:document><w:body><w:p><w:r><w:t>第一章 概述</w:t></w:r></w:p><w:p><w:r><w:t>这是 &lt;设计&gt; 说明</w:t></w:r></w:p></w:body></w:document>"));
        String t = AskAttachments.extract("设计.docx", "application/octet-stream", docx);
        assertThat(t).contains("第一章 概述").contains("这是 <设计> 说明");

        byte[] xlsx = zip(Map.of(
                "xl/sharedStrings.xml", "<sst><si><t>姓名</t></si><si><t>产量</t></si><si><t>张三</t></si></sst>",
                "xl/worksheets/sheet1.xml", "<sheetData><row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row><row><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>1280</v></c></row></sheetData>"));
        String x = AskAttachments.extract("产量.xlsx", "application/octet-stream", xlsx);
        assertThat(x).contains("姓名\t产量").contains("张三\t1280");

        byte[] pptx = zip(Map.of(
                "ppt/slides/slide2.xml", "<p:sld><a:p><a:r><a:t>第二页内容</a:t></a:r></a:p></p:sld>",
                "ppt/slides/slide1.xml", "<p:sld><a:p><a:r><a:t>标题页</a:t></a:r></a:p></p:sld>"));
        String p = AskAttachments.extract("汇报.pptx", "application/octet-stream", pptx);
        assertThat(p).contains("标题页").contains("第二页内容");
        assertThat(p.indexOf("标题页")).isLessThan(p.indexOf("第二页内容"));

        byte[] pdf = pdf("hello zhiye attachment");
        assertThat(AskAttachments.extract("说明书.pdf", "application/pdf", pdf)).contains("hello zhiye attachment");

        assertThat(AskAttachments.extract("note.md", "text/markdown", "# 标题\n正文".getBytes(StandardCharsets.UTF_8))).contains("# 标题");
        // 读不了的格式返回 null，由调用方决定提示语
        assertThat(AskAttachments.extract("a.zip", "application/zip", "x".getBytes(StandardCharsets.UTF_8))).isNull();
    }
}
