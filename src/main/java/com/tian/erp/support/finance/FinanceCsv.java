package com.tian.erp.support.finance;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static com.tian.erp.support.finance.FinanceFields.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 解析带引号的 UTF-8 CSV 输入，并编码财务导出记录。
 */
@Component
public class FinanceCsv {
    /**
     * 校验文件大小、编码、必需列与可选列后返回最多 1000 条记录。
     */
    public List<Map<String, Object>> read(MultipartFile file, Set<String> required, Set<String> optional) throws IOException {
        require(file.getSize() > 0 && file.getSize() <= 2 * 1024 * 1024, "CSV 文件应在 2 MB 内");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.getBytes())).toString();
        } catch (CharacterCodingException ex) {
            throw new ResponseStatusException(BAD_REQUEST, "CSV 文件应为 UTF-8 编码");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        var rows = parse(text);
        require(rows.size() >= 2 && rows.size() <= 1001, "CSV 需要标题行与 1–1000 条记录");
        var headers = rows.get(0).stream().map(String::strip).toList();
        var distinct = new HashSet<>(headers);
        var allowed = new HashSet<>(required);
        allowed.addAll(optional);
        require(distinct.size() == headers.size() && distinct.containsAll(required) && allowed.containsAll(distinct),
                "CSV 列名有误，必填列: " + String.join(", ", new TreeSet<>(required)));
        var records = new ArrayList<Map<String, Object>>();
        for (int row = 1; row < rows.size(); row++) {
            var cells = rows.get(row);
            require(cells.size() == headers.size(), "CSV 第 " + (row + 1) + " 行列数不一致");
            Map<String, Object> record = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String value = cells.get(column).strip();
                if (!value.isEmpty()) {
                    record.put(headers.get(column), value);
                }
            }
            records.add(record);
        }
        return records;
    }

    /**
     * 逐字符解析 CSV 分隔符、转义引号和引号内换行。
     */
    private List<List<String>> parse(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                    closedQuote = true;
                } else {
                    cell.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    require(cell.isEmpty() && !closedQuote, "CSV 引号格式有误");
                    quoted = true;
                }
                case ',', '\r', '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    closedQuote = false;
                    if (c != ',') {
                        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                            i++;
                        }
                        if (row.size() > 1 || !row.get(0).isBlank()) {
                            rows.add(row);
                            require(rows.size() <= 1001, "CSV 明细最多 1000 行");
                        }
                        row = new ArrayList<>();
                    }
                }
                default -> {
                    require(!closedQuote, "CSV 引号后应为分隔符");
                    cell.append(c);
                }
            }
        }
        require(!quoted, "CSV 引号未闭合");
        if (!row.isEmpty() || !cell.isEmpty() || closedQuote) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * 将记录列名和数据编码为带 UTF-8 BOM 的 CSV 字节。
     */
    public static byte[] encode(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return new byte[0];
        }
        var headers = new ArrayList<>(rows.get(0).keySet());
        var content = new StringBuilder("\uFEFF").append(headers.stream().map(FinanceCsv::cell).collect(Collectors.joining(",")));
        for (var row : rows) {
            content.append("\r\n").append(headers.stream().map(row::get).map(FinanceCsv::cell).collect(Collectors.joining(",")));
        }
        return content.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 转义 CSV 单元格，并对可能触发公式的字符串加文本前缀。
     */
    private static String cell(Object value) {
        var text = Objects.toString(value, "");
        if (value instanceof String && text.stripLeading().matches("(?s)^[=+@\\-].*")) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
