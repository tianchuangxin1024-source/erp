package com.tian.erp.service.finance;

import com.tian.erp.common.exception.BusinessException;
import com.tian.erp.repository.finance.FinanceRepository;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static com.tian.erp.support.finance.FinanceFields.*;

/**
 * 校验凭证或报销单的附件归属、上传权限及文件限制，并存取数据库文件内容。
 */
@Service
public class AttachmentService {
    private final FinanceRepository db;
    private final FinanceQueryService queries;
    private final LedgerService ledger;

    /**
     * 注入附件数据访问、记录可见性查询和会计期间校验服务。
     */
    AttachmentService(FinanceRepository db, FinanceQueryService queries, LedgerService ledger) {
        this.db = db;
        this.queries = queries;
        this.ledger = ledger;
    }

    /**
     * 仅为操作者自己的草稿保存 PDF、PNG 或 JPEG，每份不超过 10 MB 且单据最多 10 份。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long upload(String resource, long id, MultipartFile file, long actor) throws IOException {
        String column = column(resource);
        String name = Objects.toString(file.getOriginalFilename(), "attachment");
        require(name.length() <= 255 && !name.matches("(?s).*[\\p{Cntrl}/\\\\].*"), "附件名无效");
        require(file.getSize() > 0 && file.getSize() <= 10 * 1024 * 1024, "附件大小应在 10 MB 以内");
        byte[] bytes = file.getBytes();
        String mime = fileType(bytes);
        if (resource.equals("gl/vouchers")) {
            var snapshot = db.row("fin_voucher", id, false);
            ledger.period(date(snapshot, "voucher_date"));
            var voucher = db.row("fin_voucher", id, true);
            state(id(voucher, "maker_id") == actor && "草稿".equals(voucher.get("status")), "仅制单人可为草稿上传附件");
        } else {
            var claim = db.row("fin_claim", id, true);
            state(id(claim, "applicant_id") == actor && "草稿".equals(claim.get("approval_status")), "仅申请人可为草稿上传附件");
        }
        var count = db.one("SELECT COUNT(*) total FROM fin_attachment WHERE " + column + "=:id", values("id", id));
        state(((Number) count.get("total")).intValue() < 10, "最多上传 10 份附件");
        long attachment = db.insert("fin_attachment", values(column, id, "file_name", name, "content_type", mime, "file_data", bytes));
        db.audit(actor, resource, "上传附件", id, "附件 " + attachment);
        return attachment;
    }

    /**
     * 确认记录及附件可见性后返回文件名与字节内容。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> download(String resource, long id, long attachment, Map<String, Object> actor) {
        var owner = queries.detail(resource, id, actor);
        String column = resource.equals("expense/approvals") ? "claim_id" : column(resource);
        if (resource.equals("expense/approvals")) {
            id = id(owner, "claim_id");
        }
        return db.one("SELECT file_name,file_data FROM fin_attachment WHERE " + column + "=:id AND id=:attachment",
                values("id", id, "attachment", attachment));
    }

    /**
     * 将支持的附件资源映射到数据库归属字段。
     */
    private String column(String resource) {
        return switch (resource) {
            case "gl/vouchers" -> "voucher_id";
            case "expense/claims" -> "claim_id";
            default -> throw BusinessException.unsupported();
        };
    }

    /**
     * 按文件头识别允许上传的媒体类型。
     */
    private String fileType(byte[] bytes) {
        if (prefix(bytes, 0x25, 0x50, 0x44, 0x46, 0x2d)) {
            return "application/pdf";
        }
        if (prefix(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return "image/png";
        }
        if (prefix(bytes, 0xff, 0xd8, 0xff)) {
            return "image/jpeg";
        }
        require(false, "附件应为 PDF、PNG 或 JPEG");
        throw new IllegalStateException();
    }

    /**
     * 判断文件字节是否以指定签名开头。
     */
    private boolean prefix(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (Byte.toUnsignedInt(bytes[i]) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
