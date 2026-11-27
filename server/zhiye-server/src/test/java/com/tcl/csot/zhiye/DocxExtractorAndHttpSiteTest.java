package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.chat.AskAttachments;
import com.tcl.csot.zhiye.service.chat.DocxExtractor;
import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.service.task.Prompts;
import com.tcl.csot.zhiye.service.task.TaskExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocxExtractorAndHttpSiteTest {

    /** 1x1 透明 PNG 合法字节 */
    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    void extractsTextTablesAndEmbeddedImagesEvenAfter230EntriesAndBackslashes(@TempDir Path tempDir) throws Exception {
        byte[] docx = buildRichDocx(true, true);

        DocxExtractor.Result chatRes = DocxExtractor.extract(docx, null);
        assertThat(chatRes.legacyBinaryDoc()).isFalse();
        assertThat(chatRes.markdown()).contains("# 产品介绍与操作手册");
        assertThat(chatRes.markdown()).contains("正文第一段：支持图文混排。");
        assertThat(chatRes.markdown()).contains("[内嵌图片 1：image1.png]");
        assertThat(chatRes.markdown()).contains("| 指标 | 数值 |");
        assertThat(chatRes.markdown()).contains("| 良率 | 99.6% |");
        assertThat(chatRes.markdown()).contains("[内嵌图片 2：image2.png]");
        // mc:Fallback 重复节点不应产生重复文字
        assertThat(chatRes.markdown().indexOf("不该重复的文本框")).isEqualTo(chatRes.markdown().lastIndexOf("不该重复的文本框"));
        assertThat(chatRes.images()).hasSize(2);
        assertThat(chatRes.images().get(0).filename()).isEqualTo("image1.png");
        assertThat(chatRes.images().get(0).bytes()).isEqualTo(PNG_1X1);
        assertThat(chatRes.images().get(1).filename()).isEqualTo("image2.png");

        // AskAttachments.extract 也能正常提取且不返回 null
        String plain = AskAttachments.extract("操作手册.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);
        assertThat(plain).contains("含 2 张内嵌图片").contains("产品介绍与操作手册").contains("99.6%");

        // TaskExecutor.unpackDocumentAsset 自动解包 *-parsed.md 和 *-media/ 原图到工作区
        TaskExecutor.unpackDocumentAsset(tempDir, "public/assets/", "操作手册.docx", "操作手册.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx, false);
        Path parsedMd = tempDir.resolve("public/assets/操作手册-parsed.md");
        Path img1 = tempDir.resolve("public/assets/操作手册-media/image1.png");
        Path img2 = tempDir.resolve("public/assets/操作手册-media/image2.png");
        assertThat(parsedMd).exists();
        assertThat(img1).exists().hasBinaryContent(PNG_1X1);
        assertThat(img2).exists().hasBinaryContent(PNG_1X1);
        String mdText = Files.readString(parsedMd, StandardCharsets.UTF_8);
        assertThat(mdText).contains("![图片 1](./assets/操作手册-media/image1.png)");
        assertThat(mdText).contains("![图片 2](./assets/操作手册-media/image2.png)");

        String promptSec = Prompts.formatAssetSection("## 用户上传的素材（已放进项目）", List.of(
                "public/assets/操作手册.docx",
                "public/assets/操作手册-parsed.md",
                "public/assets/操作手册-media/image1.png"
        ), false);
        assertThat(promptSec).contains("Word/PDF/PPT 文档与图片专项要求").contains("*-parsed.md");
    }

    @Test
    void detectsLegacyOle2DocAndHtmlSavedAsDocx() throws Exception {
        byte[] ole2 = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0};
        DocxExtractor.Result legacy = DocxExtractor.extract(ole2, null);
        assertThat(legacy.legacyBinaryDoc()).isTrue();
        assertThat(legacy.notice()).contains("旧版二进制 .doc").contains("另存为");

        byte[] htmlDoc = "<html><body><h1>导出报告</h1><p>内容正文</p></body></html>".getBytes(StandardCharsets.UTF_8);
        DocxExtractor.Result htmlRes = DocxExtractor.extract(htmlDoc, null);
        assertThat(htmlRes.legacyBinaryDoc()).isFalse();
        assertThat(htmlRes.markdown()).contains("导出报告").contains("内容正文");
    }

    @Test
    void siteCookieRespectsHttpSiteSchemeEvenWhenMainSsoIsSecure() {
        AuthProperties.Sso ssoHttps = new AuthProperties.Sso(
                "https://sso.example.com", "/oauth/csot/login", "SSO-METIS", "https://zhiye.csot.tcl.com/access_token",
                null, 10, "zhiye_session", 7, true);
        AuthProperties authProps = new AuthProperties("sso", false, ssoHttps);

        ZhiyeProperties httpSites = new ZhiyeProperties(
                null, null, "https://zhiye.csot.tcl.com",
                new ZhiyeProperties.Site("http://preview.zhiye.csot.tcl.com", "http", "zhiye.csot.tcl.com", null, null),
                null, null, null, null, null, null, null, null, null, null, null);
        AccessService httpAccess = new AccessService(null, null, authProps, httpSites);
        String httpCookie = httpAccess.siteCookie("tok123", Duration.ofDays(7));
        assertThat(httpCookie).contains("zhiye_site=tok123").contains("HttpOnly").doesNotContain("Secure");

        ZhiyeProperties httpsSites = new ZhiyeProperties(
                null, null, "https://zhiye.csot.tcl.com",
                new ZhiyeProperties.Site("https://preview.zhiye.csot.tcl.com", "https", "zhiye.csot.tcl.com", null, null),
                null, null, null, null, null, null, null, null, null, null, null);
        AccessService httpsAccess = new AccessService(null, null, authProps, httpsSites);
        String httpsCookie = httpsAccess.siteCookie("tok123", Duration.ofDays(7));
        assertThat(httpsCookie).contains("zhiye_site=tok123").contains("HttpOnly").contains("Secure");
    }

    private static byte[] buildRichDocx(boolean withManyPaddingEntries, boolean useBackslash) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            if (withManyPaddingEntries) {
                for (int i = 0; i < 230; i++) {
                    zos.putNextEntry(new ZipEntry("word/theme/dummy_" + i + ".bin"));
                    zos.write(("bin" + i).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            String sep = useBackslash ? "\\" : "/";
            String rels = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image2.png"/>
                    </Relationships>
                    """;
            zos.putNextEntry(new ZipEntry("word" + sep + "_rels" + sep + "document.xml.rels"));
            zos.write(rels.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            String docXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                                xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
                      <w:body>
                        <w:p>
                          <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                          <w:r><w:t>产品介绍与操作手册</w:t></w:r>
                        </w:p>
                        <w:p>
                          <w:r><w:t>正文第一段：支持图文混排。</w:t></w:r>
                        </w:p>
                        <w:p>
                          <w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r>
                        </w:p>
                        <w:p>
                          <mc:AlternateContent>
                            <mc:Choice><w:r><w:txbxContent><w:p><w:r><w:t>不该重复的文本框</w:t></w:r></w:p></w:txbxContent></w:r></mc:Choice>
                            <mc:Fallback><w:r><w:t>不该重复的文本框</w:t></w:r></mc:Fallback>
                          </mc:AlternateContent>
                        </w:p>
                        <w:tbl>
                          <w:tr>
                            <w:tc><w:p><w:r><w:t>指标</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>数值</w:t></w:r></w:p></w:tc>
                          </w:tr>
                          <w:tr>
                            <w:tc><w:p><w:r><w:t>良率</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>99.6%</w:t></w:r></w:p></w:tc>
                          </w:tr>
                        </w:tbl>
                        <w:p>
                          <w:r><w:drawing><a:blip r:embed="rId2"/></w:drawing></w:r>
                        </w:p>
                      </w:body>
                    </w:document>
                    """;
            zos.putNextEntry(new ZipEntry("word" + sep + "document.xml"));
            zos.write(docXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word" + sep + "media" + sep + "image1.png"));
            zos.write(PNG_1X1);
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("word" + sep + "media" + sep + "image2.png"));
            zos.write(PNG_1X1);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
