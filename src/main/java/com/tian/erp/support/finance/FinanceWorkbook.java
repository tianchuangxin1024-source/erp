package com.tian.erp.support.finance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A single-sheet workbook with typed values and no executable formulas. */
public final class FinanceWorkbook {
    /**
     * 工具类构造器，禁止实例化。
     */
    private FinanceWorkbook() {}

    /**
     * 写入工作簿及工作表 XML，返回压缩后的 XLSX 字节。
     */
    public static byte[] encode(List<Map<String, Object>> rows) {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                    """);
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """);
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                    """);
            var xml = new StringBuilder("""
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                    """);
            if (!rows.isEmpty()) {
                var columns = new ArrayList<>(rows.get(0).keySet());
                row(xml, 1, columns);
                for (int i = 0; i < rows.size(); i++) {
                    var data = rows.get(i);
                    row(xml, i + 2, columns.stream().map(data::get).toList());
                }
            }
            xml.append("</sheetData></worksheet>");
            entry(zip, "xl/worksheets/sheet1.xml", xml.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return output.toByteArray();
    }

    /**
     * 写入单行单元格，超过 Excel 数值有效精度的值按文本保存。
     */
    private static void row(StringBuilder xml, int number, List<?> values) {
        xml.append("<row r=\"").append(number).append("\">");
        for (int i = 0; i < values.size(); i++) {
            String column = columnName(i + 1);
            var value = values.get(i);
            xml.append("<c r=\"").append(column).append(number);
            // Excel numeric cells preserve up to 15 significant digits.
            if (value instanceof Number && new BigDecimal(value.toString()).precision() <= 15) {
                xml.append("\"><v>").append(value).append("</v></c>");
            } else {
                xml.append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(Objects.toString(value, ""))).append("</t></is></c>");
            }
        }
        xml.append("</row>");
    }

    /**
     * 将从 1 开始的列序号转换为 Excel 列字母。
     */
    private static String columnName(int number) {
        var name = new StringBuilder();
        while (number > 0) {
            number--;
            name.append((char) ('A' + number % 26));
            number /= 26;
        }
        return name.reverse().toString();
    }

    /**
     * 移除 XML 不允许的控制字符并转义文本中的保留字符。
     */
    private static String escape(String value) {
        var result = new StringBuilder();
        value.codePoints().filter(cp -> cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xd7ff
                || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000).forEach(result::appendCodePoint);
        return result.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 向工作簿 ZIP 包中写入指定路径的 UTF-8 XML 文件。
     */
    private static void entry(ZipOutputStream zip, String path, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
